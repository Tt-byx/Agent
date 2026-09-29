package com.scenic.ai.security;

/**
 * Wave 0 · 认证主体：从 JWT 解析出的当前请求身份。
 *
 * 放在 SecurityContext 里，业务层用 {@link CurrentUser#get()} 取，
 * 避免各个 Controller 各自解析 Authorization 头（旧实现的问题之一）。
 */
public record AuthUser(Long id, String username, String role) {

    public boolean isAdmin() {
        return "admin".equalsIgnoreCase(role);
    }

    /** 用于隔离键：登录用户一律用 user:{id}，与客户端声明的 visitorId 无关 */
    public String memoryKey() {
        return "user:" + id;
    }
}
