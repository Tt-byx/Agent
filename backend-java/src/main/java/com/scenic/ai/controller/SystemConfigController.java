package com.scenic.ai.controller;

import com.scenic.ai.exception.RedisShortCircuitException;
import com.scenic.ai.dto.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

/**
 * 系统健康检查。
 *
 * A2 · Python 退场后，检查对象换成**当前真实的主链路依赖**：
 * MySQL（业务数据）、Redis（记忆/限流）、pgvector（向量检索）。
 */
@RestController
@RequestMapping("/api/system")
public class SystemConfigController {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 系统健康检查 */
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> status = new HashMap<>();

        // MySQL：业务数据
        try (Connection conn = dataSource.getConnection()) {
            status.put("mysql", Map.of("status", "ok", "database", conn.getCatalog()));
        } catch (Exception e) {
            RedisShortCircuitException.rethrowIf(e);   // 短路必须冒泡，不能被降级逻辑吞掉
            status.put("mysql", Map.of("status", "error", "message", e.getMessage()));
        }

        // Redis：短期记忆 / 会话摘要 / 限流
        try {
            redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<String>)
                    connection -> connection.ping());
            status.put("redis", Map.of("status", "ok"));
        } catch (Exception e) {
            RedisShortCircuitException.rethrowIf(e);   // 短路必须冒泡，不能被降级逻辑吞掉
            status.put("redis", Map.of("status", "error", "message", e.getMessage()));
        }

        return Result.success(status);
    }
}
