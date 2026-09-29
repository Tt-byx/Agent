package com.scenic.ai.controller;

import com.scenic.ai.dto.Result;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Wave 4 · 成本 / 延迟台账。
 *
 * 回答三类问题（单看 Grafana 曲线回答不了）：
 *   1. **这段时间花了多少钱、钱花在哪个模型上** —— token 数按 route 分账 × 各自单价；
 *   2. **语义缓存省了多少** —— 命中次数 × 单次平均成本，把「缓存的价值」换算成金额；
 *   3. **延迟分布与可靠性摘要** —— 各端点的 P50/P95/P99，以及熔断、降级、限流、工具失败计数。
 *
 * 口径说明（避免误读）：
 *   - token 与调用次数取自进程内 Micrometer 计数，**进程重启即归零**；长期趋势以 Prometheus 为准；
 *   - 单价按「元 / 千 token」配置（Flash 与 Pro 价格不同），仅作量级估算，不代表账单；
 *   - 缓存节省用的是「单次平均模型调用成本」，未命中时本来也要调模型，所以这是保守估计。
 *
 * 权限：由 SecurityConfig 统一要求 ROLE_ADMIN。
 */
@RestController
@RequestMapping("/api/ops")
@Slf4j
public class OpsController {

    private final MeterRegistry registry;
    private final double flashInputPrice;
    private final double flashOutputPrice;
    private final double proInputPrice;
    private final double proOutputPrice;

    public OpsController(MeterRegistry registry,
                         @Value("${ops.pricing.flash.input-per-1k:0.001}") double flashInputPrice,
                         @Value("${ops.pricing.flash.output-per-1k:0.002}") double flashOutputPrice,
                         @Value("${ops.pricing.pro.input-per-1k:0.004}") double proInputPrice,
                         @Value("${ops.pricing.pro.output-per-1k:0.012}") double proOutputPrice) {
        this.registry = registry;
        this.flashInputPrice = flashInputPrice;
        this.flashOutputPrice = flashOutputPrice;
        this.proInputPrice = proInputPrice;
        this.proOutputPrice = proOutputPrice;
    }

    /** 成本 / 延迟 / 可靠性总台账 */
    @GetMapping("/ledger")
    public Result<Map<String, Object>> ledger() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("note", "数据来自进程内 Micrometer 计数，重启归零；长期趋势请以 Prometheus 为准");
        report.put("cost", costLedger());
        report.put("latency", latencyLedger());
        report.put("reliability", reliabilitySummary());
        return Result.success(report);
    }

    // ───────────────────────── 成本 ─────────────────────────

    private Map<String, Object> costLedger() {
        // route -> [输入 token, 输出 token]
        Map<String, long[]> tokensByRoute = new LinkedHashMap<>();
        for (Counter counter : registry.find("scenic.llm.tokens.input").counters()) {
            tokensByRoute.computeIfAbsent(routeOf(counter.getId()), k -> new long[2])[0] =
                    (long) counter.count();
        }
        for (Counter counter : registry.find("scenic.llm.tokens.output").counters()) {
            tokensByRoute.computeIfAbsent(routeOf(counter.getId()), k -> new long[2])[1] =
                    (long) counter.count();
        }

        List<Map<String, Object>> byRoute = new ArrayList<>();
        double totalCost = 0;
        long totalInput = 0;
        long totalOutput = 0;
        for (Map.Entry<String, long[]> entry : tokensByRoute.entrySet()) {
            String route = entry.getKey();
            long input = entry.getValue()[0];
            long output = entry.getValue()[1];
            double[] price = priceOf(route);
            double cost = input / 1000.0 * price[0] + output / 1000.0 * price[1];
            totalCost += cost;
            totalInput += input;
            totalOutput += output;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("route", route);
            item.put("inputTokens", input);
            item.put("outputTokens", output);
            item.put("pricePer1kInput", price[0]);
            item.put("pricePer1kOutput", price[1]);
            item.put("costCNY", round(cost, 4));
            byRoute.add(item);
        }
        byRoute.sort(Comparator.comparingDouble(m -> -((Number) m.get("costCNY")).doubleValue()));

        // 缓存收益：命中次数 × 单次平均模型调用成本
        double hits = counter("scenic.cache.hits");
        double misses = counter("scenic.cache.misses");
        double modelCalls = counterWithTag("scenic.llm.route.calls", "result", "success");
        double avgCostPerCall = modelCalls > 0 ? totalCost / modelCalls : 0;
        double hitRate = (hits + misses) > 0 ? hits / (hits + misses) : 0;

        Map<String, Object> cache = new LinkedHashMap<>();
        cache.put("hits", (long) hits);
        cache.put("misses", (long) misses);
        cache.put("hitRate", round(hitRate, 4));
        cache.put("avgCostPerModelCallCNY", round(avgCostPerCall, 6));
        cache.put("estimatedSavingCNY", round(hits * avgCostPerCall, 4));
        cache.put("explain", "缓存命中跳过整条 Agent 链路（检索+工具+模型），节省额 = 命中次数 × 单次平均模型成本（保守估计）");

        Map<String, Object> cost = new LinkedHashMap<>();
        cost.put("currency", "CNY");
        cost.put("totalInputTokens", totalInput);
        cost.put("totalOutputTokens", totalOutput);
        cost.put("totalCost", round(totalCost, 4));
        cost.put("byRoute", byRoute);
        cost.put("semanticCache", cache);
        return cost;
    }

    // ───────────────────────── 延迟 ─────────────────────────

    private List<Map<String, Object>> latencyLedger() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Timer timer : registry.find("scenic.http.server.requests").timers()) {
            Meter.Id id = timer.getId();
            if (timer.count() == 0) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("method", id.getTag("method"));
            row.put("uri", id.getTag("uri"));
            row.put("status", id.getTag("status"));
            row.put("count", timer.count());
            row.put("avgMs", round(timer.mean(TimeUnit.MILLISECONDS), 1));
            row.put("p50Ms", round(timer.percentile(0.5, TimeUnit.MILLISECONDS), 1));
            row.put("p95Ms", round(timer.percentile(0.95, TimeUnit.MILLISECONDS), 1));
            row.put("p99Ms", round(timer.percentile(0.99, TimeUnit.MILLISECONDS), 1));
            row.put("maxMs", round(timer.max(TimeUnit.MILLISECONDS), 1));
            rows.add(row);
        }
        rows.sort(Comparator.comparingLong(m -> -((Number) m.get("count")).longValue()));
        return rows;
    }

    // ───────────────────────── 可靠性摘要 ─────────────────────────

    private Map<String, Object> reliabilitySummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("agentRuns", counter("scenic.agent.runs"));
        summary.put("agentConverged", counter("scenic.agent.runs.converged"));
        summary.put("agentAborted", counter("scenic.agent.runs.aborted"));
        summary.put("toolCalls", counter("scenic.agent.tool.calls"));
        summary.put("toolFailures", counter("scenic.agent.tool.failures"));

        // 路由与熔断（按 route + result 展开）
        Map<String, Long> routes = new LinkedHashMap<>();
        for (Counter counter : registry.find("scenic.llm.route.calls").counters()) {
            routes.put(counter.getId().getTag("route") + "/" + counter.getId().getTag("result"),
                    (long) counter.count());
        }
        summary.put("routeCalls", routes);

        // 降级分布
        Map<String, Long> degrades = new LinkedHashMap<>();
        for (Counter counter : registry.find("scenic.degrade").counters()) {
            degrades.put(counter.getId().getTag("type"), (long) counter.count());
        }
        summary.put("degradeByType", degrades);

        // 限流拒绝
        Map<String, Long> rejected = new LinkedHashMap<>();
        for (Counter counter : registry.find("scenic.rate_limit.rejected").counters()) {
            rejected.put(counter.getId().getTag("dimension"), (long) counter.count());
        }
        summary.put("rateLimitRejected", rejected);

        return summary;
    }

    // ───────────────────────── 工具方法 ─────────────────────────

    /** primary:deepseek-flash / fallback:deepseek-v4-pro → 对应单价（含 pro 的按 pro 计） */
    private double[] priceOf(String route) {
        String lower = route == null ? "" : route.toLowerCase();
        if (lower.contains("pro")) {
            return new double[]{proInputPrice, proOutputPrice};
        }
        return new double[]{flashInputPrice, flashOutputPrice};
    }

    private String routeOf(Meter.Id id) {
        String route = id.getTag("route");
        return route == null ? "unknown" : route;
    }

    private double counter(String name) {
        Counter counter = registry.find(name).counter();
        return counter == null ? 0 : counter.count();
    }

    private double counterWithTag(String name, String tagKey, String tagValue) {
        Counter counter = registry.find(name).tag(tagKey, tagValue).counter();
        return counter == null ? 0 : counter.count();
    }

    private double round(double value, int scale) {
        double factor = Math.pow(10, scale);
        return Math.round(value * factor) / factor;
    }
}
