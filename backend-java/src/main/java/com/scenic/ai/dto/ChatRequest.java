package com.scenic.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 对话请求。
 *
 * Wave 0 起增加输入侧约束（路线图「标准之外的补充项」）：
 * 原先只有 {@code @NotBlank}，用户可提交超长文本 → 直接打爆上下文与 token 预算。
 */
@Data
public class ChatRequest {

    @NotBlank(message = "消息不能为空")
    @Size(max = 2000, message = "单次提问最长 2000 字")
    private String message;

    /** 会话 ID：限定字符集，避免异常字符进入 Redis key 与查询条件 */
    @Size(max = 64, message = "sessionId 最长 64 字符")
    @Pattern(regexp = "^[A-Za-z0-9_.:-]{0,64}$",
            message = "sessionId 只允许字母、数字、下划线、点、冒号与短横线")
    private String sessionId;

    /**
     * 游客标识（P8 引入，**Wave 0 起不再被服务端信任**）。
     *
     * 原实现直接把它当长期记忆的归属键，导致任何人声明别人的 visitorId
     * 就能读到对方的跨会话记忆（路线图 B5）。现仅作为兼容字段保留：
     * 服务端只认 {@link #visitorToken} 里的 visitorId，或在无凭证时退化为会话级隔离。
     *
     * @deprecated 请改用 {@code POST /api/auth/guest} 获取 visitorToken
     */
    @Deprecated
    @Size(max = 64, message = "visitorId 最长 64 字符")
    private String visitorId;

    /**
     * Wave 0 · 服务端签发的游客凭证（JWT）。
     *
     * 由 {@code POST /api/auth/guest} 签发，前端存 localStorage 后随每次对话携带。
     * 校验通过后，其中的 visitorId 才作为长期记忆的归属键 —— 客户端无法伪造他人身份。
     * 未携带或校验失败时：记忆退化为「仅本会话有效」，而不是去读一个可伪造的键。
     */
    @Size(max = 1024, message = "visitorToken 过长")
    private String visitorToken;
}
