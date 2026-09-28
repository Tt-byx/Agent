package com.scenic.ai.agent.core;

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
 * 为什么不用 AiServices 自带的内层循环：
 *   1. **可控性** —— 最大步数、超时、终止条件都必须由我们决定，框架内层循环不暴露这些旋钮；
 *   2. **可观测** —— 每一步的工具选择、参数、观察结果都记录下来，既能在日志里看到，也是 P8 埋点的基础；
 *   3. **可靠性** —— 工具异常回灌、模型异常降级、达到步数上限后的强制收口，都需要在循环层处理。
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
    private final String systemPrompt;
    private final int maxSteps;
    private final long timeoutSeconds;

    public AgentLoop(ChatModel chatModel,
                     AgentToolbox toolbox,
                     @Value("${agent.max-steps:6}") int maxSteps,
                     @Value("${agent.timeout-seconds:120}") long timeoutSeconds,
                     @Value("${agent.system-prompt:prompts/scenic-agent-system.txt}") String systemPromptPath)
            throws IOException {
        this.chatModel = chatModel;
        this.toolbox = toolbox;
        this.maxSteps = Math.max(maxSteps, 1);
        this.timeoutSeconds = timeoutSeconds;
        this.systemPrompt = new ClassPathResource(systemPromptPath)
                .getContentAsString(StandardCharsets.UTF_8);
        log.info("AgentLoop 就绪：最大步数 {}，整体超时 {}s，工具 {} 个",
                this.maxSteps, this.timeoutSeconds, toolbox.size());
    }

    public Result run(String sessionId, String userMessage) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.add(UserMessage.from(userMessage));

        List<Step> trace = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;

        for (int step = 1; step <= maxSteps; step++) {
            if (System.currentTimeMillis() > deadline) {
                log.warn("Agent 整体超时（{}s），已执行 {} 步，降级返回", timeoutSeconds, step - 1);
                return new Result(convergeAfterAbort(messages), trace, step - 1, false, "TIMEOUT");
            }

            AiMessage aiMessage;
            try {
                ChatResponse response = chatModel.chat(ChatRequest.builder()
                        .messages(messages)
                        .toolSpecifications(toolbox.specifications())
                        .build());
                aiMessage = response.aiMessage();
            } catch (Exception e) {
                log.error("Agent 第 {} 步模型调用失败: {}", step, e.getMessage(), e);
                return new Result(MODEL_ERROR_ANSWER, trace, step - 1, false, "MODEL_ERROR");
            }
            messages.add(aiMessage);

            // 终止条件：模型不再请求工具，说明它认为可以作答了
            if (!aiMessage.hasToolExecutionRequests()) {
                String answer = aiMessage.text();
                if (answer == null || answer.isBlank()) {
                    answer = FALLBACK_ANSWER;
                }
                log.info("Agent 收敛：共 {} 步、{} 次工具调用", step, trace.size());
                return new Result(answer, trace, step, true, "CONVERGED");
            }

            // act → observe
            for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                String observation = toolbox.execute(request, sessionId);
                boolean failed = observation.contains("执行失败：");
                trace.add(new Step(step, request.name(), request.arguments(), observation, failed));
                messages.add(ToolExecutionResultMessage.from(request, observation));
            }
        }

        log.warn("Agent 达到最大步数 {}，触发强制收口", maxSteps);
        return new Result(convergeAfterAbort(messages), trace, maxSteps, false, "MAX_STEPS");
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
