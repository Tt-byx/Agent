package com.scenic.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scenic.ai.dto.Result;
import com.scenic.ai.security.JwtAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

/**
 * Wave 0 · 安全底座。
 *
 * 设计要点：
 *   1. **无状态**：不建会话，身份完全来自 JWT —— 这是水平扩容的前提；
 *   2. **游客可用对话**：/api/chat 放行（导览服务的核心体验不该要求注册），由 Redis 限流兜底；
 *   3. **管理接口按角色收口**：后台/知识库/评测/分析类接口一律需要 ADMIN，
 *      修掉「管理接口全裸奔」这个最严重的问题；
 *   4. **401/403 返回 JSON**：前端拿到的仍是统一的 Result 结构，不会突然变成 HTML 错误页；
 *   5. CORS 复用 WebMvcConfigurer 里的全局配置（cors(withDefaults()) 会通过
 *      HandlerMappingIntrospector 拾取 MVC 的 CORS 规则），避免两处配置打架。
 */
@Configuration
@EnableWebSecurity
@Slf4j
public class SecurityConfig {

    private final ObjectMapper objectMapper;

    public SecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthFilter jwtAuthFilter) throws Exception {
        http
                // 纯 token API，无 Cookie 会话 → 关闭 CSRF（否则前端每个写请求都要带 csrf token）
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 公开：登录注册、健康检查（探活用）、MCP 端点、监控端点
                        // 注：/actuator/prometheus 生产环境应改为仅内网可访问
                        // 2026-10-29（D30）：/ws/** 已移除 —— WebSocket 链路整体删除，不再有该端点
                        .requestMatchers("/api/auth/**", "/api/system/health",
                                "/mcp", "/mcp/**", "/actuator/health", "/actuator/info",
                                "/actuator/prometheus").permitAll()
                        // 游客可用：对话入口（另有 Redis 限流与隔离键校验保护）
                        .requestMatchers("/api/chat", "/api/chat/**").permitAll()
                        // 管理接口：必须管理员
                        .requestMatchers("/api/admin-ai/**", "/api/knowledge/**", "/api/rag/**",
                                "/api/analytics/**", "/api/dashboard/**",
                                "/api/visitor-profiles/**").hasRole("ADMIN")
                        // Wave 4 · 成本/延迟台账属于经营数据，仅管理员可见
                        .requestMatchers("/api/ops/**").hasRole("ADMIN")
                        // 其余（会话历史、反馈等）：登录即可，资源归属校验在业务层
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(this::writeUnauthorized)
                        .accessDeniedHandler(this::writeForbidden))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        log.info("[SECURITY] 安全过滤链就绪：无状态 JWT 认证；对话接口对游客开放；"
                + "管理接口需 ROLE_ADMIN");
        return http.build();
    }

    /** 密码哈希：BCrypt（自带盐、可调强度）—— 替换原先的明文存储 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private void writeUnauthorized(HttpServletRequest request, HttpServletResponse response,
                                   AuthenticationException exception) throws IOException {
        writeJson(response, 401, "未登录或登录已过期，请重新登录");
    }

    private void writeForbidden(HttpServletRequest request, HttpServletResponse response,
                                AccessDeniedException exception) throws IOException {
        log.warn("[SECURITY] 拒绝越权访问：{} {}（身份：{}）",
                request.getMethod(), request.getRequestURI(), request.getRemoteUser());
        writeJson(response, 403, "当前账号无权访问该资源");
    }

    private void writeJson(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(code, message)));
    }
}
