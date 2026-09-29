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

    public AgentLoop(ChatModel chatModel,
                     AgentToolbox toolbox,
                     ShortTermMemory shortTermMemory,
                     LongTermMemory longTermMemory,
                     SemanticCache semanticCache,
                     AgentMetrics metrics,
                     @Value("${agent.max-steps:6}") int maxSteps,
                     @Value("${agent.timeout-seconds:120}") long timeoutSeconds,
                     @Value("${agent.max-tools-per-step:4}") int maxToolsPerStep,
                     @Value("${agent.system-prompt:prompts/scenic-agent-system.txt}") String systemPromptPath)
            throws IOException {
        this.chatModel = chatModel;
        this.toolbox = toolbox;
        this.shortTermMemory = shortTermMemory;
        this.longTermMemory = longTermMemory;
        this.semanticCache = semanticCache;
        this.metrics = metrics;
        this.maxSteps = Math.max(maxSteps, 1);
        this.timeoutSeconds = timeoutSeconds;
        // Wave 1 · 单步工具上限：模型一次返回过多工具调用会放大延迟与成本
        this.maxToolsPerStep = Math.max(1, maxToolsPerStep);
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
        messages.add(SystemMessage.from(buildSystemMessage(systemPrompt, memoryKey, userMessage)));
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
            try {
                ChatResponse response = chatModel.chat(ChatRequest.builder()
                        .messages(messages)
                        .toolSpecifications(agentToolbox.specifications())
                        .build());
                aiMessage = response.aiMessage();
                // token 用量：P8 成本核算的基础
                if (response.tokenUsage() != null) {
                    metrics.tokens(response.tokenUsage().inputTokenCount(),
                            response.tokenUsage().outputTokenCount());
                }
            } catch (Exception e) {
                log.error("Agent 第 {} 步模型调用失败: {}", step, e.getMessage(), e);
                metrics.runAborted();
                metrics.degrade("model");
                return new Result(MODEL_ERROR_ANSWER, trace, step - 1, false, "MODEL_ERROR");
            }
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
                        metrics.toolFailed();
                        String rejected = "工具「" + request.name() + "」执行失败：单步工具调用数超过上限（"
                                + maxToolsPerStep + "），本次未执行。请在下一步再调用它。";
                        trace.add(new Step(step, request.name(), request.arguments(), rejected, true));
                        messages.add(ToolExecutionResultMessage.from(request, rejected));
                        continue;
                    }

                    metrics.toolCalled();
                    String observation = agentToolbox.execute(request, sessionId);
                    boolean failed = observation.contains("执行失败：");
                    if (failed) {
                        metrics.toolFailed();
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
     * 组装带记忆的系统提示词：
     * 基础人设与规则 + 长期偏好（跨会话）+ 短期摘要与本轮上下文（会话内）。
     */
    private String buildSystemMessage(String basePrompt, String memoryKey, String query) {
        StringBuilder sb = new StringBuilder(basePrompt);

        // 长期偏好（跨会话）
        List<String> preferences = longTermMemory.recall(memoryKey, query);
        metrics.memoryRecalled(preferences.size());
        if (!preferences.isEmpty()) {
            sb.append("\n\n【关于这位游客的长期偏好（来自历史对话，可主动利用）】\n");
            for (int i = 0; i < preferences.size(); i++) {
                sb.append(i + 1).append(". ").append(preferences.get(i)).append('\n');
            }
        }

        // 会话摘要 + 短期滑窗（会话内）
        String summary = shortTermMemory.summary(memoryKey);
        List<ShortTermMemory.Turn> recent = shortTermMemory.recent(memoryKey);
        if (summary != null && !summary.isBlank()) {
            sb.append("\n\n【本会话此前内容的摘要】\n").append(summary).append('\n');
        }
        if (!recent.isEmpty()) {
            sb.append("\n\n【本会话最近几轮对话】\n");
            for (ShortTermMemory.Turn turn : recent) {
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
        if (size > shortTermWindowSize()) {
            List<ShortTermMemory.Turn> drained = shortTermMemory.drain(sessionId, size - shortTermWindowSize());
            summarize(sessionId, drained);
        }
    }

    private long shortTermWindowSize() {
        // 滑窗按「消息条数」管理（1 轮 = 2 条）
        return 20;
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
