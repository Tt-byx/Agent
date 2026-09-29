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
    private final Counter inputTokens;
    private final Counter outputTokens;
    private final MeterRegistry registry;

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
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
        inputTokens = Counter.builder("scenic.llm.tokens.input")
                .description("LLM 输入 token 总量").register(registry);
        outputTokens = Counter.builder("scenic.llm.tokens.output")
                .description("LLM 输出 token 总量").register(registry);
    }

    /** 模型路由结果（带 route 标签，用于对比主/备模型的健康状况与延迟） */
    public void routeSucceeded(String route, long durationMillis) {
        registry.counter("scenic.llm.route.calls", "route", route, "result", "success").increment();
        registry.timer("scenic.llm.route.duration", "route", route)
                .record(durationMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void routeFailed(String route) {
        registry.counter("scenic.llm.route.calls", "route", route, "result", "failure").increment();
    }

    /** token 用量：成本核算的基础（P8 验收 ④） */
    public void tokens(Integer input, Integer output) {
        if (input != null) {
            inputTokens.increment(input);
        }
        if (output != null) {
            outputTokens.increment(output);
        }
    }

    /**
     * Wave 1 · 熔断跳过：该 route 处于 OPEN 或半开限流状态，本次请求**根本没有调用它**。
     * 这个指标是判断「熔断是否真的生效」的抓手 —— 若它恒为 0，说明熔断形同虚设。
     */
    public void routeSkipped(String route) {
        registry.counter("scenic.llm.route.calls", "route", route, "result", "skipped").increment();
    }

    /**
     * Wave 1 · 降级打点：记录「优雅降级」发生的次数，让静默兜底变得可观测。
     * type 取值：cache / memory / retrieval / rerank / tool / model / model_all_failed
     */
    public void degrade(String type) {
        registry.counter("scenic.degrade", "type", type).increment();
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
