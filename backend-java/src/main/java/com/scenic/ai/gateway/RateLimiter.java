package com.scenic.ai.gateway;

import com.scenic.ai.exception.RedisShortCircuitException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 限流（Redis 固定窗口计数）。
 *
 * 为什么自己写而不是上 Bucket4j / Resilience4j：
 *   这里要限的是「每个游客的模型调用频次」，维度是业务身份（visitorId），
 *   而且天然要用已有的 Redis —— 十几行代码就够，引入一个框架反而是负担。
 *
 * 窗口按分钟切（key 里带时间片），Redis 计数 + 短 TTL 自动过期。
 * **Redis 挂了直接放行**：限流是保护措施，不能反过来把服务打死。
 *
 * Wave 3：补上 `scenic.rate_limit.rejected{dimension}` 指标 —— 被挡掉多少请求必须可见，
 * 否则无法判断「限流阈值是保护了系统还是误伤了正常用户」。
 */
@Component
@Slf4j
public class RateLimiter {

    private static final long WINDOW_MILLIS = 60_000L;

    /**
     * 2026-10-29（D21）· INCR + 首次 EXPIRE 打包成一次 Lua 调用。
     *
     * <p>原实现是两条命令：{@code INCR} 然后（计数为 1 时）{@code EXPIRE}。
     * 若第一条成功、第二条失败，这个计数器就**永不过期** —— 该 key 从此固定返回
     * 「已超额」，那个身份被**永久限流**。而限流器的注释还写着「短 TTL 自动过期」。
     *
     * <p>Lua 在 Redis 里是单线程原子执行的，两条命令要么都生效要么都不生效。
     */
    private static final DefaultRedisScript<Long> INCR_WITH_TTL_SCRIPT = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1]) "
                    + "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
                    + "return c",
            Long.class);

    private final StringRedisTemplate redis;
    private final AgentMetrics metrics;
    private final boolean enabled;
    private final int limitPerMinute;

    public RateLimiter(StringRedisTemplate redis,
                       AgentMetrics metrics,
                       @Value("${gateway.rate-limit.enabled:true}") boolean enabled,
                       @Value("${gateway.rate-limit.per-minute:20}") int limitPerMinute) {
        this.redis = redis;
        this.metrics = metrics;
        this.enabled = enabled;
        this.limitPerMinute = Math.max(limitPerMinute, 1);
        log.info("限流就绪：enabled={}, 每分钟 {} 次", enabled, limitPerMinute);
    }

    public boolean allow(String key) {
        return allow(key, "unknown");
    }

    /**
     * @param key       限流维度标识（visitorId 或客户端 IP）
     * @param dimension 维度类型，仅用于指标标签：visitor / ip
     * @return true = 放行，false = 超出配额
     */
    public boolean allow(String key, String dimension) {
        return allow(key, dimension, limitPerMinute);
    }

    /**
     * Wave 6 · 指定额度的限流。
     *
     * 为什么需要：对接外部客户端的 MCP 端点有自己的流量特征（一次会话可能连续调多个工具），
     * 不能和对话入口共用「20 次/分钟」这种面向游客的额度。
     *
     * @param key       限流维度标识（visitorId / IP / MCP 客户端标识）
     * @param dimension 维度类型，仅用于指标标签：visitor / ip / mcp
     * @param limit     该维度每分钟允许的次数
     */
    public boolean allow(String key, String dimension, int limit) {
        if (!enabled || key == null || key.isBlank()) {
            return true;
        }
        int effectiveLimit = Math.max(1, limit);
        try {
            String bucket = "rate:" + key + ":" + (System.currentTimeMillis() / WINDOW_MILLIS);
            // D21：INCR 与「首次设 TTL」原子化，避免计数器永不过期导致该身份被永久限流
            Long count = redis.execute(INCR_WITH_TTL_SCRIPT,
                    java.util.List.of(bucket),
                    String.valueOf(WINDOW_MILLIS * 2 / 1000));
            boolean allowed = count != null && count <= effectiveLimit;
            if (!allowed) {
                metrics.rateLimitRejected(dimension);
                log.warn("[RATE] 已限流：{}={}，本分钟第 {} 次（上限 {}）",
                        dimension, key, count, effectiveLimit);
            }
            return allowed;
        } catch (Exception e) {
            RedisShortCircuitException.rethrowIf(e);   // 短路必须冒泡，不能被降级逻辑吞掉
            log.warn("限流检查失败（Redis 不可用，放行）: {}", e.getMessage());
            return true;
        }
    }
}
