package com.scenic.ai.gateway;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * P8 建立 → Wave 1 加固 · 模型路由（DeepSeek 同厂商双档：flash 主 / v4-pro 备）。
 *
 * 实现 LangChain4j 的 {@link ChatModel}，对上层（AgentLoop）完全透明。
 *
 * Wave 1 补上的两块（对应路线图的「熔断」与「分级退避重试」）：
 *
 *   1. **熔断**（{@link CircuitBreaker}，失败率阈值 + 半开探测）。
 *      原实现是纯顺序尝试：主模型持续故障时，每个请求都要先打一次主模型、失败后再切备，
 *      再叠加模型层 2 次内置重试 —— 主模型事实上被打成 3 倍流量，故障被放大。
 *      现在熔断打开后**直接跳过该候选**，请求立刻走备模型。
 *      关键点：熔断状态必须参与路由决策，否则「熔断形同虚设」。
 *
 *   2. **分级重试**（{@link RetryPolicy}）：只重试 429 / 5xx / 超时这类重试有意义的错误，
 *      鉴权与参数错误立即切换候选，不浪费配额和延迟；退避带全抖动。
 *      重试发生在**同一候选内部**：先重试主模型，确实不行才换备模型。
 */
@Slf4j
public class RoutedChatModel implements ChatModel {

    /** 每个候选：名称 + 模型客户端 + 独立熔断器（主备各自统计，互不干扰） */
    public record Route(String name, ChatModel model, CircuitBreaker circuitBreaker) {}

    private final List<Route> routes;
    private final AgentMetrics metrics;
    private final RetryPolicy retryPolicy;

    public RoutedChatModel(List<Route> routes, AgentMetrics metrics, RetryPolicy retryPolicy) {
        if (routes == null || routes.isEmpty()) {
            throw new IllegalArgumentException("至少要配置一条模型路由");
        }
        this.routes = List.copyOf(routes);
        this.metrics = metrics;
        this.retryPolicy = retryPolicy;
        log.info("[ROUTE] 模型路由就绪：{} 条（{}），单候选最多 {} 次尝试（分级退避）",
                routes.size(),
                routes.stream().map(Route::name).toList(),
                retryPolicy.maxAttempts());
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        Exception lastFailure = null;
        boolean anyCandidateAttempted = false;

        // Wave 6 · 语义路由：按请求复杂度决定候选顺序。
        // 简单问题让快模型先答（省钱、低延迟）；复杂问题把强模型提到前面（保质量）。
        // 判定放在路由层，AgentLoop 无需感知 —— 它只知道"我调用了一个 ChatModel"。
        for (Route route : orderByComplexity(request)) {
            // 熔断打开（或半开状态下未取得许可）→ 直接跳过，不再白打一次
            if (!route.circuitBreaker().tryAcquirePermission()) {
                log.warn("[ROUTE] {} 处于熔断状态，本次请求直接跳过", route.name());
                metrics.routeSkipped(route.name());
                continue;
            }
            anyCandidateAttempted = true;

            for (int attempt = 1; attempt <= retryPolicy.maxAttempts(); attempt++) {
                long start = System.currentTimeMillis();
                try {
                    ChatResponse response = route.model().chat(request);
                    long costMillis = System.currentTimeMillis() - start;
                    route.circuitBreaker().onSuccess(costMillis, TimeUnit.MILLISECONDS);
                    metrics.routeSucceeded(route.name(), costMillis);
                    // Wave 3 · token 计数放在这里而不是 AgentLoop：只有路由层知道
                    // 这次回答实际由哪个候选产出，成本才能按模型（flash / pro）归因
                    if (response.tokenUsage() != null) {
                        metrics.tokens(response.tokenUsage().inputTokenCount(),
                                response.tokenUsage().outputTokenCount(), route.name());
                    }
                    if (attempt > 1) {
                        log.info("[ROUTE] {} 第 {} 次尝试成功（此前失败 {} 次）",
                                route.name(), attempt, attempt - 1);
                    }
                    return response;
                } catch (Exception e) {
                    long costMillis = System.currentTimeMillis() - start;
                    boolean retriable = retryPolicy.isRetriable(e);
                    boolean hasNextAttempt = attempt < retryPolicy.maxAttempts();

                    if (retriable && hasNextAttempt) {
                        log.warn("[ROUTE] {} 第 {}/{} 次调用失败（可重试）：{}",
                                route.name(), attempt, retryPolicy.maxAttempts(), e.getMessage());
                        if (!retryPolicy.backoff(attempt)) {
                            // 线程被中断：不再重试，记失败并尝试下一个候选
                            route.circuitBreaker().onError(costMillis, TimeUnit.MILLISECONDS, e);
                            metrics.routeFailed(route.name());
                            lastFailure = e;
                            break;
                        }
                        continue;
                    }

                    route.circuitBreaker().onError(costMillis, TimeUnit.MILLISECONDS, e);
                    metrics.routeFailed(route.name());
                    if (!retriable) {
                        log.warn("[ROUTE] {} 失败且不可重试（{}），立即切换候选：{}",
                                route.name(), e.getClass().getSimpleName(), e.getMessage());
                    } else {
                        log.warn("[ROUTE] {} 已重试 {} 次仍失败，切换候选", route.name(), attempt);
                    }
                    lastFailure = e;
                    break;
                }
            }
        }

        // 走到这里说明没有任何候选成功
        metrics.degrade("model_all_failed");
        if (!anyCandidateAttempted) {
            throw new IllegalStateException("所有模型候选均处于熔断状态，暂时无法提供模型服务");
        }
        throw (lastFailure instanceof RuntimeException runtimeException)
                ? runtimeException
                : new IllegalStateException("所有模型候选均调用失败", lastFailure);
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        return chat(request);
    }

    // ───────────────────────── Wave 6 · 语义路由 ─────────────────────────

    /**
     * 复杂意图标记：出现这类词说明需要综合多步信息（对比、规划、分析），
     * 值得优先用更强的模型。
     */
    private static final List<String> COMPLEX_MARKERS = List.of(
            "分析", "对比", "规划", "安排", "为什么", "总结", "推荐路线",
            "怎么选", "区别", "分别", "综合", "方案", "预算", "几天", "行程");

    /**
     * 按复杂度排序候选：复杂请求把强模型提到最前，其余保持配置顺序（快模型优先）。
     *
     * 强模型的识别用**命名约定**（候选名含 "pro"）—— 这样不必改动 Route 结构，
     * 代价是依赖命名；而项目里主备名是稳定的（primary:deepseek-flash / fallback:deepseek-v4-pro）。
     */
    private List<Route> orderByComplexity(ChatRequest request) {
        if (routes.size() < 2) {
            return routes;
        }
        boolean complex = isComplex(request);
        metrics.routeDecision(complex);
        if (!complex) {
            return routes;
        }
        List<Route> ordered = new ArrayList<>(routes);
        ordered.sort((a, b) -> Boolean.compare(isStrong(b), isStrong(a)));
        log.info("[ROUTE] 请求判为复杂，候选顺序调整为：{}",
                ordered.stream().map(Route::name).toList());
        return ordered;
    }

    /**
     * 复杂度启发式（零额外成本，不引入分类模型）：
     *   - 上下文很长（多轮对话累积）→ 复杂
     *   - 最新一句用户输入含「分析/对比/规划/安排」等需要综合的意图 → 复杂
     *
     * 取舍说明：真正的语义分类器需要**多一次模型调用**才能判断该用哪个模型，
     * 那次调用的成本与延迟往往超过分流带来的收益 —— 启发式在这里"够用就好"。
     */
    private boolean isComplex(ChatRequest request) {
        if (request == null || request.messages() == null) {
            return false;
        }
        List<ChatMessage> messages = request.messages();
        if (messages.size() > 8) {
            return true;
        }
        int totalChars = 0;
        String lastUserText = "";
        for (ChatMessage message : messages) {
            String text = textOf(message);
            totalChars += text.length();
            if (message instanceof UserMessage) {
                lastUserText = text;
            }
        }
        if (totalChars > 2000) {
            return true;
        }
        for (String marker : COMPLEX_MARKERS) {
            if (lastUserText.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private String textOf(ChatMessage message) {
        if (message instanceof UserMessage user) {
            String text = user.singleText();
            return text == null ? "" : text;
        }
        if (message instanceof AiMessage ai) {
            String text = ai.text();
            return text == null ? "" : text;
        }
        return "";
    }

    private boolean isStrong(Route route) {
        return route.name() != null && route.name().toLowerCase().contains("pro");
    }
}
