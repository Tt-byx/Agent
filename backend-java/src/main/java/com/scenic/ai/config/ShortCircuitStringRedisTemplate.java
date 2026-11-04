package com.scenic.ai.config;

import com.scenic.ai.exception.RedisShortCircuitException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 失败短路的 Redis 客户端。
 *
 * <p><b>要解决的问题</b>：Redis 超时只能让单次失败变快。一次对话会串行访问
 * Redis 七八次（限流 → 滑窗读 → 缓存查 → 幂等占位 → 滑窗写 → 摘要检查…），
 * 每次都要各自等满超时。实测停掉 Redis 后仍有 12.73s。
 *
 * <p><b>做法</b>：第一次调用失败后开启短路窗口（默认 10s），窗口内的后续调用
 * 直接抛 {@link RedisShortCircuitException}，完全不触碰网络。
 *
 * <p><b>为什么覆写 execute 而不是 opsFor*</b>：
 * {@link StringRedisTemplate} 的所有高层操作（opsForValue / opsForHash / expire …）
 * 最终都会走 {@code execute(RedisCallback)} 或 {@code execute(SessionCallback)}，
 * 只要拦截住这两个入口，就能覆盖全部 Redis 调用，不用逐个方法去覆写。
 *
 * <p><b>与 pgvector 短路的差异</b>：pgvector 降级只是少了知识库（回答质量下降），
 * 所以静默降级即可；Redis 挂了会让限流和幂等同时失效，属于服务降级，
 * 因此这里抛出可识别的异常，由上层映射成 HTTP 503 让用户知道。
 */
public class ShortCircuitStringRedisTemplate extends StringRedisTemplate {

    private static final Logger LOG = LoggerFactory.getLogger(ShortCircuitStringRedisTemplate.class);

    /** >0 = 短路截止时刻；0 = 正常 */
    private final AtomicLong openUntilMs = new AtomicLong(0);
    private final long windowMs;

    public ShortCircuitStringRedisTemplate(RedisConnectionFactory connectionFactory, long windowMs) {
        super(connectionFactory);
        this.windowMs = windowMs;
    }

    private void ensureOpen() {
        long until = openUntilMs.get();
        if (until == 0) {
            return;
        }
        long remain = until - System.currentTimeMillis();
        if (remain > 0) {
            throw new RedisShortCircuitException(
                    "服务正在降级（依赖不可用），约 " + Math.max(1, remain / 1000) + " 秒后自动恢复", remain);
        }
        openUntilMs.compareAndSet(until, 0);
    }

    private void trip(RuntimeException cause) {
        long until = System.currentTimeMillis() + windowMs;
        long prev = openUntilMs.getAndSet(until);
        if (prev == 0) {
            LOG.warn("[REDIS] 调用失败，开启短路窗口 {}ms: {}", windowMs, cause.getMessage());
        }
    }

    @Override
    public <T> T execute(RedisCallback<T> action) {
        ensureOpen();
        try {
            return super.execute(action);
        } catch (RuntimeException e) {
            trip(e);
            throw e;
        }
    }

    @Override
    public <T> T execute(SessionCallback<T> session) {
        ensureOpen();
        try {
            return super.execute(session);
        } catch (RuntimeException e) {
            trip(e);
            throw e;
        }
    }

    @Override
    public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
        ensureOpen();
        try {
            return super.execute(script, keys, args);
        } catch (RuntimeException e) {
            trip(e);
            throw e;
        }
    }
}
