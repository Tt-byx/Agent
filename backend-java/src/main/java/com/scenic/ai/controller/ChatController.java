package com.scenic.ai.controller;

import com.scenic.ai.agent.core.AgentLoop;
import com.scenic.ai.dto.ChatRequest;
import com.scenic.ai.dto.ChatResponse;
import com.scenic.ai.dto.Result;
import com.scenic.ai.gateway.RateLimiter;
import com.scenic.ai.service.ChatService;
import com.scenic.ai.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 对话入口（两条路径并存）。
 *
 * 路径一 · `POST /api/chat`：整段返回。稳定、可控，服务端完整掌握每一步（P7 的取舍）。
 * 路径二 · `POST /api/chat/stream`：SSE 真流式（Wave 5）。首字延迟前移，
 *          代价是「首包之后不可重试/切换候选」（见 RoutedStreamingChatModel）。
 *
 * 两条路径共用同一个 AgentLoop 骨架与同一批熔断器，只是模型输出方式不同。
 *
 * 限流：优先用 visitorId（业务身份），没有就用客户端 IP。被限流时返回明确提示而不是 500。
 */
@RestController
@RequestMapping("/api/chat")
@Slf4j
public class ChatController {

    private static final AtomicInteger SSE_THREAD_SEQ = new AtomicInteger();

    @Autowired
    private ChatService chatService;

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private com.scenic.ai.security.IdentityResolver identityResolver;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Value("${agent.stream.timeout-ms:300000}")
    private long streamTimeoutMs;

    /**
     * 2026-10-29（D05）· 是否信任 {@code X-Forwarded-For}。
     *
     * 默认 <b>false</b>：这个头是客户端可以任意伪装的，直接采信等于把限流维度交给攻击者 ——
     * 每次换一个假的 XFF 就是一批全新配额，限流形同不设防。
     * 只有部署在可信反向代理（nginx / SLB）之后，才把它置为 true。
     */
    @Value("${gateway.rate-limit.trust-forwarded-for:false}")
    private boolean trustForwardedFor;

    /** SSE 队列容量（2026-10-29 · D12） */
    private static final int SSE_QUEUE_CAPACITY = 64;

    /**
     * SSE 专用线程池。
     *
     * 为什么必须有：{@code chatStream} 内部是阻塞的（AgentLoop 是同步循环），
     * 而 SseEmitter 只是把**响应**变成异步 —— 若不把执行挪出 Tomcat 线程，
     * 每个流式请求都会占住一个 Tomcat 工作线程直到整轮结束，
     * 并发一上来就会把 Tomcat 的线程池占满、拖垮所有接口。
     *
     * <p><b>2026-10-29（D12）· 队列必须有界。</b>
     * 原实现用 {@code Executors.newFixedThreadPool(8)}，它等价于
     * {@code new ThreadPoolExecutor(8, 8, 0, MS, new LinkedBlockingQueue<>())} ——
     * 队列容量是 {@code Integer.MAX_VALUE}，等于**无限收**。推演一次就明白后果：
     * 100 个并发流式请求 → 8 个在跑、92 个进队列 → 用户看到「SSE 连接已建立（HTTP 200 + 响应头）
     * 但一个字节都不来」→ 排队超过 300s 后 emitter 超时断开。
     * 「连接建立了但没数据」是最难排查的一类故障：用户以为网慢，运维看连接数正常。
     *
     * <p>所以改成有界 64 + {@code AbortPolicy}：满了就**明确拒绝**（推一条 error 事件），
     * 而不是让用户对着一个不产数据的连接干等。
     *
     * <p>注意这里**不能**用 {@code CallerRunsPolicy}（工具池用了它）：本池的「调用线程」
     * 是 Tomcat 工作线程，CallerRuns 等于让 Tomcat 线程自己跑完整轮对话 ——
     * 正好破坏了这个池存在的意义。工具池能用，是因为它的调用方（AgentLoop）可以承受反压。
     *
     * <p>配 {@link #shutdownSseExecutor()} 优雅停机：否则容器滚动重启时，
     * 正在跑的流会被直接掐断，用户看到半句话。
     */
    private final ExecutorService sseExecutor = createSseExecutor();

    private ExecutorService createSseExecutor() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                8, 8, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(SSE_QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "sse-worker-" + SSE_THREAD_SEQ.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        log.info("[SSE] 工作线程池就绪：8 线程 / 队列 {} / 满则拒绝", SSE_QUEUE_CAPACITY);
        return pool;
    }

    /**
     * 优雅停机：先不再接新任务，等在跑的流结束（最多 10s），再强制中断。
     * 这样滚动重启时用户拿到的是「完整回答」，而不是被掐断的半句话。
     */
    @jakarta.annotation.PreDestroy
    public void shutdownSseExecutor() {
        sseExecutor.shutdown();
        try {
            if (!sseExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("[SSE] 优雅停机超时（10s），强制中断剩余流式任务");
                sseExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sseExecutor.shutdownNow();
        }
        log.info("[SSE] 工作线程池已关闭");
    }

    /** 同步对话：整段返回 */
    @PostMapping
    public Result<ChatResponse> chat(@RequestBody @Valid ChatRequest request,
                                     HttpServletRequest httpRequest) {
        RateLimitSubject subject = resolveRateLimitSubject(request, httpRequest);

        // Wave 3：带上维度标签，指标能区分「按身份限流」还是「按 IP 限流」
        if (!rateLimiter.allow(subject.value(), subject.dimension())) {
            log.info("[RATE] 拒绝请求：key={}", subject.value());
            return Result.error(429, "您的提问有点太频繁了，请休息一下再问～");
        }

        ChatResponse response = chatService.chat(request);
        return Result.success(response);
    }

    /**
     * Wave 5 · 流式对话（SSE）。
     *
     * 事件类型：
     *   meta    —— 首个事件，带 traceId（前端可记下来便于报障反查）
     *   session —— 会话标识（首次对话由服务端生成；前端须存下来供后续消息复用）
     *   step    —— 步骤边界（start / end，end 带 usedTools）
     *   token   —— **正式回答**的增量文本（推理增量已在服务端丢弃）
     *   discard —— 撤销某步已显示的文本（该步其实在调工具）
     *   done    —— 最终完整答案（前端可据此校验拼接结果）
     *   error   —— 失败提示（此时不会再有 done）
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody @Valid ChatRequest request,
                             HttpServletRequest httpRequest) {
        RateLimitSubject subject = resolveRateLimitSubject(request, httpRequest);

        SseEmitter emitter = new SseEmitter(streamTimeoutMs);

        if (!rateLimiter.allow(subject.value(), subject.dimension())) {
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data("您的提问有点太频繁了，请休息一下再问～"));
            } catch (Exception ignored) {
                // 客户端可能已断开
            }
            emitter.complete();
            return emitter;
        }

        String traceId = TraceIdFilter.currentTraceId();

        // ── 关键：把「当前身份」捕获下来，随任务一起带到 SSE 工作线程 ──
        // SecurityContextHolder 默认是 ThreadLocal 策略，身份只绑在 Tomcat 线程上；
        // 而下面把执行体交给了 sseExecutor 的**另一个线程**。若不显式传递，
        // 工作线程里 CurrentUser.get() 恒为 null，会导致：
        //   ① 已登录用户的历史会话被判为「不属于当前账号」→ 403；
        //   ② 登录用户新建的会话 userId 落成 NULL，沦为无主会话；
        //   ③ 长期记忆键退化为会话级，跨会话记忆失效。
        // 这里在提交前取快照，在 worker 里恢复、结束前清理（Spring 官方推荐做法）。
        SecurityContext securityContext = SecurityContextHolder.getContext();
        AtomicBoolean clientGone = new AtomicBoolean(false);

        // 2026-10-29（D14）· 断连检测的两条补充信号。
        // 主动检测手段仍然是「send 失败」（见 push），但那只会在**下一次推送**时才发现；
        // 这两个回调能让「超时 / 传输错误」更早把标记置上。
        // 注意不要用 onCompletion：服务端正常 complete 时它也会触发，会把正常结束误判成断连。
        emitter.onTimeout(() -> {
            clientGone.set(true);
            log.info("[SSE] emitter 超时，标记客户端已离开");
        });
        emitter.onError(e -> clientGone.set(true));

        try {
            sseExecutor.execute(() -> {
                SecurityContextHolder.setContext(securityContext);
                try {
                    push(emitter, clientGone, "meta", "{\"traceId\":\"" + traceId + "\"}");
                    chatService.chatStream(request, new AgentLoop.StreamSink() {
                        @Override
                        public void onSession(String sid) {
                            // 会话标识下发：前端拿到后把它存起来，后续消息再带上，
                            // 就能连续归入同一会话（否则每次提问都会新建会话）。
                            push(emitter, clientGone, "session",
                                    "{\"sessionId\":" + toJsonString(sid) + "}");
                        }

                        @Override
                        public void onStepStart(int step) {
                            push(emitter, clientGone, "step",
                                    "{\"step\":" + step + ",\"phase\":\"start\"}");
                        }

                        @Override
                        public void onToken(int step, String token) {
                            push(emitter, clientGone, "token", token);
                        }

                        @Override
                        public void onDiscard(int step) {
                            push(emitter, clientGone, "discard", "{\"step\":" + step + "}");
                        }

                        @Override
                        public void onStepEnd(int step, boolean usedTools) {
                            push(emitter, clientGone, "step", "{\"step\":" + step
                                    + ",\"phase\":\"end\",\"usedTools\":" + usedTools + "}");
                        }

                        @Override
                        public void onDone(String answer) {
                            push(emitter, clientGone, "done", answer);
                            emitter.complete();
                        }

                        /**
                         * D14：把「客户端已离开」这个事实交给 AgentLoop。
                         * 原实现里 clientGone 只在 push 内部自查，循环完全不知道 ——
                         * 用户关掉页面后服务端照样跑满 120s、照样调工具、照样烧 token。
                         */
                        @Override
                        public boolean isCancelled() {
                            return clientGone.get();
                        }

                        @Override
                        public void onError(String message) {
                            push(emitter, clientGone, "error", message);
                            emitter.complete();
                        }
                    });
                } catch (com.scenic.ai.exception.ApiException e) {
                    // 业务异常（如会话归属 403）：首包可能已经推送，响应已 committed，
                    // 此时若再走 completeWithError，GlobalExceptionHandler / Tomcat 会二次写响应，
                    // 触发 "Unable to handle the Spring Security Exception because the response
                    // is already committed"，把一条清晰的业务错误变成一堆堆栈噪音。
                    // 正确做法：把业务提示作为 error 事件推给前端，再正常 complete。
                    log.warn("[SSE] 业务异常收口：code={}, msg={}", e.getCode(), e.getMessage());
                    push(emitter, clientGone, "error", e.getMessage());
                    emitter.complete();
                } catch (Exception e) {
                    log.error("[SSE] 流式对话失败", e);
                    push(emitter, clientGone, "error", "服务内部错误，请稍后重试");
                    emitter.complete();
                } finally {
                    // 线程池会复用这条 worker 线程：必须清掉身份，
                    // 否则下一个请求可能意外继承到上一个用户的身份（越权风险）。
                    SecurityContextHolder.clearContext();
                }
            });
        } catch (RejectedExecutionException e) {
            // D12：队列满时明确拒绝。原实现队列无界，永远不会走到这里 ——
            // 代价是用户在队列里静默排队到 300s 超时，连一句提示都拿不到。
            log.warn("[SSE] 工作线程池与队列均已满（{} 线程 / 队列 {}），拒绝本次流式请求",
                    8, SSE_QUEUE_CAPACITY);
            push(emitter, clientGone, "error", "当前咨询人数较多，请稍后再试");
            emitter.complete();
        }

        return emitter;
    }

    /**
     * 推送一个事件。客户端断开时 send 会抛异常 —— 这里记 debug 并打上 clientGone 标记，
     * 后续事件直接跳过，避免对着已断开的连接反复尝试、刷满日志。
     */
    private void push(SseEmitter emitter, AtomicBoolean clientGone, String event, String data) {
        if (clientGone.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (Exception e) {
            clientGone.set(true);
            log.debug("[SSE] 推送失败，判定客户端已断开（后续事件跳过）: {}", e.getMessage());
        }
    }

    /**
     * 限流维度（键 + 指标 label）。
     *
     * <p>2026-10-29（D05）：原实现取 {@code request.getVisitorId()} —— 那是 body 里客户端自己写的字段，
     * 而同一个字段在记忆链路（{@code resolveMemoryKey}）早就被明确废弃了。
     * 结果就是「换个 visitorId 就是一批新配额」，限流等于不设防。
     *
     * <p>现在与记忆链路共用 {@link com.scenic.ai.security.IdentityResolver}：
     * 登录 → {@code user:{id}}；有效游客凭证 → {@code visitor:{vid}}；都没有 → {@code ip:...}。
     */
    private RateLimitSubject resolveRateLimitSubject(ChatRequest request, HttpServletRequest httpRequest) {
        com.scenic.ai.security.IdentityResolver.Identity identity =
                identityResolver.resolve(request.getVisitorToken());
        String key = identity.rateLimitKey();
        if (key != null) {
            return new RateLimitSubject(key, identity.rateLimitDimension());
        }
        return new RateLimitSubject("ip:" + clientIp(httpRequest), "ip");
    }

    /** 限流维度的取值对：(key, 指标 label) */
    private record RateLimitSubject(String value, String dimension) {}

    /**
     * 客户端 IP。
     *
     * <p>2026-10-29（D05）：默认**不读** {@code X-Forwarded-For} —— 客户端可以随便写这个头，
     * 采信它等于让攻击者自选限流维度。部署在可信反向代理之后再打开
     * {@code gateway.rate-limit.trust-forwarded-for}。
     *
     * <p>打开后取**最后一段**而不是第一段：第一段是客户端自己塞进去的，
     * nginx 的 {@code $proxy_add_x_forwarded_for} 只会往后追加，所以第一段永远可伪造；
     * 只有「离服务端最近的一跳」在可信代理前提下才有意义。
     * 多层代理场景请改用 nginx 的 real_ip 模块并限定可信网段，不要靠这里解析。
     */
    private String clientIp(HttpServletRequest request) {
        if (!trustForwardedFor) {
            return request.getRemoteAddr();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return request.getRemoteAddr();
        }
        String[] hops = forwarded.split(",");
        String nearest = hops[hops.length - 1].strip();
        return nearest.isEmpty() ? request.getRemoteAddr() : nearest;
    }

    /** 把字符串安全地序列化为 JSON 字面量（sessionId 是 UUID，但转义交给 Jackson 更稳） */
    private String toJsonString(String value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            // 理论上不会发生；兜底为空字符串，不因序列化失败中断流式
            return "\"\"";
        }
    }
}
