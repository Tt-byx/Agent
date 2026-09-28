package com.scenic.ai.gateway;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * P8 · Agent 可观测性指标（Micrometer / Prometheus）。
 *
 * 面试讲这块的价值：不是「我加了监控」，而是**这些指标分别回答什么问题**——
 *   - cache_hits / cache_misses     → 语义缓存到底省了多少钱、命中率多少
 *   - agent_tool_calls / failures   → 工具稳定性，以及失败是否都被回灌消化
 *   - agent_runs(收敛/步数上限/超时) → Agent 自主性是否失控
 *   - agent_step_duration           → 延迟分布，P99 出在哪一步
 */
@Component
public class AgentMetrics {

    private final Counter agentRuns;
    private final Counter agentRunsConverged;
    private final Counter agentRunsAborted;
    private final Counter toolCalls;
    private final Counter toolFailures;
    private final Counter cacheHits;
    private final Counter cacheMisses;
    private final Counter memoryWrites;
    private final Counter memoryRecalls;

    public AgentMetrics(MeterRegistry registry) {
        agentRuns = Counter.builder("scenic.agent.runs")
                .description("Agent 循环总次数").register(registry);
        agentRunsConverged = Counter.builder("scenic.agent.runs.converged")
                .description("正常收敛的次数").register(registry);
        agentRunsAborted = Counter.builder("scenic.agent.runs.aborted")
                .description("因步数上限/超时被强制收口的次数").register(registry);
        toolCalls = Counter.builder("scenic.agent.tool.calls")
                .description("工具调用总次数").register(registry);
        toolFailures = Counter.builder("scenic.agent.tool.failures")
                .description("工具执行失败（已被回灌给模型）次数").register(registry);
        cacheHits = Counter.builder("scenic.cache.hits")
                .description("语义缓存命中次数").register(registry);
        cacheMisses = Counter.builder("scenic.cache.misses")
                .description("语义缓存未命中次数").register(registry);
        memoryWrites = Counter.builder("scenic.memory.writes")
                .description("长期记忆写入次数").register(registry);
        memoryRecalls = Counter.builder("scenic.memory.recalls")
                .description("长期记忆召回次数（有召回到内容）").register(registry);
    }

    public void runStarted() {
        agentRuns.increment();
    }

    public void runConverged() {
        agentRunsConverged.increment();
    }

    public void runAborted() {
        agentRunsAborted.increment();
    }

    public void toolCalled() {
        toolCalls.increment();
    }

    public void toolFailed() {
        toolFailures.increment();
    }

    public void cacheHit() {
        cacheHits.increment();
    }

    public void cacheMiss() {
        cacheMisses.increment();
    }

    public void memoryWritten() {
        memoryWrites.increment();
    }

    public void memoryRecalled(int count) {
        if (count > 0) {
            memoryRecalls.increment();
        }
    }
}
