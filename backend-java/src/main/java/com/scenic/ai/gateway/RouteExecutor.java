package com.scenic.ai.gateway;

import com.scenic.ai.exception.RedisShortCircuitException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 2026-10-29（D15）· 模型候选遍历的**唯一实现**：熔断跳过 → 分级重试 → 主备切换。
 *
 * <h3>为什么要有这个类</h3>
 *
 * <p>改造前，{@link RoutedChatModel} 与 {@link RoutedStreamingChatModel} 各自写了一遍
 * 「遍历候选 + 查熔断 + 按错误类型重试 + 失败切下一个 + 全失败兜底」。它们是复制粘贴的产物，
 * 随后各自演化，于是出现了四处不一致（这就是 D15 的内容）：
 *
 * <table border="1">
 *   <caption>两条链路的实现差异</caption>
 *   <tr><th>#</th><th>同步路径</th><th>流式路径</th></tr>
 *   <tr><td>①</td><td>按复杂度排序候选</td><td><b>没有排序</b> —— 同一问题两个入口走不同模型</td></tr>
 *   <tr><td>②</td><td>backoff 被中断时记 onError/routeFailed</td>
 *       <td><b>漏记</b> —— 熔断器低估故障率</td></tr>
 *   <tr><td>③</td><td>—</td><td>onCompleteResponse 写在 try 内，会被自己的 catch 捕获</td></tr>
 *   <tr><td>④</td><td>—</td><td>超时后不撤销底层调用，迟到的回调仍会推流</td></tr>
 * </table>
 *
 * <p>「每次只修一个调用点」是这类问题的根因。把骨架收敛成一份之后，
 * 「两条链路行为不一致」在**结构上**就不可能再发生 —— 差异只剩调用方传进来的那个 lambda。
 *
 * <h3>不可回退点（流式特有）</h3>
 *
 * <p>流式有一个同步路径没有的概念：**首包一旦推给客户端，就不能再重试、也不能切换候选** ——
 * 否则用户会看到重复或拼接错乱的内容。这个信息只有调用方知道（它才知道有没有推过），
 * 所以约定用 {@link IrreversibleFailure} 表达：调用方发现「已推流 + 失败」就包成它抛出，
 * 本模板见到它立刻放弃，不再重试、不再换候选。
 *
 * <p>同步路径永远不会抛它，所以这条分支对同步行为零影响。
 */
@Slf4j
public final class RouteExecutor {

    /** 单次候选调用的实际动作。返回值就是「这次尝试成功的结果」。 */
    @FunctionalInterface
    public interface RouteCall<M, T> {
        T invoke(ModelRoute<M> route) throws Exception;
    }

    /**
     * 已经产生了**不可回退**的副作用（流式首包已推给客户端），模板必须立即放弃本次请求。
     * 携带原始异常，供上层原样转交给 error 回调。
     */
    public static final class IrreversibleFailure extends RuntimeException {
        public IrreversibleFailure(Throwable cause) {
            super(cause);
        }
    }

    /** 所有候选都处于熔断状态，本次请求根本没有调用任何模型 */
    public static final class AllRoutesUnavailableException extends IllegalStateException {
        public AllRoutesUnavailableException(String message) {
            super(message);
        }
    }

    private RouteExecutor() {
    }

    /**
     * 按顺序尝试候选，返回第一个成功的结果。
     *
     * @param routes      候选列表，**顺序即优先级**（是否按复杂度重排由调用方决定，见 SemanticRouter）
     * @param logTag      日志前缀（"[ROUTE]" / "[ROUTE-STREAM]"），只影响可读性
     * @param call        单次尝试的实际动作
     * @throws IrreversibleFailure           流式已推流后失败，调用方应直接把 cause 交给 error 回调
     * @throws AllRoutesUnavailableException 全部熔断
     * @throws RuntimeException              全部失败时抛出最后一个原因
     */
    public static <M, T> T execute(List<ModelRoute<M>> routes,
                                   RetryPolicy retryPolicy,
                                   AgentMetrics metrics,
                                   String logTag,
                                   RouteCall<M, T> call) {
        Throwable lastFailure = null;
        boolean anyCandidateAttempted = false;

        for (ModelRoute<M> route : routes) {
            CircuitBreaker breaker = route.breaker();
            // 熔断打开（或半开未取到许可）→ 直接跳过，不再白打一次。
            // 关键点：熔断状态必须参与路由决策，否则「熔断形同虚设」——
            // 主模型持续故障时，每个请求仍会先打一次主模型、失败后再切备，故障被放大成 N 倍流量。
            if (!breaker.tryAcquirePermission()) {
                log.warn("{} {} 处于熔断状态，本次请求直接跳过", logTag, route.name());
                metrics.routeSkipped(route.name());
                continue;
            }
            anyCandidateAttempted = true;

            for (int attempt = 1; attempt <= retryPolicy.maxAttempts(); attempt++) {
                long start = System.currentTimeMillis();
                try {
                    T result = call.invoke(route);
                    long costMillis = System.currentTimeMillis() - start;
                    breaker.onSuccess(costMillis, TimeUnit.MILLISECONDS);
                    metrics.routeSucceeded(route.name(), costMillis);
                    if (attempt > 1) {
                        log.info("{} {} 第 {} 次尝试成功（此前失败 {} 次）",
                                logTag, route.name(), attempt, attempt - 1);
                    }
                    return result;

                } catch (IrreversibleFailure e) {
                    // 已推流后失败：不能再重试、不能再换候选（否则用户看到重复内容）。
                    // 熔断器仍要记这一笔 —— 它确实失败了，只是没法补救。
                    long costMillis = System.currentTimeMillis() - start;
                    breaker.onError(costMillis, TimeUnit.MILLISECONDS, e.getCause());
                    metrics.routeFailed(route.name());
                    log.warn("{} {} 在已推流后失败，无法重试/切换候选：{}",
                            logTag, route.name(), e.getCause() == null ? "未知原因" : e.getCause().getMessage());
                    throw e;

                } catch (Exception e) {
                    RedisShortCircuitException.rethrowIf(e);   // 短路必须冒泡，不能被降级逻辑吞掉
                    long costMillis = System.currentTimeMillis() - start;
                    boolean retriable = retryPolicy.isRetriable(e);
                    boolean hasNextAttempt = attempt < retryPolicy.maxAttempts();

                    if (retriable && hasNextAttempt) {
                        log.warn("{} {} 第 {}/{} 次调用失败（可重试）：{}",
                                logTag, route.name(), attempt, retryPolicy.maxAttempts(), e.getMessage());
                        if (!retryPolicy.backoff(attempt)) {
                            // 退避时线程被中断：不再重试，记失败并尝试下一个候选。
                            // （D15②：原流式实现在这个分支漏了 onError/routeFailed/lastFailure 三件事，
                            //  导致熔断器低估故障率，且最终抛出的异常丢掉了真实原因。）
                            breaker.onError(costMillis, TimeUnit.MILLISECONDS, e);
                            metrics.routeFailed(route.name());
                            lastFailure = e;
                            break;
                        }
                        continue;
                    }

                    breaker.onError(costMillis, TimeUnit.MILLISECONDS, e);
                    metrics.routeFailed(route.name());
                    if (!retriable) {
                        // 鉴权、参数类错误重试没有意义，立即换候选取代浪费配额和延迟
                        log.warn("{} {} 失败且不可重试（{}），切换候选：{}",
                                logTag, route.name(), e.getClass().getSimpleName(), e.getMessage());
                    } else {
                        log.warn("{} {} 已重试 {} 次仍失败，切换候选", logTag, route.name(), attempt);
                    }
                    lastFailure = e;
                    break;
                }
            }
        }

        // 走到这里说明没有任何候选成功
        metrics.degrade("model_all_failed");
        if (!anyCandidateAttempted) {
            throw new AllRoutesUnavailableException("所有模型候选均处于熔断状态，暂时无法提供模型服务");
        }
        throw lastFailure instanceof RuntimeException runtimeException
                ? runtimeException
                : new IllegalStateException("所有模型候选均调用失败", lastFailure);
    }
}
