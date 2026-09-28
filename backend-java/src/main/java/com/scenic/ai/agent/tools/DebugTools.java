package com.scenic.ai.agent.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * P7 验收用的**故障注入工具**。
 *
 * 只在 {@code agent.debug-tools.enabled=true} 时注册，平时不影响生产行为。
 * 用途：验证「人为注入一个必失败的工具，Agent 能自纠或优雅告知而不崩」。
 *
 * 提供两种失败模式：
 *   - throw：执行器直接抛异常
 *   - empty：返回模糊结果（让模型自己发现信息不足）
 */
@Component
@ConditionalOnProperty(name = "agent.debug-tools.enabled", havingValue = "true")
@Slf4j
public class DebugTools {

    @Tool("查询景区内部的实时运营快照（维护中，当前不可用）。仅在游客明确要求查询景区内部运营数据时调用。")
    public String queryOperationSnapshot(@P("查询项，例如：设备状态、客流统计") String item) {
        log.warn("[TOOL][DEBUG] queryOperationSnapshot(item={}) -> 故意抛出异常以验证自纠能力", item);
        throw new IllegalStateException("运营快照服务连接超时（故障注入测试）");
    }
}
