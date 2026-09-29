package com.scenic.ai.agent.multi;

import com.scenic.ai.agent.core.AgentLoop;
import com.scenic.ai.agent.core.AgentToolbox;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * P9 · Supervisor 编排器。
 *
 * 编排方式：**把「委派」做成一个工具**（见 {@link DelegationTool}），
 * Supervisor 本身就是一个普通的 ReAct 循环 —— 只是它的工具箱里只有一个委派工具。
 * 于是路由、多轮交接、失败回灌、步数上限全部复用 P7 的机制，零额外编排代码。
 *
 * 与单 Agent 的关系：通过 `agent.multi.enabled` 切换，默认关闭。
 * 单 Agent 一次模型调用就能答的问题，没必要为它多付一层 Supervisor 的开销。
 */
@Service
@Slf4j
public class MultiAgentOrchestrator {

    private final AgentLoop agentLoop;
    private final boolean enabled;
    private final AgentToolbox supervisorToolbox;
    private final String supervisorPrompt;

    public MultiAgentOrchestrator(AgentLoop agentLoop,
                                  DelegationTool delegationTool,
                                  SpecialistRegistry registry,
                                  AgentToolbox mainToolbox,
                                  @Value("${agent.multi.enabled:false}") boolean enabled,
                                  @Value("${agent.system-prompt:prompts/scenic-agent-system.txt}") String promptPath)
            throws IOException {
        this.agentLoop = agentLoop;
        this.enabled = enabled;

        // Supervisor 的工具箱里**只有委派工具** —— 它自己不碰业务工具。
        // Wave 1 起复用主工具箱的执行资源（线程池/重试策略/超时配置），不再单独 new 一套。
        this.supervisorToolbox = mainToolbox.withTools(List.of(delegationTool));

        StringBuilder sb = new StringBuilder(
                new ClassPathResource(promptPath).getContentAsString(StandardCharsets.UTF_8));
        sb.append("\n\n【你现在是调度者（Supervisor）】\n");
        sb.append("你不直接回答具体问题，而是把任务委派给专家 Agent，拿到结果后再汇总成一段话回答游客。\n\n");
        sb.append("可选专家：\n");
        for (SpecialistRegistry.Specialist specialist : registry.all()) {
            sb.append("- ").append(specialist.name()).append("（").append(specialist.title()).append("）：")
                    .append(specialist.description()).append('\n');
        }
        sb.append("\n【调度规则】\n");
        sb.append("1. 单一领域的问题，委派给对应专家即可。\n");
        sb.append("2. 跨领域的问题（例如「今天天气怎么样、适不适合户外、再给条路线」），分别委派给不同专家，再汇总。\n");
        sb.append("3. 寒暄或不需要任何信息的问题，可以直接回答，不必委派。\n");
        sb.append("4. 委派时务必把任务描述清楚 —— 专家看不到你们之间的对话。\n");
        sb.append("5. 最终回答用你自己的话连贯汇总，不要出现「某专家说」这类字眼。\n");
        this.supervisorPrompt = sb.toString();

        log.info("多 Agent 编排器就绪：enabled={}，Supervisor 工具 {} 个，专家 {} 个",
                enabled, supervisorToolbox.size(), registry.all().size());
    }

    public boolean isEnabled() {
        return enabled;
    }

    public AgentLoop.Result orchestrate(String sessionId, String memoryKey, String userMessage) {
        return agentLoop.runWith(sessionId, memoryKey, userMessage, supervisorToolbox, supervisorPrompt);
    }
}
