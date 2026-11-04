package com.scenic.ai.gateway;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Agent 可观测性指标（Micrometer / Prometheus）。
 *
 * 讲这块的价值不是「我加了监控」，而是**每个指标回答什么问题**：
 *   - cache_hits / cache_misses      → 语义缓存省了多少调用、命中率多少
 *   - agent.tool.calls{failure}      → 工具稳定性（Wave 3 起带**工具名**标签，能定位到具体工具）
 *   - agent.runs(收敛/收口/超时)      → Agent 自主性是否失控
 *   - llm.route.calls{route,result}  → 主备模型健康度；`result=skipped` 是熔断是否真生效的抓手
 *   - llm.tokens{route}              → Wave 3 起按模型分账，成本可归因
 *   - http.server.requests           → Wave 3 新增，请求级 P50/P95/P99
 *   - agent.step.duration            → Wave 3 新增，延迟出在第几步、是模型还是工具
 *   - degrade{type}                  → 静默降级发生了几次
 *   - rate_limit.rejected            → Wave 3 新增，被限流挡掉的请求量
 */
@Component
public class AgentMetrics {

    private final Counter agentRuns;
    private final Counter agentRunsConverged;
    private final Counter agentRunsAborted;
    private final Counter cacheHits;
    private final Counter cacheMisses;
    private final Counter memoryWrites;
    private final Counter memoryRecalls;
    private final MeterRegistry registry;

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
        agentRuns = Counter.builder("scenic.agent.runs")
                .description("Agent 循环总次数").register(registry);
        agentRunsConverged = Counter.builder("scenic.agent.runs.converged")
                .description("正常收敛的次数").register(registry);
        agentRunsAborted = Counter.builder("scenic.agent.runs.aborted")
                .description("因步数上限/超时被强制收口的次数").register(registry);
        cacheHits = Counter.builder("scenic.cache.hits")
                .description("语义缓存命中次数").register(registry);
        cacheMisses = Counter.builder("scenic.cache.misses")
                .description("语义缓存未命中次数").register(registry);
        memoryWrites = Counter.builder("scenic.memory.writes")
                .description("长期记忆写入次数").register(registry);
        memoryRecalls = Counter.builder("scenic.memory.recalls")
                .description("长期记忆召回次数（有召回到内容）").register(registry);
    }

    // ───────────────────────── 模型路由 ─────────────────────────

    /** 模型路由结果（带 route 标签，用于对比主/备模型的健康状况与延迟） */
    public void routeSucceeded(String route, long durationMillis) {
        registry.counter("scenic.llm.route.calls", "route", route, "result", "success").increment();
        registry.timer("scenic.llm.route.duration", "route", route)
                .record(durationMillis, TimeUnit.MILLISECONDS);
    }

    public void routeFailed(String route) {
        registry.counter("scenic.llm.route.calls", "route", route, "result", "failure").increment();
    }

    /**
     * Wave 1 · 熔断跳过：该 route 处于 OPEN 或半开限流状态，本次请求**根本没有调用它**。
     * 这个指标是判断「熔断是否真的生效」的抓手 —— 若它恒为 0，说明熔断形同虚设。
     */
    public void routeSkipped(String route) {
        registry.counter("scenic.llm.route.calls", "route", route, "result", "skipped").increment();
    }

    // ───────────────────────── token 与成本 ─────────────────────────

    /**
     * Wave 3 · token 用量（带 route 标签）。
     * 由 {@link RoutedChatModel} 打点 —— 只有它知道这次回答实际由哪个候选产出，
     * 因此成本可以按模型归因（flash 与 pro 的单价不同）。
     */
    public void tokens(Integer input, Integer output, String route) {
        if (input != null) {
            registry.counter("scenic.llm.tokens.input", "route", route).increment(input);
        }
        if (output != null) {
            registry.counter("scenic.llm.tokens.output", "route", route).increment(output);
        }
    }

    /**
     * 2026-11-04 · LLM 调用成本（元）。
     *
     * <p>只在 Prometheus 里采 token 数是不够的 —— 「花了多少钱」是运营最关心的指标。
     * 这里把按 route 归因的单次调用成本也采下来，Grafana 可以直接 sum 出总成本。
     * 单价来自配置（不同模型不同价），计算逻辑在调用方。
     */
    public void cost(String route, int inputTokens, int outputTokens) {
        double inPrice = pricePer1kInput(route);
        double outPrice = pricePer1kOutput(route);
        double cny = inputTokens / 1000.0 * inPrice + outputTokens / 1000.0 * outPrice;
        if (cny > 0) {
            registry.counter("scenic.llm.cost", "route", route).increment(cny);
        }
    }

    /** 按 route 猜单价（元/千 token）。flash 便宜、pro 贵；未知 route 按 pro 算（保守） */
    private double pricePer1kInput(String route) {
        return route != null && route.contains("flash") ? 0.001 : 0.004;
    }

    private double pricePer1kOutput(String route) {
        return route != null && route.contains("flash") ? 0.002 : 0.012;
    }

    /**
     * 2026-11-04 · 语义缓存节省的成本（元）。
     *
     * <p>缓存命中时跳过整条 Agent 链路（检索+工具+模型），节省额按
     * 「命中次数 × 单次平均模型成本」保守估算。这个指标是证明缓存价值的直接抓手。
     */
    public void cacheSaving(double cny) {
        if (cny > 0) {
            registry.counter("scenic.cache.saving", "unit", "cny").increment(cny);
        }
    }

    /**
     * 2026-10-29（D33）· 流式路径的**估算** token（厂商不返回 usage 时的兜底）。
     *
     * <p>刻意用**独立的指标名**而不是给 {@code scenic.llm.tokens.*} 加标签：
     * 同一个指标名混两种标签集，Prometheus 查询会变得很难写（要处处 {@code sum without(estimated)}），
     * 而且容易被误读成「真实用量」。分开之后，「缺口有多大」一眼可见 —— 这比数字好看重要。
     */
    public void tokensEstimated(Integer input, Integer output, String route) {
        if (input != null) {
            registry.counter("scenic.llm.tokens.estimated.input", "route", route).increment(input);
        }
        if (output != null) {
            registry.counter("scenic.llm.tokens.estimated.output", "route", route).increment(output);
        }
    }

    // ───────────────────────── 工具 ─────────────────────────

    /** Wave 3 · 工具调用（带工具名标签，可定位到具体是哪个工具不健康） */
    public void toolCalled(String toolName) {
        registry.counter("scenic.agent.tool.calls", "tool", safeTag(toolName)).increment();
    }

    public void toolFailed(String toolName) {
        registry.counter("scenic.agent.tool.failures", "tool", safeTag(toolName)).increment();
    }

    // ───────────────────────── HTTP / 请求级 ─────────────────────────

    /**
     * Wave 3 · HTTP 请求延迟直方图。
     * uri 使用**路由模板**（如 {@code /api/conversations/{id}}）而非原始路径，
     * 否则每条带 ID 的 URL 都会生成一个时间序列，标签基数会爆炸。
     */
    public void httpRequest(String method, String uri, int status, long durationMillis) {
        Timer.builder("scenic.http.server.requests")
                .description("HTTP 请求延迟（按端点与状态码，用于 P50/P95/P99）")
                .tag("method", method)
                .tag("uri", uri)
                .tag("status", String.valueOf(status))
                // histogram 供 Prometheus 侧算任意分位；
                // publishPercentiles 让 Java 侧（成本/延迟台账接口）能直接读 P50/P95/P99
                .publishPercentileHistogram()
                .publishPercentiles(0.5, 0.95, 0.99)
                .minimumExpectedValue(Duration.ofMillis(10))
                .maximumExpectedValue(Duration.ofSeconds(30))
                .register(registry)
                .record(durationMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Wave 3 · Agent 单步耗时。
     * phase = model（模型调用）/ tool（工具执行）；step = 第几步（1..maxSteps，基数很小）。
     * 回答的问题：整轮延迟到底耗在哪一步、是模型慢还是工具慢。
     */
    public void agentStep(long step, String phase, long durationMillis) {
        Timer.builder("scenic.agent.step.duration")
                .description("Agent 单步耗时（按阶段与步序）")
                .tag("phase", phase)
                .tag("step", String.valueOf(step))
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(10))
                .maximumExpectedValue(Duration.ofSeconds(60))
                .register(registry)
                .record(durationMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Wave 6 · 语义路由决策：本次请求被判为「简单（走快模型）」还是「复杂（优先强模型）」。
     * 用于验证按复杂度分流是否真的生效、成本结构是否随之变化。
     */
    public void routeDecision(boolean complex) {
        registry.counter("scenic.route.decision", "tier", complex ? "strong" : "fast").increment();
    }

    /** Wave 3 · 限流拒绝量（dimension = visitor / ip） */
    public void rateLimitRejected(String dimension) {
        registry.counter("scenic.rate_limit.rejected", "dimension", safeTag(dimension)).increment();
    }

    // ───────────────────────── 降级与记忆 ─────────────────────────

    /**
     * Wave 1 · 降级打点：记录「优雅降级」发生的次数，让静默兜底变得可观测。
     * type：cache / memory / retrieval / rerank / tool / model / model_all_failed
     *       / ingest_write / ingest_cleanup
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

    /** 标签值不能为 null，否则 Micrometer 会抛异常 */
    private String safeTag(String value) {
        return (value == null || value.isBlank()) ? "unknown" : value;
    }
}
