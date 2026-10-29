package com.scenic.ai.gateway;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * P8 建立 → Wave 1 加固 → 2026-10-29（D15）重构 · 模型路由（同步路径）。
 *
 * <p>实现 LangChain4j 的 {@link ChatModel}，对上层（AgentLoop）完全透明。
 *
 * <p>本类现在只负责**三件与「同步」有关的事**：
 * 把候选交给 {@link SemanticRouter} 排序、把单次调用包成 lambda、从响应里取 token 用量。
 * 「遍历候选 + 熔断 + 分级重试 + 主备切换」全部在 {@link RouteExecutor} 里 ——
 * 那份骨架由同步与流式**共用**，这是 D15 的核心：
 * 原先是两份复制粘贴的实现，然后各自演化出四处不一致（流式没有复杂度排序、
 * backoff 中断时漏记熔断、onCompleteResponse 写在 try 内、超时后不撤销底层调用）。
 *
 * <p>熔断（{@code CircuitBreaker}，失败率阈值 + 半开探测）的意义：
 * 纯顺序尝试会在主模型持续故障时，让每个请求都先打一次主模型、失败后再切备，
 * 再叠加模型层重试 —— 主模型事实上被打成 N 倍流量，故障被放大。
 * 熔断打开后**直接跳过该候选**，请求立刻走备模型。
 *
 * <p>分级重试（{@link RetryPolicy}）：只重试 429 / 5xx / 超时这类重试有意义的错误，
 * 鉴权与参数错误立即切换候选，不浪费配额和延迟；退避带全抖动。
 */
@Slf4j
public class RoutedChatModel implements ChatModel {

    private final List<ModelRoute<ChatModel>> routes;
    private final AgentMetrics metrics;
    private final RetryPolicy retryPolicy;

    public RoutedChatModel(List<ModelRoute<ChatModel>> routes,
                           AgentMetrics metrics,
                           RetryPolicy retryPolicy) {
        if (routes == null || routes.isEmpty()) {
            throw new IllegalArgumentException("至少要配置一条模型路由");
        }
        this.routes = List.copyOf(routes);
        this.metrics = metrics;
        this.retryPolicy = retryPolicy;
        log.info("[ROUTE] 模型路由就绪：{} 条（{}），单候选最多 {} 次尝试（分级退避）",
                routes.size(),
                routes.stream().map(ModelRoute::name).toList(),
                retryPolicy.maxAttempts());
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return RouteExecutor.execute(
                // 语义路由：复杂请求把强模型提前（D15①：流式路径原先缺这一步）
                SemanticRouter.order(routes, request, metrics),
                retryPolicy,
                metrics,
                "[ROUTE]",
                route -> {
                    ChatResponse response = route.model().chat(request);
                    // token 计数放在这里而不是 AgentLoop：只有路由层知道
                    // 这次回答实际由哪个候选产出，成本才能按模型（flash / pro）归因
                    if (response.tokenUsage() != null) {
                        metrics.tokens(response.tokenUsage().inputTokenCount(),
                                response.tokenUsage().outputTokenCount(), route.name());
                    }
                    return response;
                });
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        return chat(request);
    }
}
