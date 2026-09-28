package com.scenic.ai.agent.tools;

import com.scenic.ai.memory.LongTermMemory;
import com.scenic.ai.memory.MemoryContextHolder;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * P8 · 长期记忆工具。
 *
 * 让 Agent 自己决定「这句话值得记住」—— 比事后用 LLM 抽取偏好省一次调用，
 * 也和 P7 的架构一致（记忆能力也是 Agent 的一个工具）。
 *
 * 归属键来自 {@link MemoryContextHolder}（由 AgentLoop 在每轮设置）：
 * 登录用户是 userId，匿名游客是 sessionId。
 */
@Component
@Slf4j
public class MemoryTools {

    private final LongTermMemory longTermMemory;

    public MemoryTools(LongTermMemory longTermMemory) {
        this.longTermMemory = longTermMemory;
    }

    @Tool("记住游客的个人偏好或重要信息（例如：对历史文化感兴趣、带小孩出行、不想走太多路、"
            + "明天再来、喜欢拍照）。当游客在对话中表达了自己的兴趣、偏好、同行情况或约束条件时调用。"
            + "闲聊和问询类内容不要调用。")
    public String rememberVisitorPreference(
            @P("要记住的偏好内容，用一句简短的陈述句，例如：对历史文化特别感兴趣") String fact) {
        String memoryKey = MemoryContextHolder.current();
        if (memoryKey == null || memoryKey.isBlank()) {
            String skip = "当前会话无法识别游客身份，偏好未保存。";
            log.info("[MEMORY][TOOL] {}", skip);
            return skip;
        }
        longTermMemory.remember(memoryKey, fact);
        String ok = "已记住：「" + fact + "」。之后为这位游客做推荐时我会主动考虑这一点。";
        log.info("[MEMORY][TOOL] rememberVisitorPreference(fact={}) -> {}", fact, ok);
        return ok;
    }
}
