package com.scenic.ai.gateway;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wave 5 · 流式模型路由（与 {@link RoutedChatModel} 对等的流式版本）。
 *
 * 为什么要单独做一个：如果流式路径直接用裸的 StreamingChatModel，就等于
 * **为了体验把 Wave 1 建立的熔断/分级重试/主备降级全丢了** —— 主模型故障时流式接口会直接报错。
 * 这里让流式路径复用同一套候选顺序、同一套熔断器与重试策略。
 *
 * ⭐ 流式特有的可靠性边界（与同步路径最大的不同）：
 *     **首包之前**失败 → 可以重试、可以静默切换候选（用户什么都还没看到）；
 *     **首包之后**失败 → **不能**重试也不能切换（否则用户会看到重复或拼接错乱的内容），
 *     只能如实把错误抛给上层，由它收尾（已显示的部分保留）。
 *   这个「不可回退点」是流式系统设计的核心概念。
 *
 * 实现上把异步回调收敛成同步等待（CountDownLatch），因为上游的 AgentLoop 是同步循环；
 * 每步的流式增量通过 handler 实时透传，不做缓冲，保证首字延迟真正前移。
 */
@Slf4j
public class RoutedStreamingChatModel implements StreamingChatModel {

    public record Route(String name, StreamingChatModel model, CircuitBreaker circuitBreaker) {}

    private final List<Route> routes;
    private final AgentMetrics metrics;
    private final RetryPolicy retryPolicy;
    private final long waitTimeoutSeconds;

    public RoutedStreamingChatModel(List<Route> routes,
                                    AgentMetrics metrics,
                                    RetryPolicy retryPolicy,
                                    long waitTimeoutSeconds) {
        if (routes == null || routes.isEmpty()) {
            throw new IllegalArgumentException("至少要配置一条流式模型路由");
        }
        this.routes = List.copyOf(routes);
        this.metrics = metrics;
        this.retryPolicy = retryPolicy;
        this.waitTimeoutSeconds = waitTimeoutSeconds;
        log.info("[ROUTE-STREAM] 流式路由就绪：{} 条（{}），单候选最多 {} 次尝试",
                routes.size(), routes.stream().map(Route::name).toList(), retryPolicy.maxAttempts());
    }

    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        Throwable lastFailure = null;
        boolean anyCandidateAttempted = false;

        for (Route route : routes) {
            if (!route.circuitBreaker().tryAcquirePermission()) {
                log.warn("[ROUTE-STREAM] {} 处于熔断状态，本次请求直接跳过", route.name());
                metrics.routeSkipped(route.name());
                continue;
            }
            anyCandidateAttempted = true;

            for (int attempt = 1; attempt <= retryPolicy.maxAttempts(); attempt++) {
                long start = System.currentTimeMillis();

                // 是否已经把增量推给客户端 —— 一旦推过，就跨过了「不可回退点」
                AtomicBoolean streamedToClient = new AtomicBoolean(false);
                CountDownLatch done = new CountDownLatch(1);
                AtomicReference<ChatResponse> completed = new AtomicReference<>();
                AtomicReference<Throwable> failed = new AtomicReference<>();

                try {
                    route.model().chat(request, new StreamingChatResponseHandler() {
                        @Override
                        public void onPartialResponse(String partialResponse) {
                            streamedToClient.set(true);
                            handler.onPartialResponse(partialResponse);
                        }

                        @Override
                        public void onCompleteResponse(ChatResponse response) {
                            completed.set(response);
                            done.countDown();
                        }

                        @Override
                        public void onError(Throwable error) {
                            failed.set(error);
                            done.countDown();
                        }
                    });

                    if (!done.await(waitTimeoutSeconds, TimeUnit.SECONDS)) {
                        throw new java.util.concurrent.TimeoutException(
                                "流式响应超时（" + waitTimeoutSeconds + "s 未完成）");
                    }
                    if (failed.get() != null) {
                        throw new RuntimeException(failed.get());
                    }

                    ChatResponse response = completed.get();
                    long costMillis = System.currentTimeMillis() - start;
                    route.circuitBreaker().onSuccess(costMillis, TimeUnit.MILLISECONDS);
                    metrics.routeSucceeded(route.name(), costMillis);
                    if (response != null && response.tokenUsage() != null) {
                        metrics.tokens(response.tokenUsage().inputTokenCount(),
                                response.tokenUsage().outputTokenCount(), route.name());
                    }
                    handler.onCompleteResponse(response);
                    return;

                } catch (Exception e) {
                    long costMillis = System.currentTimeMillis() - start;

                    // ⭐ 不可回退点：已经推过内容 → 不再重试、不再切换候选
                    if (streamedToClient.get()) {
                        route.circuitBreaker().onError(costMillis, TimeUnit.MILLISECONDS, e);
                        metrics.routeFailed(route.name());
                        metrics.degrade("stream_mid_failure");
                        log.warn("[ROUTE-STREAM] {} 在已推流后失败，无法重试/切换候选：{}",
                                route.name(), e.getMessage());
                        handler.onError(e);
                        return;
                    }

                    boolean retriable = retryPolicy.isRetriable(e);
                    boolean hasNextAttempt = attempt < retryPolicy.maxAttempts();
                    if (retriable && hasNextAttempt) {
                        log.warn("[ROUTE-STREAM] {} 第 {}/{} 次首包前失败（可重试）：{}",
                                route.name(), attempt, retryPolicy.maxAttempts(), e.getMessage());
                        if (!retryPolicy.backoff(attempt)) {
                            break;
                        }
                        continue;
                    }

                    route.circuitBreaker().onError(costMillis, TimeUnit.MILLISECONDS, e);
                    metrics.routeFailed(route.name());
                    log.warn("[ROUTE-STREAM] {} 失败（{}），切换候选",
                            route.name(), e.getClass().getSimpleName());
                    lastFailure = e;
                    break;
                }
            }
        }

        metrics.degrade("model_all_failed");
        if (!anyCandidateAttempted) {
            handler.onError(new IllegalStateException("所有模型候选均处于熔断状态，暂时无法提供模型服务"));
            return;
        }
        handler.onError(lastFailure != null ? lastFailure
                : new IllegalStateException("所有模型候选均调用失败"));
    }

    @Override
    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        chat(request, handler);
    }
}
