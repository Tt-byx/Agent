package com.scenic.ai.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scenic.ai.dto.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Wave 4 · 对话幂等。
 *
 * 解决什么问题：原实现没有幂等键，用户（或前端重试逻辑）重复提交同一个问题时，
 * 会**各自跑一遍完整 Agent 循环、各写一条问答落库、各消耗一次 token** —— 重复计费且有重复数据。
 *
 * 实现（Redis）：
 *   `idem:result:{identity}:{requestId}` —— 首次处理完成后写入结果（JSON），命中即直接返回；
 *   `idem:lock:{identity}:{requestId}`   —— 处理期间的短锁（SETNX），
 *   并发重复提交时第二个请求拿到锁失败 → 返回 409「正在处理中」，而不是重复跑一遍。
 *
 * 为什么 identity 参与键：幂等作用域应该是「同一个人的同一次请求」，
 * 不加身份会让 A 用与 B 相同的 requestId 拿到 B 的答案。
 */
@Component
@Slf4j
public class IdempotencyStore {

    private static final String RESULT_PREFIX = "idem:result:";
    private static final String LOCK_PREFIX = "idem:lock:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final long ttlMinutes;
    private final long lockSeconds;

    public IdempotencyStore(StringRedisTemplate redis,
                            ObjectMapper objectMapper,
                            @Value("${gateway.idempotency.ttl-minutes:30}") long ttlMinutes,
                            @Value("${gateway.idempotency.lock-seconds:60}") long lockSeconds) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttlMinutes = Math.max(1, ttlMinutes);
        this.lockSeconds = Math.max(5, lockSeconds);
        log.info("幂等存储就绪：结果保留 {} 分钟，处理中锁 {} 秒", this.ttlMinutes, this.lockSeconds);
    }

    /** 组合键：身份 + 请求号 */
    public String buildKey(String identity, String requestId) {
        return (identity == null ? "anonymous" : identity) + ":" + requestId;
    }

    /** 查首次结果；未命中或 Redis 异常都返回 null（幂等是增强，不能反过来阻断主流程） */
    public ChatResponse find(String key) {
        try {
            String json = redis.opsForValue().get(RESULT_PREFIX + key);
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, ChatResponse.class);
        } catch (Exception e) {
            log.warn("幂等结果读取失败（按未命中处理）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 处理期间的占位锁。
     *
     * @return true = 抢到锁，可以开始处理；false = 已有同请求在处理中
     */
    public boolean tryLock(String key) {
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(
                    LOCK_PREFIX + key, "1", Duration.ofSeconds(lockSeconds));
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            // Redis 不可用时放行：宁可失去防重，也不能让正常请求全被拒
            log.warn("幂等加锁失败（放行）: {}", e.getMessage());
            return true;
        }
    }

    /** 处理完成后落结果，并释放占位锁 */
    public void saveAndUnlock(String key, ChatResponse response) {
        try {
            redis.opsForValue().set(RESULT_PREFIX + key,
                    objectMapper.writeValueAsString(response),
                    Duration.ofMinutes(ttlMinutes));
        } catch (Exception e) {
            log.warn("幂等结果写入失败（不影响本次响应）: {}", e.getMessage());
        } finally {
            unlock(key);
        }
    }

    /** 仅释放占位锁（处理失败时调用，让客户端可以重试） */
    public void unlock(String key) {
        try {
            redis.delete(LOCK_PREFIX + key);
        } catch (Exception e) {
            log.debug("幂等解锁失败（等待自动过期）: {}", e.getMessage());
        }
    }
}
