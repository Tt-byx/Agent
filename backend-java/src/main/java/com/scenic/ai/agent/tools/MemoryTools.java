package com.scenic.ai.agent.tools;

import com.scenic.ai.gateway.AgentMetrics;
import com.scenic.ai.memory.LongTermMemory;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * P8 · 长期记忆工具。
 *
 * 让 Agent 自己决定「这句话值得记住」—— 比事后用 LLM 抽取偏好省一次调用，
 * 也和 P7 的架构一致（记忆能力也是 Agent 的一个工具）。
 *
 * <p><b>归属键怎么进来（2026-10-29 修 D01）</b>：
 * 由 {@link ToolMemoryId} 让 LangChain4j 在调用工具时注入 {@code memoryId}，
 * 而 {@code memoryId} 来自 {@code AgentToolbox.execute(request, memoryKey)} 的第二个参数
 * （由 {@code AgentLoop} 按轮传入：登录用户 = {@code user:{id}}，游客 = 服务端签发的 visitorId）。
 *
 * <p>为什么不用 ThreadLocal：工具执行跑在 {@code AgentToolbox} 的线程池里，
 * 普通 ThreadLocal 不跨线程传递，读到的永远是空字符串 —— 这正是 D01 的成因
 * （ThreadLocal 版本在低并发下必然失败、只在池被占满触发 CallerRuns 时"碰巧"成功）。
 *
 * <p>注意：{@code @ToolMemoryId} 标注的参数**不会进入工具 schema**，
 * 模型看不到它、也不需要填 —— 这是它优于「给 @Tool 方法多加一个普通参数」的关键。
 */
@Component
@Slf4j
public class MemoryTools {

    private final LongTermMemory longTermMemory;
    private final AgentMetrics metrics;

    public MemoryTools(LongTermMemory longTermMemory, AgentMetrics metrics) {
        this.longTermMemory = longTermMemory;
        this.metrics = metrics;
    }

    @Tool("记住游客的个人偏好或重要信息（例如：对历史文化感兴趣、带小孩出行、不想走太多路、"
            + "明天再来、喜欢拍照）。当游客在对话中表达了自己的兴趣、偏好、同行情况或约束条件时调用。"
            + "闲聊和问询类内容不要调用。")
    public String rememberVisitorPreference(
            @ToolMemoryId String memoryKey,
            @P("要记住的偏好内容，用一句简短的陈述句，例如：对历史文化特别感兴趣") String fact) {
        if (memoryKey == null || memoryKey.isBlank()) {
            String skip = "当前会话无法识别游客身份，偏好未保存。";
            log.info("[MEMORY][TOOL] {}", skip);
            return skip;
        }
        longTermMemory.remember(memoryKey, fact);
        // D27：原实现只在 AgentMetrics 里定义了计数器与 memoryWritten()，全项目零调用方 ——
        // 面板上 scenic.memory.writes 恒为 0，看起来像「模型从不调记忆工具」，
        // 实际是指标没接上。接上之后，D01 那类「写入静默失败」才能被曲线抓住：
        // 特征是「召回次数在涨、写入次数不涨」。
        metrics.memoryWritten();
        String ok = "已记住：「" + fact + "」。之后为这位游客做推荐时我会主动考虑这一点。";
        log.info("[MEMORY][TOOL] rememberVisitorPreference(key={}, fact={}) -> {}", memoryKey, fact, ok);
        return ok;
    }
}
