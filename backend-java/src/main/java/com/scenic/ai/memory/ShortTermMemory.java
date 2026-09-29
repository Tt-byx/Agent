package com.scenic.ai.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * P8 · 短期记忆（Redis 滑窗）。
 *
 * 存每轮对话的「用户一条 + 助手一条」，按会话隔离，滑动窗口只保留最近 N 轮。
 * 溢出的旧消息交给摘要层；摘要本身也存在 Redis（key = short:{id}:summary）。
 *
 * 设计要点：
 *   - **读写都不抛异常**：Redis 挂了只影响记忆，不能影响问答主流程（降级为无记忆）；
 *   - key 带过期时间，避免无人清理的会话无限占用内存。
 */
@Component
@Slf4j
public class ShortTermMemory {

    private static final String KEY_PREFIX = "memory:short:";
    private static final String SUMMARY_KEY = "memory:short:%s:summary";
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final int maxTurns;

    public record Turn(String role, String content) {}

    public ShortTermMemory(StringRedisTemplate redis,
                           ObjectMapper objectMapper,
                           @Value("${memory.short-term.max-turns:10}") int maxTurns) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.maxTurns = Math.max(maxTurns, 1);
        log.info("短期记忆就绪：滑窗 {} 轮（窗口容量 {} 条消息）", maxTurns, windowSize());
    }

    /**
     * 滑窗容量（消息条数，1 轮 = 2 条）。
     *
     * Wave 6 · 修 B3：此前 `AgentLoop` 里硬编码了 20 条，与这里的 maxTurns 各说各话；
     * 而配置项 `summarize-threshold` 先被 clamp 到 maxTurns×2、又被赋给一个**从未被读取**的字段 ——
     * 是一个「看起来能调、实际是死的」配置（比没有配置更误导）。
     * 现在窗口容量**只由 `max-turns` 决定**，并从记忆组件统一对外提供。
     */
    public int windowSize() {
        return maxTurns * 2;
    }

    public void append(String sessionId, String role, String content) {
        if (sessionId == null || sessionId.isBlank() || content == null || content.isBlank()) {
            return;
        }
        try {
            String key = KEY_PREFIX + sessionId;
            redis.opsForList().rightPush(key, objectMapper.writeValueAsString(new Turn(role, content)));
            redis.expire(key, TTL);
        } catch (Exception e) {
            log.warn("短期记忆写入失败（不影响主流程）: {}", e.getMessage());
        }
    }

    /** 最近 N 轮的消息（按时序升序） */
    public List<Turn> recent(String sessionId) {
        return recent(sessionId, maxTurns);
    }

    public List<Turn> recent(String sessionId, int turns) {
        List<Turn> result = new ArrayList<>();
        if (sessionId == null || sessionId.isBlank()) {
            return result;
        }
        try {
            String key = KEY_PREFIX + sessionId;
            Long size = redis.opsForList().size(key);
            if (size == null || size == 0) {
                return result;
            }
            long start = Math.max(0L, size - turns * 2L);
            List<String> raw = redis.opsForList().range(key, start, size - 1);
            if (raw == null) {
                return result;
            }
            for (String item : raw) {
                try {
                    result.add(objectMapper.readValue(item, Turn.class));
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            log.warn("短期记忆读取失败（降级为无记忆）: {}", e.getMessage());
        }
        return result;
    }

    public long size(String sessionId) {
        try {
            Long size = redis.opsForList().size(KEY_PREFIX + sessionId);
            return size == null ? 0 : size;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 取出最旧的 count 条（用于摘要后移出滑窗） */
    public List<Turn> drain(String sessionId, long count) {
        List<Turn> drained = new ArrayList<>();
        try {
            String key = KEY_PREFIX + sessionId;
            List<String> raw = redis.opsForList().range(key, 0, count - 1);
            if (raw != null && !raw.isEmpty()) {
                redis.opsForList().trim(key, raw.size(), -1);
                for (String item : raw) {
                    drained.add(objectMapper.readValue(item, Turn.class));
                }
            }
        } catch (Exception e) {
            log.warn("短期记忆滑出失败: {}", e.getMessage());
        }
        return drained;
    }

    public void setSummary(String sessionId, String summary) {
        try {
            redis.opsForValue().set(String.format(SUMMARY_KEY, sessionId), summary, TTL);
        } catch (Exception e) {
            log.warn("写入会话摘要失败: {}", e.getMessage());
        }
    }

    public String summary(String sessionId) {
        try {
            return redis.opsForValue().get(String.format(SUMMARY_KEY, sessionId));
        } catch (Exception e) {
            return null;
        }
    }
}
