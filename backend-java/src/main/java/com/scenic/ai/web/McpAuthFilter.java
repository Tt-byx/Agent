package com.scenic.ai.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scenic.ai.gateway.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Wave 6 · MCP 端点的鉴权与限流。
 *
 * 解决的问题：MCP Server 直接挂在 {@code /mcp}，此前**完全开放**（无鉴权无限流）——
 * 任何能访问到该端口的人都能调用景区工具（查票、查开放状态），
 * 而它的流量特征又与游客对话不同（一次会话会连续调用多个工具），
 * 因此不能复用对话入口那套「20 次/分钟」的额度。
 *
 * 鉴权方式：共享密钥（`X-MCP-Token` 请求头，或 `token` 查询参数，便于不支持自定义头的客户端）。
 * **未配置 `mcp.auth-token` 时放行但显式告警** —— 保持本机开发顺手，同时把风险说清楚，
 * 而不是悄悄留一个洞。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@Slf4j
public class McpAuthFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-MCP-Token";
    private static final String QUERY_KEY = "token";

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final String expectedToken;
    private final int perMinute;

    public McpAuthFilter(RateLimiter rateLimiter,
                         ObjectMapper objectMapper,
                         @Value("${mcp.auth-token:}") String expectedToken,
                         @Value("${mcp.rate-limit.per-minute:120}") int perMinute) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
        this.expectedToken = expectedToken == null ? "" : expectedToken.strip();
        this.perMinute = perMinute;
        if (this.expectedToken.isEmpty()) {
            log.warn("[MCP] 未配置 mcp.auth-token —— MCP 端点当前**不对调用方做鉴权**，"
                    + "仅依赖网络边界。生产环境请在 .env 里配置（建议 32 字节以上随机串）");
        } else {
            log.info("[MCP] 已启用共享密钥鉴权；限流 {} 次/分钟/客户端", perMinute);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI() == null || !request.getRequestURI().startsWith("/mcp");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // ① 鉴权（未配置密钥时跳过，见构造函数告警）
        if (!expectedToken.isEmpty()) {
            String provided = request.getHeader(HEADER);
            if (provided == null || provided.isBlank()) {
                provided = request.getParameter(QUERY_KEY);
            }
            if (!expectedToken.equals(provided)) {
                log.warn("[MCP] 拒绝未授权访问：{} {}", request.getMethod(), request.getRequestURI());
                writeError(response, 401, "MCP 鉴权失败：请在 X-MCP-Token 头或 token 参数中提供正确密钥");
                return;
            }
        }

        // ② 限流：按客户端 IP，使用 MCP 自己的额度（不与对话入口共用）
        String clientKey = "mcp:" + clientIp(request);
        if (!rateLimiter.allow(clientKey, "mcp", perMinute)) {
            log.warn("[MCP] 客户端 {} 超出 MCP 调用配额", clientIp(request));
            writeError(response, 429, "MCP 调用过于频繁，请稍后再试");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                Map.of("code", code, "message", message)));
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].strip();
        }
        return request.getRemoteAddr();
    }
}
