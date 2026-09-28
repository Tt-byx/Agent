package com.scenic.ai.agent.multi;

import com.scenic.ai.agent.core.AgentLoop;
import com.scenic.ai.memory.MemoryContextHolder;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * P9 · Supervisor 的「委派」工具。
 *
 * 多 Agent 编排的做法有两种：
 *   a) Supervisor 用一次 LLM 调用输出 JSON 路由决策，代码里 if/else 分发；
 *   b) **把「委派」做成一个工具**，让 Supervisor 走和普通工具一样的 ReAct 循环。
 *
 * 选 (b) 的理由：路由、交接、多轮委派、失败回灌全部复用 P7 已有的循环机制，
 * 不需要为 Supervisor 单独写一套编排代码 —— 这正是"手写循环"灵活性的体现。
 *
 * 递归保护：专家的工具箱由 {@link SpecialistRegistry#toolboxFor} 裁剪，
 * **不包含委派工具**，所以专家不可能再往下委派（物理上防住无限递归）。
 */
@Component
@Slf4j
public class DelegationTool {

    private final AgentLoop agentLoop;
    private final SpecialistRegistry registry;

    public DelegationTool(AgentLoop agentLoop, SpecialistRegistry registry) {
        this.agentLoop = agentLoop;
        this.registry = registry;
    }

    @Tool("把任务委派给合适的专家 Agent 处理，返回专家的处理结果。"
            + "可选专家：knowledge（景区知识顾问）、itinerary（行程规划师）、operations（运营实况分析师）。"
            + "当问题涉及多个领域时，可以分别委派给不同专家，最后由你汇总。")
    public String delegateToSpecialist(
            @P("专家名称，可取：knowledge、itinerary、operations") String agentName,
            @P("交给这位专家的具体任务，要说清楚要它做什么") String task) {

        SpecialistRegistry.Specialist specialist = registry.find(agentName);
        if (specialist == null) {
            String msg = "错误：不存在名为「" + agentName + "」的专家。可选：" + registry.names();
            log.warn(msg);
            return msg;
        }

        String memoryKey = MemoryContextHolder.current();
        log.info("[SUPERVISOR] 委派 -> {}（{}）：{}", specialist.name(), specialist.title(), task);

        AgentLoop.Result result = agentLoop.runWith(
                memoryKey, memoryKey, task,
                registry.toolboxFor(specialist),
                registry.promptFor(specialist));

        log.info("[SUPERVISOR] {} 返回（{} 步 / {} 次工具调用）",
                specialist.name(), result.stepCount(), result.toolCallCount());
        return "【来自" + specialist.title() + "的回答】\n" + result.answer();
    }
}
