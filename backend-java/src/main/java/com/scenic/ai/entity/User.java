package com.scenic.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 账号。
 *
 * <p>字段语义以 {@code resources/schema.sql} 为准，这里复述容易踩坑的两点：
 * 密码的存储形态，以及 {@code status} 到底能拦住什么。
 */
@Data
@TableName("user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /**
     * BCrypt 哈希（形如 {@code $2a$10$...}）。
     *
     * <p>历史数据里可能存在**明文密码** —— {@code AuthController#passwordMatchesAndMaybeUpgrade}
     * 对它们做了渐进式升级：登录成功时自动改写为哈希，不打断存量账号。
     * 因此任何「判断密码形态」的新代码都必须兼容这个过渡期，不能假设这里一定是 BCrypt。
     */
    private String password;

    private String nickname;

    private String avatarUrl;

    /** 角色：{@code admin} / {@code visitor}。Spring Security 的 {@code hasRole("ADMIN")} 依赖它 */
    private String role;

    private String phone;

    /**
     * 1 = 正常，0 = 禁用。
     *
     * <p>登录时校验（{@code AuthController#login} 里 {@code status == 0} 直接返回 403）。
     *
     * <p><b>注意：禁用只影响「下次登录」。</b> 已签发的 JWT 在有效期内仍然可用，
     * 因为 {@code JwtAuthFilter} 只验签名、不查库。要「立刻踢下线」需要额外的
     * token 黑名单，或者缩短 TTL + refresh token —— 前者会破坏无状态扩容，
     * 这个取舍目前**没有做**，属于已知边界而不是遗漏。
     */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
