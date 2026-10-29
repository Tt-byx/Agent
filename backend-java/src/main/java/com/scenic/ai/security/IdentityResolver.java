package com.scenic.ai.security;

import org.springframework.stereotype.Component;

/**
 * 2026-10-29（D05）· 身份解析的**唯一入口**。
 *
 * <p>为什么要有这个类：项目里原本有三处「从请求里判断这是谁」，三处标准不一致 ——
 *
 * <table border="1">
 *   <caption>改造前的三处身份判断</caption>
 *   <tr><th>位置</th><th>依据</th><th>是否可信</th></tr>
 *   <tr><td>{@code ChatServiceImpl#resolveMemoryKey}</td>
 *       <td>SecurityContext → 签名过的 visitorToken</td><td>✅ 可信（Wave 0 已修）</td></tr>
 *   <tr><td>{@code ChatController}（限流）</td>
 *       <td><b>body 里的 visitorId</b> 或 XFF 头</td><td>❌ 客户端随便填</td></tr>
 *   <tr><td>{@code FeedbackController}</td>
 *       <td><b>body 里的 userId</b></td><td>❌ 可冒充他人</td></tr>
 * </table>
 *
 * <p>后果不是「三份代码重复」这么轻，而是**同一个字段在不同链路上被信任程度不同**：
 * {@code visitorId} 在记忆链路已经被明确废弃，却还在给限流当配额键 —— 每次换一个值就是一批新配额。
 *
 * <p>所以这里把判断收敛成一份。原则只有一句：
 * <b>客户端能写的字段是「声明」，不是身份；身份只能来自 SecurityContext 或服务端签名。</b>
 *
 * <p>注意 {@link Identity#memoryKey} **刻意不给游客键加前缀** —— 历史数据在 pgvector 里
 * 就是裸的 {@code guest-xxxx}，加前缀会让存量长期记忆全部读不到。限流键是另一个命名空间，
 * 没有迁移负担，所以用 {@code visitor:} 前缀把三类维度分开，便于日志与指标排查。
 */
@Component
public class IdentityResolver {

    private final JwtService jwtService;

    public IdentityResolver(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    /**
     * 身份快照。两个字段互斥：登录用户 {@code userId != null}，游客 {@code visitorId != null}，
     * 都没有（未登录且未带有效凭证）则两者皆 null。
     */
    public record Identity(Long userId, String visitorId) {

        public boolean isLoggedIn() {
            return userId != null;
        }

        public boolean isGuest() {
            return userId == null && visitorId != null;
        }

        /** 三者皆无 —— 拿不到任何可信身份 */
        public boolean isAnonymous() {
            return userId == null && visitorId == null;
        }

        /**
         * 长期记忆归属键。
         *
         * @param fallback 无可信身份时的退化键（调用方传 sessionId，即「记忆只在本会话内有效」）
         */
        public String memoryKey(String fallback) {
            if (userId != null) {
                return "user:" + userId;
            }
            if (visitorId != null) {
                // 保持历史格式（裸 guest-xxx），不加前缀 —— 否则存量记忆读不到
                return visitorId;
            }
            return fallback;
        }

        /** 内部：user:{id} / visitor:{vid} / null */
        private String scopedKey() {
            if (userId != null) {
                return "user:" + userId;
            }
            if (visitorId != null) {
                return "visitor:" + visitorId;
            }
            return null;
        }

        /** 限流维度键；无可信身份时返回 null，由调用方退化到 IP */
        public String rateLimitKey() {
            return scopedKey();
        }

        /**
         * 幂等作用域；无可信身份时返回 null。
         *
         * <p>2026-10-29（D17）：null 必须表示「**放弃幂等**」，绝不能退化成某个共享默认值。
         * 原实现在 {@code IdempotencyStore#buildKey} 里把 null 映射成字符串 {@code "anonymous"}，
         * 于是「匿名 + 固定 requestId」的两个用户会撞进同一个键，后者直接拿到前者的答案。
         */
        public String idempotencyScope() {
            return scopedKey();
        }

        /** 限流维度标签（仅用于指标 label，取值基数必须很小） */
        public String rateLimitDimension() {
            if (userId != null) {
                return "user";
            }
            return visitorId != null ? "visitor" : "ip";
        }
    }

    /**
     * 解析当前请求的可信身份。
     *
     * @param visitorToken 请求携带的游客凭证（由 {@code POST /api/auth/guest} 签发）。
     *                     只有签名校验通过、且 role=guest 时其中的 visitorId 才会被采信。
     */
    public Identity resolve(String visitorToken) {
        AuthUser current = CurrentUser.get();
        if (current != null) {
            // 已登录：完全忽略客户端声明的任何 visitorId / visitorToken
            return new Identity(current.id(), null);
        }
        return new Identity(null, parseVisitorId(visitorToken));
    }

    /** 便捷方法：一步给出长期记忆归属键（等价于 {@code resolve(token).memoryKey(fallback)}） */
    public String memoryKey(String visitorToken, String fallback) {
        return resolve(visitorToken).memoryKey(fallback);
    }

    /** 只解析游客凭证里的 visitorId，校验失败返回 null */
    public String parseVisitorId(String visitorToken) {
        return jwtService.parseGuestVisitorId(jwtService.stripBearer(visitorToken));
    }
}
