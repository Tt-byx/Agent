package com.scenic.ai.agent.multi;

import com.scenic.ai.agent.core.AgentLoop;
import com.scenic.ai.gateway.AgentMetrics;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Supervisor 的「委派」工具（P9 建立，Wave 6 收尾）。
 *
 * 多 Agent 编排的做法有两种：
 *   a) Supervisor 用一次 LLM 调用输出 JSON 路由决策，代码里 if/else 分发；
 *   b) **把「委派」做成一个工具**，让 Supervisor 走和普通工具一样的 ReAct 循环。
 *
 * 选 (b) 的理由：路由、交接、多轮委派、失败回灌全部复用已有的循环机制，
 * 不需要为 Supervisor 单独写一套编排代码 —— 这正是「手写循环」灵活性的体现。
 *
 * 递归保护：专家的工具箱由 {@link SpecialistRegistry#toolboxFor} 裁剪，
 * **不包含委派工具**，所以专家不可能再往下委派（物理上防住无限递归）。
 *
 * Wave 6 补上的两件事：
 *   1. **委派次数上限**：每次委派都是一次完整的子循环（模型 + 工具），
 *      同一专家被反复委派会让成本与延迟线性上涨，这里显式拦住；
 *   2. **子 Agent 使用独立记忆键**：原先与主会话共用 memoryKey，
 *      专家的短期记忆会与 Supervisor 的对话混在一起（上下文串味）。
 */
@Component
@Slf4j
public class DelegationTool {

    /** 同一专家在窗口内最多被委派几次 */
    private static final int MAX_DELEGATIONS_PER_SPECIALIST = 2;

    /** 计数窗口：超过这个时长视为新一轮编排 */
    private static final long WINDOW_MILLIS = 60_000L;

    private final AgentLoop agentLoop;
    private final SpecialistRegistry registry;
    private final AgentMetrics metrics;

    /**
     * 委派计数按执行线程保存：Supervisor 的整轮编排跑在同一线程上。
     * 用「时间窗口」而不是「显式重置」，是为了不引入跨类耦合
     * （否则需要编排器在每轮开始时回调重置，而 ThreadLocal 又会被 Tomcat 线程复用）。
     */
    private final ThreadLocal<Map<String, long[]>> delegationWindow =
            ThreadLocal.withInitial(HashMap::new);

    public DelegationTool(AgentLoop agentLoop, SpecialistRegistry registry, AgentMetrics metrics) {
        this.agentLoop = agentLoop;
        this.registry = registry;
        this.metrics = metrics;
    }

    @Tool("把任务委派给合适的专家 Agent 处理，返回专家的处理结果。"
            + "可选专家：knowledge（景区知识顾问）、itinerary（行程规划师）、operations（运营实况分析师）。"
            + "当问题涉及多个领域时，可以分别委派给不同专家，最后由你汇总。")
    public String delegateToSpecialist(
            @ToolMemoryId String baseKey,
            @P("专家名称，可取：knowledge、itinerary、operations") String agentName,
            @P("交给这位专家的具体任务，要说清楚要它做什么") String task) {

        SpecialistRegistry.Specialist specialist = registry.find(agentName);
        if (specialist == null) {
            String msg = "错误：不存在名为「" + agentName + "」的专家。可选：" + registry.names();
            log.warn(msg);
            return msg;
        }

        // Wave 6 · 委派次数上限（按时间窗口计数）
        long now = System.currentTimeMillis();
        Map<String, long[]> windows = delegationWindow.get();
        long[] window = windows.get(agentName);
        if (window == null || now - window[1] > WINDOW_MILLIS) {
            window = new long[]{0L, now};
            windows.put(agentName, window);
        }
        if (window[0] >= MAX_DELEGATIONS_PER_SPECIALIST) {
            metrics.degrade("delegation_limit");
            String msg = "错误：本窗口内「" + agentName + "」已被委派 " + window[0]
                    + " 次，达到上限（" + MAX_DELEGATIONS_PER_SPECIALIST
                    + "）。请基于已获得的结果作答，或改派其他专家。";
            log.warn("[SUPERVISOR] {}", msg);
            return msg;
        }
        window[0]++;

        // Wave 6 · 子 Agent 用**独立记忆键**：任务描述已由 Supervisor 完整给出，
        // 专家不依赖共享的短期对话上下文，独立键反而避免了上下文串味。
        // 2026-10-29（D01）：baseKey 由 @ToolMemoryId 注入。原实现读 ThreadLocal，
        // 而工具跑在独立线程池里，读到的永远是空串 → 键退化成 "specialist:knowledge:"，
        // 所有游客共用一份专家记忆（跨用户偏好串号）。
        String specialistKey = "specialist:" + agentName + ":" + (baseKey == null ? "" : baseKey);

        log.info("[SUPERVISOR] 委派 -> {}（{}）第 {} 次：{}",
                specialist.name(), specialist.title(), window[0], task);

        AgentLoop.Result result = agentLoop.runWith(
                specialistKey, specialistKey, task,
                registry.toolboxFor(specialist),
                registry.promptFor(specialist));

        log.info("[SUPERVISOR] {} 返回（{} 步 / {} 次工具调用）",
                specialist.name(), result.stepCount(), result.toolCallCount());
        return "【来自" + specialist.title() + "的回答】\n" + result.answer();
    }
}
