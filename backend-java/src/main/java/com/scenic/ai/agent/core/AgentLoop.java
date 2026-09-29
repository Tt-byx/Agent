package com.scenic.ai.agent.core;

import com.scenic.ai.gateway.AgentMetrics;
import com.scenic.ai.gateway.SemanticCache;
import com.scenic.ai.memory.LongTermMemory;
import com.scenic.ai.memory.MemoryContextHolder;
import com.scenic.ai.memory.ShortTermMemory;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * P7 · ReAct Agent 循环（reason → act → observe），由本项目**自己实现**而不是交给框架黑盒。
 *
 * P8 叠加的三块能力：
 *   1. **语义缓存**：命中直接返回，跳过整条链路（延迟秒级→毫秒级，省一次完整模型调用）；
 *   2. **三层记忆**：短期滑窗（Redis）+ 会话摘要（Redis）+ 长期偏好（pgvector），
 *      在每轮开始时注入系统提示词；
 *   3. **可观测**：每次循环、每次工具调用、缓存命中、记忆写入都打点。
 *
 * 可靠性设计（P7 验收要求）：
 *   - **最大步数**：`agent.max-steps`，防止模型反复调工具陷入死循环；
 *   - **整体超时**：`agent.timeout-seconds`，每步开始前检查，超时即降级返回；
 *   - **工具异常回灌**：由 {@link AgentToolbox#execute} 转成错误文本，模型能读到并自纠；
 *   - **模型异常降级**：模型调用失败返回友好提示，不让请求崩掉；
 *   - **强制收口**：达到步数上限后，去掉工具再问一次，让模型基于已有观察作答。
 */
@Service
@Slf4j
public class AgentLoop {

    /** 单步记录 */
    public record Step(int step, String toolName, String arguments, String observation, boolean failed) {}

    /** 一轮 Agent 执行结果 */
    public record Result(String answer, List<Step> steps, int stepCount, boolean converged, String stopReason) {
        public int toolCallCount() {
            return steps.size();
        }
    }

    private static final String FALLBACK_ANSWER = "抱歉，我这边暂时没能组织出合适的回答，能换个问法再试一次吗？";
    private static final String MODEL_ERROR_ANSWER = "抱歉，AI 服务暂时不可用，请稍后再试。";

    private final ChatModel chatModel;
    private final AgentToolbox toolbox;
    private final ShortTermMemory shortTermMemory;
    private final LongTermMemory longTermMemory;
    private final SemanticCache semanticCache;
    private final AgentMetrics metrics;
    private final String baseSystemPrompt;
    private final int maxSteps;
    private final long timeoutSeconds;
    private final int maxToolsPerStep;
    /** Wave 6 · 系统提示词的字符预算（超出则按优先级收敛短期滑窗） */
    private final int maxContextChars;
    /** Wave 5 · 流式路径专用（SSE）；同步路径仍用 chatModel */
    private final StreamingChatModel streamingChatModel;

    public AgentLoop(ChatModel chatModel,
                     StreamingChatModel streamingChatModel,
                     AgentToolbox toolbox,
                     ShortTermMemory shortTermMemory,
                     LongTermMemory longTermMemory,
                     SemanticCache semanticCache,
                     AgentMetrics metrics,
                     @Value("${agent.max-steps:6}") int maxSteps,
                     @Value("${agent.timeout-seconds:120}") long timeoutSeconds,
                     @Value("${agent.max-tools-per-step:4}") int maxToolsPerStep,
                     @Value("${agent.context.max-chars:6000}") int maxContextChars,
                     @Value("${agent.system-prompt:prompts/scenic-agent-system.txt}") String systemPromptPath)
            throws IOException {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.toolbox = toolbox;
        this.shortTermMemory = shortTermMemory;
        this.longTermMemory = longTermMemory;
        this.semanticCache = semanticCache;
        this.metrics = metrics;
        this.maxSteps = Math.max(maxSteps, 1);
        this.timeoutSeconds = timeoutSeconds;
        // Wave 1 · 单步工具上限：模型一次返回过多工具调用会放大延迟与成本
        this.maxToolsPerStep = Math.max(1, maxToolsPerStep);
        // Wave 6 · 上下文预算：至少留 1000 字，否则连人设都放不下
        this.maxContextChars = Math.max(1000, maxContextChars);
        this.baseSystemPrompt = new ClassPathResource(systemPromptPath)
                .getContentAsString(StandardCharsets.UTF_8);
        log.info("AgentLoop 就绪：最大步数 {}，整体超时 {}s，单步工具上限 {}，工具 {} 个",
                this.maxSteps, this.timeoutSeconds, this.maxToolsPerStep, toolbox.size());
    }

    public Result run(String sessionId, String userMessage) {
        return run(sessionId, sessionId, userMessage);
    }

    /**
     * @param memoryKey 长期记忆的归属键：登录用户 = userId，匿名 = 前端持久化的 visitorId，
     *                  退化为 sessionId（此时记忆只在本会话内有效）
     */
    public Result run(String sessionId, String memoryKey, String userMessage) {
        return runWith(sessionId, memoryKey, userMessage, toolbox, baseSystemPrompt);
    }

    /**
     * P9 · 可注入工具箱与系统提示词的循环入口。
     * 多 Agent 编排时：Supervisor 传「只含委派工具」的工具箱，各专家传「按名单裁剪」的工具箱。
     */
    public Result runWith(String sessionId, String memoryKey, String userMessage,
                          AgentToolbox agentToolbox, String systemPrompt) {
        metrics.runStarted();

        // ===== P8：语义缓存命中则直接返回，跳过整条链路 =====
        var cached = semanticCache.lookup(userMessage);
        if (cached.isPresent()) {
            metrics.cacheHit();
            log.info("[CACHE] 命中，跳过 Agent 循环（question={}）", userMessage);
            return new Result(cached.get(), List.of(), 0, true, "CACHE_HIT");
        }
        metrics.cacheMiss();

        // ===== P8：组装带记忆的系统提示词 =====
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(buildSystemMessage(systemPrompt, sessionId, memoryKey, userMessage)));
        messages.add(UserMessage.from(userMessage));

        List<Step> trace = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;

        for (int step = 1; step <= maxSteps; step++) {
            if (System.currentTimeMillis() > deadline) {
                log.warn("Agent 整体超时（{}s），已执行 {} 步，降级返回", timeoutSeconds, step - 1);
                metrics.runAborted();
                return new Result(convergeAfterAbort(messages), trace, step - 1, false, "TIMEOUT");
            }

            AiMessage aiMessage;
            long modelStart = System.currentTimeMillis();
            try {
                ChatResponse response = chatModel.chat(ChatRequest.builder()
                        .messages(messages)
                        .toolSpecifications(agentToolbox.specifications())
                        .build());
                aiMessage = response.aiMessage();
                // Wave 3：token 计数已下移到 RoutedChatModel —— 只有路由层知道本次回答
                // 实际由哪个候选产出，成本才能按模型（flash / pro）归因
            } catch (Exception e) {
                metrics.agentStep(step, "model", System.currentTimeMillis() - modelStart);
                log.error("Agent 第 {} 步模型调用失败: {}", step, e.getMessage(), e);
                metrics.runAborted();
                metrics.degrade("model");
                return new Result(MODEL_ERROR_ANSWER, trace, step - 1, false, "MODEL_ERROR");
            }
            // Wave 3 · 单步耗时：回答「整轮延迟耗在哪一步、是模型慢还是工具慢」
            metrics.agentStep(step, "model", System.currentTimeMillis() - modelStart);
            messages.add(aiMessage);

            // 终止条件：模型不再请求工具，说明它认为可以作答了
            if (!aiMessage.hasToolExecutionRequests()) {
                String answer = aiMessage.text();
                if (answer == null || answer.isBlank()) {
                    answer = FALLBACK_ANSWER;
                }
                metrics.runConverged();
                rememberTurn(sessionId, userMessage, answer);
                semanticCache.store(userMessage, answer);
                log.info("Agent 收敛：共 {} 步、{} 次工具调用", step, trace.size());
                return new Result(answer, trace, step, true, "CONVERGED");
            }

            // act → observe（工具执行期间暴露 memoryKey，供记忆类工具写入）
            MemoryContextHolder.set(memoryKey);
            try {
                List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
                // Wave 1 · 单步工具数上限：超出部分不执行，直接以失败文本回灌，
                // 让模型知道"这一步要的太多了"，而不是无限放大本步的耗时与成本。
                if (requests.size() > maxToolsPerStep) {
                    log.warn("第 {} 步请求了 {} 个工具，超过单步上限 {}，仅执行前 {} 个",
                            step, requests.size(), maxToolsPerStep, maxToolsPerStep);
                }
                for (int i = 0; i < requests.size(); i++) {
                    ToolExecutionRequest request = requests.get(i);

                    if (i >= maxToolsPerStep) {
                        metrics.toolFailed(request.name());
                        String rejected = "工具「" + request.name() + "」执行失败：单步工具调用数超过上限（"
                                + maxToolsPerStep + "），本次未执行。请在下一步再调用它。";
                        trace.add(new Step(step, request.name(), request.arguments(), rejected, true));
                        messages.add(ToolExecutionResultMessage.from(request, rejected));
                        continue;
                    }

                    // Wave 3：工具指标带工具名，并记录单步耗时（phase=tool）
                    metrics.toolCalled(request.name());
                    long toolStart = System.currentTimeMillis();
                    String observation = agentToolbox.execute(request, sessionId);
                    metrics.agentStep(step, "tool", System.currentTimeMillis() - toolStart);
                    boolean failed = observation.contains("执行失败：");
                    if (failed) {
                        metrics.toolFailed(request.name());
                    }
                    trace.add(new Step(step, request.name(), request.arguments(), observation, failed));
                    messages.add(ToolExecutionResultMessage.from(request, observation));
                }
            } finally {
                MemoryContextHolder.clear();
            }
        }

        log.warn("Agent 达到最大步数 {}，触发强制收口", maxSteps);
        metrics.runAborted();
        return new Result(convergeAfterAbort(messages), trace, maxSteps, false, "MAX_STEPS");
    }

    /**
     * Wave 5 · 流式输出回调（由 SSE 端点实现）。
     *
     * 事件语义：
     *   onStepStart → 某步开始（前端可提示"正在检索/思考"）
     *   onToken     → **正式回答**的增量文本
     *   onDiscard   → 撤销某步已显示的文本（该步其实在请求工具，不是最终答案）
     *   onStepEnd   → 某步结束（usedTools 标明该步是否调了工具）
     *   onDone      → 最终完整答案（前端用它做兜底校验）
     */
    public interface StreamSink {
        default void onStepStart(int step) {}

        void onToken(int step, String token);

        default void onDiscard(int step) {}

        default void onStepEnd(int step, boolean usedTools) {}

        void onDone(String answer);

        void onError(String message);
    }

    /**
     * Wave 5 · 流式执行（SSE 路径）。与 {@link #runWith} 同构 ——
     * 同样的步数上限、整体超时、工具执行与异常回灌、记忆与缓存写入、强制收口，
     * 唯一区别是**模型输出以增量回调实时推给前端**，因此首字延迟不再等于整轮耗时。
     *
     * 两个关键取舍（写在这里，因为它们决定了这套实现能不能用）：
     *
     *   ① **只推 onPartialResponse**。本项目的 DeepSeek flash 会输出大量推理 token
     *      （实测占输出的大头），它们走 onPartialThinking 回调 —— 这里不作实现（默认丢弃）。
     *      否则用户会看到满屏内部思考。这是流式在本项目能用的前提。
     *
     *   ② **推流时无法预判哪一步是最终答案**。所以约定「每步 token 带 step 标记；
     *      步结束时若该步请求了工具，就发 onDiscard(step) 让前端撤销」——
     *      正确性由这个撤销协议兜底，而不是靠猜测。
     */
    public Result runStreaming(String sessionId, String memoryKey, String question, StreamSink sink) {
        metrics.runStarted();

        // 1. 语义缓存命中：流式路径同样受益（直接给完整答案，不再调模型）
        java.util.Optional<String> cached = semanticCache.lookup(question);
        if (cached.isPresent()) {
            metrics.cacheHit();
            String answer = cached.get();
            // 缓存命中也以 token 事件下发：前端只维护一条渲染路径（收到 token 就追加），
            // 不必为「缓存命中」单独写分支。内容与 done 完全一致，只是少了逐字延迟。
            sink.onToken(0, answer);
            sink.onDone(answer);
            return new Result(answer, List.of(), 0, true, "CACHE_HIT");
        }
        metrics.cacheMiss();

        String systemMessage = buildSystemMessage(baseSystemPrompt, sessionId, memoryKey, question);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemMessage));
        messages.add(UserMessage.from(question));

        List<Step> trace = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;

        for (int step = 1; step <= maxSteps; step++) {
            if (System.currentTimeMillis() > deadline) {
                log.warn("[STREAM] Agent 整体超时（{}s），已执行 {} 步，收口返回", timeoutSeconds, step - 1);
                metrics.runAborted();
                String closing = convergeAfterAbort(messages);
                sink.onDone(closing);
                return new Result(closing, trace, step - 1, false, "TIMEOUT");
            }

            sink.onStepStart(step);
            long modelStart = System.currentTimeMillis();
            final int currentStep = step;

            java.util.concurrent.atomic.AtomicReference<ChatResponse> responseRef =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<Throwable> errorRef =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

            try {
                streamingChatModel.chat(
                        ChatRequest.builder()
                                .messages(messages)
                                .toolSpecifications(toolbox.specifications())
                                .build(),
                        new dev.langchain4j.model.chat.response.StreamingChatResponseHandler() {
                            @Override
                            public void onPartialResponse(String partialResponse) {
                                // 只推正式回答；推理增量走 onPartialThinking（此处不实现 = 丢弃）
                                sink.onToken(currentStep, partialResponse);
                            }

                            @Override
                            public void onCompleteResponse(ChatResponse response) {
                                responseRef.set(response);
                                latch.countDown();
                            }

                            @Override
                            public void onError(Throwable error) {
                                errorRef.set(error);
                                latch.countDown();
                            }
                        });

                if (!latch.await(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new java.util.concurrent.TimeoutException("流式模型调用超时");
                }
                if (errorRef.get() != null) {
                    throw new RuntimeException(errorRef.get());
                }
            } catch (Exception e) {
                metrics.agentStep(step, "model", System.currentTimeMillis() - modelStart);
                log.error("[STREAM] 第 {} 步模型调用失败: {}", step, e.getMessage(), e);
                metrics.runAborted();
                metrics.degrade("model");
                sink.onError(MODEL_ERROR_ANSWER);
                return new Result(MODEL_ERROR_ANSWER, trace, step - 1, false, "MODEL_ERROR");
            }
            metrics.agentStep(step, "model", System.currentTimeMillis() - modelStart);

            ChatResponse response = responseRef.get();
            AiMessage aiMessage = response == null ? null : response.aiMessage();
            if (aiMessage == null) {
                metrics.runAborted();
                sink.onError(MODEL_ERROR_ANSWER);
                return new Result(MODEL_ERROR_ANSWER, trace, step - 1, false, "MODEL_ERROR");
            }
            messages.add(aiMessage);

            // 终止条件：模型不再请求工具 → 这一步的文本就是最终答案（已边收边推给前端）
            if (!aiMessage.hasToolExecutionRequests()) {
                String answer = aiMessage.text();
                if (answer == null || answer.isBlank()) {
                    answer = FALLBACK_ANSWER;
                }
                metrics.runConverged();
                rememberTurn(sessionId, question, answer);
                semanticCache.store(question, answer);
                sink.onStepEnd(step, false);
                sink.onDone(answer);
                log.info("[STREAM] Agent 收敛：共 {} 步、{} 次工具调用", step, trace.size());
                return new Result(answer, trace, step, true, "CONVERGED");
            }

            // 该步请求了工具 → 它的增量文本不是给用户的答案，通知前端撤销
            sink.onDiscard(step);

            MemoryContextHolder.set(memoryKey);
            try {
                List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
                if (requests.size() > maxToolsPerStep) {
                    log.warn("[STREAM] 第 {} 步请求了 {} 个工具，超过单步上限 {}",
                            step, requests.size(), maxToolsPerStep);
                }
                for (int i = 0; i < requests.size(); i++) {
                    ToolExecutionRequest request = requests.get(i);

                    if (i >= maxToolsPerStep) {
                        metrics.toolFailed(request.name());
                        String rejected = "工具「" + request.name() + "」执行失败：单步工具调用数超过上限（"
                                + maxToolsPerStep + "），本次未执行。请在下一步再调用它。";
                        trace.add(new Step(step, request.name(), request.arguments(), rejected, true));
                        messages.add(ToolExecutionResultMessage.from(request, rejected));
                        continue;
                    }

                    metrics.toolCalled(request.name());
                    long toolStart = System.currentTimeMillis();
                    String observation = toolbox.execute(request, sessionId);
                    metrics.agentStep(step, "tool", System.currentTimeMillis() - toolStart);
                    boolean failed = observation.contains("执行失败：");
                    if (failed) {
                        metrics.toolFailed(request.name());
                    }
                    trace.add(new Step(step, request.name(), request.arguments(), observation, failed));
                    messages.add(ToolExecutionResultMessage.from(request, observation));
                }
            } finally {
                MemoryContextHolder.clear();
            }
            sink.onStepEnd(step, true);
        }

        log.warn("[STREAM] 达到最大步数 {}，触发强制收口", maxSteps);
        metrics.runAborted();
        String closing = convergeAfterAbort(messages);
        sink.onDone(closing);
        return new Result(closing, trace, maxSteps, false, "MAX_STEPS");
    }

    /**
     * 组装带记忆的系统提示词：
     * 基础人设与规则 + 长期偏好（跨会话）+ 短期摘要与本轮上下文（会话内）。
     */
    /**
     * 组装带记忆的系统提示词。
     *
     * 优先级：基础人设（必留）→ 长期偏好（跨会话，按 memoryKey）→ 会话摘要 → 最近若干轮（按 sessionId）。
     *
     * Wave 6 修正两处：
     *   ① **短期记忆与摘要改按 sessionId 读写**。原先这里用 memoryKey 读取，而
     *      {@link #rememberTurn} 是按 sessionId 写入的 —— 只要两者不同
     *      （登录用户的 memoryKey 是 {@code user:{id}}，必然不等于会话 ID），
     *      短期记忆就永远读不到、形同虚设。
     *   ② **按 token 预算动态收敛滑窗**：固定部分（人设 + 偏好 + 摘要）先占位，
     *      剩余预算决定还能带几轮对话，避免长会话把上下文撑爆。
     */
    private String buildSystemMessage(String basePrompt, String sessionId, String memoryKey, String query) {
        StringBuilder sb = new StringBuilder(basePrompt);

        // 1) 长期偏好（跨会话共享，按 memoryKey）
        List<String> preferences = longTermMemory.recall(memoryKey, query);
        metrics.memoryRecalled(preferences.size());
        if (!preferences.isEmpty()) {
            sb.append("\n\n【关于这位游客的长期偏好（来自历史对话，可主动利用）】\n");
            for (int i = 0; i < preferences.size(); i++) {
                sb.append(i + 1).append(". ").append(preferences.get(i)).append('\n');
            }
        }

        // 2) 会话摘要与短期滑窗（会话内上下文，按 sessionId）
        String summary = shortTermMemory.summary(sessionId);
        List<ShortTermMemory.Turn> recent = shortTermMemory.recent(sessionId);

        if (summary != null && !summary.isBlank()) {
            sb.append("\n\n【本会话此前内容的摘要】\n").append(summary).append('\n');
        }

        if (!recent.isEmpty()) {
            // Wave 6 · token 预算：每条最多 200 字（见 abbreviate），据此算出剩余预算能放几条
            int remainingChars = Math.max(0, maxContextChars - sb.length());
            int maxMessages = Math.max(2, remainingChars / 200);
            int fromIndex = Math.max(0, recent.size() - maxMessages);
            List<ShortTermMemory.Turn> window = recent.subList(fromIndex, recent.size());
            if (fromIndex > 0) {
                metrics.degrade("context_trim");
                log.info("上下文预算 {} 字：短期滑窗由 {} 条收敛为最近 {} 条",
                        maxContextChars, recent.size(), window.size());
            }
            sb.append("\n\n【本会话最近几轮对话】\n");
            for (ShortTermMemory.Turn turn : window) {
                sb.append(turn.role().equals("user") ? "游客: " : "导游: ")
                        .append(abbreviate(turn.content(), 200)).append('\n');
            }
        }
        return sb.toString();
    }

    /** 记录本轮对话，并在超出滑窗时把溢出部分摘要化 */
    private void rememberTurn(String sessionId, String question, String answer) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        shortTermMemory.append(sessionId, "user", question);
        shortTermMemory.append(sessionId, "assistant", answer);

        long size = shortTermMemory.size(sessionId);
        // Wave 6 · 修 B3：窗口容量来自配置（memory.short-term.max-turns），不再硬编码 20
        int window = shortTermMemory.windowSize();
        if (size > window) {
            List<ShortTermMemory.Turn> drained = shortTermMemory.drain(sessionId, size - window);
            summarize(sessionId, drained);
        }
    }

    /** 把溢出的旧对话摘要化（best-effort，失败不影响主流程） */
    private void summarize(String sessionId, List<ShortTermMemory.Turn> drained) {
        if (drained == null || drained.isEmpty()) {
            return;
        }
        try {
            StringBuilder transcript = new StringBuilder();
            String previous = shortTermMemory.summary(sessionId);
            if (previous != null && !previous.isBlank()) {
                transcript.append("已有摘要：").append(previous).append("\n\n新增对话：\n");
            }
            for (ShortTermMemory.Turn turn : drained) {
                transcript.append(turn.role().equals("user") ? "游客: " : "导游: ")
                        .append(abbreviate(turn.content(), 300)).append('\n');
            }

            AiMessage result = chatModel.chat(ChatRequest.builder()
                    .messages(
                            SystemMessage.from("你是会议纪要助手。请把下面的对话压缩成不超过 150 字的要点摘要，"
                                    + "保留游客的兴趣偏好、已得到的结论和未解决的问题。只输出摘要本身。"),
                            UserMessage.from(transcript.toString())
                    ).build()).aiMessage();

            String summary = result.text();
            if (summary != null && !summary.isBlank()) {
                shortTermMemory.setSummary(sessionId, summary.strip());
                log.info("[MEMORY] 会话摘要已更新（{} 条旧消息被摘要化）", drained.size());
            }
        } catch (Exception e) {
            log.warn("会话摘要生成失败（不影响主流程）: {}", e.getMessage());
        }
    }

    private String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replace("\r", "").replace("\n", " ").strip();
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }

    /** 去掉工具再问一次，让模型基于已拿到的信息收口 */
    private String convergeAfterAbort(List<ChatMessage> messages) {
        List<ChatMessage> closing = new ArrayList<>(messages);
        closing.add(UserMessage.from(
                "（系统提示）已达到本次任务的执行步数上限。请**只根据上面已经获得的信息**给出最终回答。"
                        + "如果信息仍然不足，如实说明还缺什么、建议游客怎么做，不要再请求调用任何工具。"));
        try {
            AiMessage finalMessage = chatModel.chat(ChatRequest.builder().messages(closing).build()).aiMessage();
            String text = finalMessage.text();
            return (text == null || text.isBlank()) ? FALLBACK_ANSWER : text;
        } catch (Exception e) {
            log.error("强制收口时模型调用失败: {}", e.getMessage(), e);
            return FALLBACK_ANSWER;
        }
    }
}
