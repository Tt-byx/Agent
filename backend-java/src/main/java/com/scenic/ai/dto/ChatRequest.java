package com.scenic.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChatRequest {

    @NotBlank(message = "消息不能为空")
    private String message;

    private String sessionId;

    /**
     * P8 · 游客标识（可选）。
     *
     * 长期记忆挂在它上面：同一个 visitorId 在**不同会话**里共享偏好记忆，
     * 这才是「跨会话召回」的实现基础。
     * 前端应在 localStorage 里生成并持久化一个 visitorId（UUID），每次请求带上；
     * 未传时后端退化为用 sessionId（此时记忆只在本会话内有效）。
     */
    private String visitorId;
}
