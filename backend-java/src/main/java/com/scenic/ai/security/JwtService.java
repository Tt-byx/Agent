package com.scenic.ai.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * Wave 0 · JWT 签发与校验。
 *
 * 替换原有的「UUID 存内存 Map」方案（B10）：内存 token 重启即失效、多实例不共享、
 * 无法携带角色信息。JWT 把 userId / username / role 编进 token 并用 HMAC-SHA256 签名，
 * 服务端无状态校验 —— 这是水平扩容的前提。
 *
 * 密钥治理：只从配置/环境变量读取（SCENIC_JWT_SECRET）。**未配置时生成临时随机密钥**
 * 并显式 WARN —— 宁可重启后 token 全失效，也不把固定密钥硬编码进仓库。
 */
@Service
@Slf4j
public class JwtService {

    private static final String ISSUER = "scenic-ai";

    /** 游客凭证的角色：不参与 Spring Security 认证，仅供业务层解析隔离键 */
    public static final String GUEST_ROLE = "guest";

    private final SecretKey signingKey;
    private final long ttlSeconds;
    private final long guestTtlSeconds;

    public JwtService(@Value("${security.jwt.secret:${SCENIC_JWT_SECRET:}}") String secret,
                      @Value("${security.jwt.ttl-seconds:86400}") long ttlSeconds,
                      @Value("${security.jwt.guest-ttl-seconds:604800}") long guestTtlSeconds) {
        this.ttlSeconds = ttlSeconds;
        this.guestTtlSeconds = guestTtlSeconds;

        if (secret == null || secret.strip().length() < 32) {
            this.signingKey = Jwts.SIG.HS256.key().build();
            log.warn("[SECURITY] 未配置 security.jwt.secret（或长度 < 32 字节），"
                    + "已生成临时随机密钥：**重启后所有 token 失效**。"
                    + "生产环境请在 .env 设置 SCENIC_JWT_SECRET（建议 32 字节以上随机串）");
        } else {
            this.signingKey = new SecretKeySpec(secret.strip().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            log.info("[SECURITY] JWT 签名密钥已加载；用户 token 有效期 {}s，游客凭证 {}s，"
                    + "角色模型 = 无状态签名校验", ttlSeconds, guestTtlSeconds);
        }
    }

    /** 登录用户的 token：携带 userId / username / role */
    public String issueUserToken(Long userId, String username, String role) {
        return build(String.valueOf(userId), username, role, ttlSeconds);
    }

    /**
     * 游客凭证：服务端签发，绑定一个 visitorId。
     * 解决 B5 —— 原实现里 visitorId 完全由前端声明，可伪造读他人跨会话记忆。
     */
    public String issueGuestToken(String visitorId) {
        return build(visitorId, "guest", GUEST_ROLE, guestTtlSeconds);
    }

    private String build(String subject, String username, String role, long ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(subject)
                .issuer(ISSUER)
                .claim("username", username)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttl)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * 校验并解析 token。**无效一律返回 null**（不抛异常，由调用方决定放行还是拒绝）。
     */
    public AuthUser parse(String token) {
        Claims claims = parseClaims(token);
        if (claims == null) {
            return null;
        }
        try {
            Long userId = Long.valueOf(claims.getSubject());
            return new AuthUser(userId, claims.get("username", String.class),
                    claims.get("role", String.class));
        } catch (NumberFormatException e) {
            // subject 不是数字 → 是游客凭证，不是登录用户
            return null;
        }
    }

    /**
     * 解析游客凭证，返回其中的 visitorId；不是游客凭证或校验失败返回 null。
     */
    public String parseGuestVisitorId(String token) {
        Claims claims = parseClaims(token);
        if (claims == null || !GUEST_ROLE.equals(claims.get("role", String.class))) {
            return null;
        }
        return claims.getSubject();
    }

    /** 去掉 "Bearer " 前缀 */
    public String stripBearer(String authorizationHeader) {
        if (authorizationHeader == null) {
            return null;
        }
        String value = authorizationHeader.strip();
        return value.startsWith("Bearer ") ? value.substring(7).strip() : value;
    }

    private Claims parseClaims(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(ISSUER)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            // 过期 / 签名不符 / 格式错误都走这里：debug 级即可，避免刷日志
            log.debug("token 校验失败: {}", e.getMessage());
            return null;
        }
    }
}
