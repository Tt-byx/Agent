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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    @Value("${agent.stream.timeout-ms:300000}")
    private long streamTimeoutMs;

    /**
     * SSE 专用线程池。
     *
     * 为什么必须有：{@code chatStream} 内部是阻塞的（AgentLoop 是同步循环），
     * 而 SseEmitter 只是把**响应**变成异步 —— 若不把执行挪出 Tomcat 线程，
     * 每个流式请求都会占住一个 Tomcat 工作线程直到整轮结束，
     * 并发一上来就会把线程池占满、拖垮所有接口。
     */
    private final ExecutorService sseExecutor = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable, "sse-worker-" + SSE_THREAD_SEQ.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    /** 同步对话：整段返回 */
    @PostMapping
    public Result<ChatResponse> chat(@RequestBody @Valid ChatRequest request,
                                     HttpServletRequest httpRequest) {
        boolean byVisitor = request.getVisitorId() != null && !request.getVisitorId().isBlank();
        String key = byVisitor ? request.getVisitorId() : clientIp(httpRequest);

        // Wave 3：带上维度标签，指标能区分「按身份限流」还是「按 IP 限流」
        if (!rateLimiter.allow(key, byVisitor ? "visitor" : "ip")) {
            log.info("[RATE] 拒绝请求：key={}", key);
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
     *   step    —— 步骤边界（start / end，end 带 usedTools）
     *   token   —— **正式回答**的增量文本（推理增量已在服务端丢弃）
     *   discard —— 撤销某步已显示的文本（该步其实在调工具）
     *   done    —— 最终完整答案（前端可据此校验拼接结果）
     *   error   —— 失败提示（此时不会再有 done）
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody @Valid ChatRequest request,
                             HttpServletRequest httpRequest) {
        boolean byVisitor = request.getVisitorId() != null && !request.getVisitorId().isBlank();
        String key = byVisitor ? request.getVisitorId() : clientIp(httpRequest);

        SseEmitter emitter = new SseEmitter(streamTimeoutMs);

        if (!rateLimiter.allow(key, byVisitor ? "visitor" : "ip")) {
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
        AtomicBoolean clientGone = new AtomicBoolean(false);

        sseExecutor.execute(() -> {
            try {
                push(emitter, clientGone, "meta", "{\"traceId\":\"" + traceId + "\"}");
                chatService.chatStream(request, new AgentLoop.StreamSink() {
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

                    @Override
                    public void onError(String message) {
                        push(emitter, clientGone, "error", message);
                        emitter.complete();
                    }
                });
            } catch (Exception e) {
                log.error("[SSE] 流式对话失败", e);
                push(emitter, clientGone, "error", "服务内部错误，请稍后重试");
                emitter.completeWithError(e);
            }
        });

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

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].strip();
        }
        return request.getRemoteAddr();
    }
}
