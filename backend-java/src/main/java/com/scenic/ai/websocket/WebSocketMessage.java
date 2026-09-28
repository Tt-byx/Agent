package com.scenic.ai.websocket;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * WebSocket 统一消息协议（纯文本 · Agent 改造后）
 *
 * 客户端 -> 服务端:
 *   - type="text":  文字消息 {type, content, session_id}
 *
 * 服务端 -> 客户端:
 *   - type="text_chunk": 文字片段 {type, content}
 *   - type="done":       完成标记 {type, session_id, total_text}
 *   - type="error":      错误 {type, message}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WebSocketMessage {

    private String type;
    private String content;
    @JsonProperty("session_id")
    private String sessionId;
    private String message;
    @JsonProperty("total_text")
    private String totalText;

    public WebSocketMessage() {}

    public WebSocketMessage(String type) {
        this.type = type;
    }

    // --- 工厂方法：构造发送给客户端的消息 ---

    public static WebSocketMessage textChunk(String content) {
        WebSocketMessage msg = new WebSocketMessage("text_chunk");
        msg.setContent(content);
        return msg;
    }

    public static WebSocketMessage done(String sessionId, String totalText) {
        WebSocketMessage msg = new WebSocketMessage("done");
        msg.setSessionId(sessionId);
        msg.setTotalText(totalText);
        return msg;
    }

    public static WebSocketMessage error(String errorMessage) {
        WebSocketMessage msg = new WebSocketMessage("error");
        msg.setMessage(errorMessage);
        return msg;
    }

    // --- Getters & Setters ---

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getTotalText() { return totalText; }
    public void setTotalText(String totalText) { this.totalText = totalText; }
}
