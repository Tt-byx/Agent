package com.scenic.ai.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * P8 · 限流（Redis 固定窗口计数）。
 *
 * 为什么自己写而不是上 Bucket4j / Resilience4j：
 *   这里要限的是「每个游客的模型调用频次」，维度是业务身份（visitorId），
 *   而且天然要用已有的 Redis —— 十几行代码就够，引入一个框架反而是负担。
 *
 * 窗口按分钟切（key 里带时间片），Redis 计数 + 短 TTL 自动过期。
 * **Redis 挂了直接放行**：限流是保护措施，不能反过来把服务打死。
 */
@Component
@Slf4j
public class RateLimiter {

    private static final long WINDOW_MILLIS = 60_000L;

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final int limitPerMinute;

    public RateLimiter(StringRedisTemplate redis,
                       @Value("${gateway.rate-limit.enabled:true}") boolean enabled,
                       @Value("${gateway.rate-limit.per-minute:20}") int limitPerMinute) {
        this.redis = redis;
        this.enabled = enabled;
        this.limitPerMinute = Math.max(limitPerMinute, 1);
        log.info("限流就绪：enabled={}, 每分钟 {} 次", enabled, limitPerMinute);
    }

    /**
     * @return true = 放行，false = 超出配额
     */
    public boolean allow(String key) {
        if (!enabled || key == null || key.isBlank()) {
            return true;
        }
        try {
            String bucket = "rate:" + key + ":" + (System.currentTimeMillis() / WINDOW_MILLIS);
            Long count = redis.opsForValue().increment(bucket);
            if (count != null && count == 1L) {
                redis.expire(bucket, Duration.ofMillis(WINDOW_MILLIS * 2));
            }
            boolean allowed = count != null && count <= limitPerMinute;
            if (!allowed) {
                log.warn("[RATE] 已限流：key={}，本分钟第 {} 次（上限 {}）", key, count, limitPerMinute);
            }
            return allowed;
        } catch (Exception e) {
            log.warn("限流检查失败（Redis 不可用，放行）: {}", e.getMessage());
            return true;
        }
    }
}
