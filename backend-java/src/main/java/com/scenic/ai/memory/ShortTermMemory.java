package com.scenic.ai.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
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

    /**
     * 2026-10-29（D21）· 追加 + 续期打包成一次 Lua 调用。
     *
     * <p>原实现是两条命令：{@code rightPush} 然后 {@code expire}。若第二条失败（网络抖动、
     * 客户端超时、连接被中间件掐断），这个 key 就**永久没有 TTL** —— 会话记忆会一直驻留，
     * 而类注释还写着「key 带过期时间，避免无人清理的会话无限占用内存」。
     *
     * <p>Redis 执行 Lua 时是单线程原子的：要么两条都生效，要么都不生效。
     * 而且只在服务端算一次，比两次往返省一个 RTT。
     */
    private static final DefaultRedisScript<Long> APPEND_SCRIPT = new DefaultRedisScript<>(
            "redis.call('RPUSH', KEYS[1], ARGV[1]) "
                    + "redis.call('EXPIRE', KEYS[1], ARGV[2]) "
                    + "return 1",
            Long.class);

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
            // D21：RPUSH + EXPIRE 原子化（见 APPEND_SCRIPT 的注释）
            redis.execute(APPEND_SCRIPT,
                    java.util.List.of(key),
                    objectMapper.writeValueAsString(new Turn(role, content)),
                    String.valueOf(TTL.toSeconds()));
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
        return alignToTurnBoundary(result);
    }

    /**
     * 2026-10-29（D20）· 把窗口对齐到「轮」的边界。
     *
     * <p>窗口是按条数往回退的（{@code size - turns*2}）。当 list 长度是**奇数**时，
     * 退到的起点会落在 assistant 上，窗口就变成「导游先说一句，没有对应的提问」：
     *
     * <pre>
     * 导游: 灵山大佛通高88米……      ← 孤立的开头，模型不知道在回答什么
     * 游客: 那门票多少钱
     * 导游: 成人票210元
     * </pre>
     *
     * <p>奇数长度的可达路径：模型返回空 {@code text()} 时 assistant 那条被前置校验挡掉、
     * 或 Redis 抖动导致第二条 append 丢失。
     *
     * <p>在**读取侧**兜底而不是在写入侧保证成对：窗口的职责就是「给我一段能读的对话」，
     * 这个约束应该由窗口自己保证，而不是要求写入方永远不出错。
     */
    private List<Turn> alignToTurnBoundary(List<Turn> turns) {
        if (!turns.isEmpty() && "assistant".equals(turns.get(0).role())) {
            return new ArrayList<>(turns.subList(1, turns.size()));
        }
        return turns;
    }

    public long size(String sessionId) {
        try {
            Long size = redis.opsForList().size(KEY_PREFIX + sessionId);
            return size == null ? 0 : size;
        } catch (Exception e) {
            // 2026-10-29（D19）· 这里必须打日志。返回 0 在语义上有二义性 ——
            // 「这轮没有记忆」和「我不知道有多少」混在一起，调用方无法区分。
            // 更糟的是 0 会被 AgentLoop 当成「没超窗」从而跳过摘要；等 Redis 恢复后
            // 一次 drain 一大批，单次摘要的 token 成本可能翻十倍。
            log.warn("短期记忆长度读取失败（按 0 处理，本轮会跳过摘要）: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 取出最旧的 count 条（用于摘要后移出滑窗）。
     *
     * <p>2026-10-29（D10）：这里必须挡住 {@code count <= 0}。
     * Redis 的 {@code LRANGE key 0 -1} 语义是「**取全部**」，不是「空范围」——
     * 原实现 {@code range(key, 0, count - 1)} 在 {@code count == 0} 时会变成
     * {@code range(key, 0, -1)}，先取走全部、再 {@code trim(size, -1)} 把整个 list 清空。
     * 也就是说「取 0 条」的实际行为是「取走全部并清空」。
     *
     * <p>当前调用方（{@code AgentLoop}）有 {@code size > window} 守卫，所以 count 至少为 1，
     * 这个问题暂时不可达。但签名允许传 0，且失败是**静默的**（catch 吞掉异常、trim 成功后连日志都没有）——
     * 将来把守卫从 {@code >} 改成 {@code >=} 就是一次无声的全量记忆清空。
     * 所有「取 N 条」的 API 都该在入口挡住非正数。
     */
    public List<Turn> drain(String sessionId, long count) {
        List<Turn> drained = new ArrayList<>();
        if (count <= 0) {
            return drained;
        }
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
            // D19：null 表示「没有摘要」，同样不能把「读失败」混进去 ——
            // 读失败会让 summarize() 以为没有旧摘要，于是覆盖写，历史摘要就丢了。
            log.warn("会话摘要读取失败（按无摘要处理，可能导致摘要被覆盖）: {}", e.getMessage());
            return null;
        }
    }
}
