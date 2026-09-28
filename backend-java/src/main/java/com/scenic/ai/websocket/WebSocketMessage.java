package com.scenic.ai.websocket;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * WebSocket 统一消息协议
 *
 * 客户端 -> 服务端:
 *   - type="text":  文字消息 {type, content, session_id}
 *   - type="audio": 音频消息 {type, format, session_id} + 二进制帧
 *
 * 服务端 -> 客户端:
 *   - type="text_chunk":  文字片段 {type, content}
 *   - type="audio_chunk": 音频片段 {type, audio(base64), format}
 *   - type="asr_result":  ASR识别结果 {type, text}
 *   - type="done":        完成标记 {type, session_id, total_text}
 *   - type="error":       错误 {type, message}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WebSocketMessage {

    private String type;
    private String content;
    @JsonProperty("session_id")
    private String sessionId;
    private String format;
    private String audio;      // Base64 编码的音频
    private String text;
    private String message;
    @JsonProperty("total_text")
    private String totalText;
    private String expression;  // 数字人表情

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

    public static WebSocketMessage audioChunk(String audioBase64, String format) {
        WebSocketMessage msg = new WebSocketMessage("audio_chunk");
        msg.setAudio(audioBase64);
        msg.setFormat(format);
        return msg;
    }

    public static WebSocketMessage asrResult(String text) {
        WebSocketMessage msg = new WebSocketMessage("asr_result");
        msg.setText(text);
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

    public static WebSocketMessage expression(String expression) {
        WebSocketMessage msg = new WebSocketMessage("expression");
        msg.setExpression(expression);
        return msg;
    }

    // --- Getters & Setters ---

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }

    public String getAudio() { return audio; }
    public void setAudio(String audio) { this.audio = audio; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getTotalText() { return totalText; }
    public void setTotalText(String totalText) { this.totalText = totalText; }

    public String getExpression() { return expression; }
    public void setExpression(String expression) { this.expression = expression; }
}
