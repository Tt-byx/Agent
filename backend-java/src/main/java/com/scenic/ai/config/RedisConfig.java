package com.scenic.ai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 客户端配置。
 *
 * <p>用 {@link ShortCircuitStringRedisTemplate} 替换 Spring Boot 自动配置的那个
 * （{@code RedisAutoConfiguration} 上有 {@code @ConditionalOnMissingBean(StringRedisTemplate.class)}，
 * 自己声明就能顶掉默认实现）。
 */
@Configuration
public class RedisConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    /**
     * 短路窗口。一次调用失败后，窗口内的后续调用直接抛
     * {@link com.scenic.ai.exception.RedisShortCircuitException}，
     * 由上层映射成 HTTP 503。
     */
    @Value("${spring.data.redis.short-circuit-ms:10000}")
    private Long shortCircuitMs;

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        log.info("Redis 客户端启用失败短路包装，窗口 {}ms", shortCircuitMs);
        return new ShortCircuitStringRedisTemplate(connectionFactory, shortCircuitMs);
    }
}
