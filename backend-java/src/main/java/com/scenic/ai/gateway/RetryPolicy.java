package com.scenic.ai.gateway;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RetriableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Wave 1 · 分级重试策略（退避 + 抖动）。
 *
 * 为什么不用 LangChain4j 内置的 {@code maxRetries}：
 *   内置重试是**无差别重试** —— 鉴权失败、参数错误、模型不存在这类「重试一万次也不会成功」的错误
 *   同样会被重试，白白放大延迟、消耗配额，还会让故障放大（原本 1 次失败变成 3 次请求）。
 *
 * 分类依据直接用框架已有的异常语义（无需自己解析字符串）：
 *   {@link RetriableException} → RateLimitException(429) / InternalServerException(5xx) / TimeoutException
 *   {@link NonRetriableException} → AuthenticationException / InvalidRequestException / ModelNotFoundException
 * 其余按 HTTP 状态码与 IO 异常兜底判断。
 *
 * 退避采用**指数 + 全抖动（full jitter）**：多个请求同时失败时，随机化重试时刻，
 * 避免它们在同一个时间点一起重试形成尖峰（thundering herd）。
 */
@Component
@Slf4j
public class RetryPolicy {

    private final int maxAttempts;
    private final long baseBackoffMs;
    private final long maxBackoffMs;

    public RetryPolicy(@Value("${llm.retry.max-attempts:3}") int maxAttempts,
                       @Value("${llm.retry.base-backoff-ms:500}") long baseBackoffMs,
                       @Value("${llm.retry.max-backoff-ms:5000}") long maxBackoffMs) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseBackoffMs = Math.max(1, baseBackoffMs);
        this.maxBackoffMs = Math.max(this.baseBackoffMs, maxBackoffMs);
        log.info("[RETRY] 分级重试就绪：最多 {} 次尝试，退避 {}~{}ms（指数 + 全抖动）",
                this.maxAttempts, this.baseBackoffMs, this.maxBackoffMs);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * 该异常是否值得重试。
     * 未知异常按「不重试」处理 —— 保守策略，避免对真正的逻辑错误反复重试。
     */
    public boolean isRetriable(Throwable throwable) {
        Throwable cause = rootCause(throwable);

        if (cause instanceof NonRetriableException) {
            return false;
        }
        if (cause instanceof RetriableException) {
            return true;
        }
        if (cause instanceof HttpException httpException) {
            int status = httpException.statusCode();
            return status == 429 || status >= 500;
        }
        if (cause instanceof java.io.IOException
                || cause instanceof java.util.concurrent.TimeoutException) {
            return true;
        }
        return false;
    }

    /** 第 attempt 次失败后的退避时长（attempt 从 1 开始） */
    public long backoffMillis(int attempt) {
        long exponential = Math.min(maxBackoffMs, baseBackoffMs * (1L << Math.max(0, attempt - 1)));
        // 全抖动：在 [base, exponential] 之间随机，既保证下限也保留随机性
        return ThreadLocalRandom.current().nextLong(baseBackoffMs, exponential + 1);
    }

    /** 退避等待；被中断时恢复中断标记并返回 false（调用方应放弃重试） */
    public boolean backoff(int attempt) {
        long millis = backoffMillis(attempt);
        try {
            log.debug("[RETRY] 等待 {}ms 后重试（第 {} 次失败）", millis, attempt);
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        int guard = 0;
        while (current.getCause() != null && current.getCause() != current && guard++ < 10) {
            current = current.getCause();
        }
        return current;
    }
}
