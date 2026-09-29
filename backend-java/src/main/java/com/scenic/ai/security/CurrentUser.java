package com.scenic.ai.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Wave 0 · 取当前登录身份的便捷入口。
 *
 * 替代原先散落各处的「自己解析 Authorization 头 + 查内存 tokenStore」，
 * 统一从 SecurityContext 读取，避免各处实现不一致（旧代码里 REST 与 WS 就不一致）。
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** 未登录返回 null */
    public static AuthUser get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthUser user) {
            return user;
        }
        return null;
    }

    public static Long id() {
        AuthUser user = get();
        return user == null ? null : user.id();
    }

    public static boolean isAdmin() {
        AuthUser user = get();
        return user != null && user.isAdmin();
    }
}
