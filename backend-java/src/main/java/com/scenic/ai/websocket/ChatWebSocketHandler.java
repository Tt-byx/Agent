package com.scenic.ai.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scenic.ai.controller.AuthController;
import com.scenic.ai.entity.ChatMessage;
import com.scenic.ai.entity.Conversation;
import com.scenic.ai.entity.VisitorProfileTag;
import com.scenic.ai.mapper.ChatMessageMapper;
import com.scenic.ai.mapper.ConversationMapper;
import com.scenic.ai.mapper.VisitorProfileTagMapper;
import com.scenic.ai.util.SentimentAnalyzer;
import com.scenic.ai.util.TagExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 纯文本对话 WebSocket 处理器（Agent 改造后）。
 * 数字人与语音链路已于 2026-09-28 移除，仅保留文字流式对话。
 */
@Component
@Slf4j
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Autowired
    private VisitorProfileTagMapper visitorProfileTagMapper;

    @Value("${python.backend.url:http://localhost:8000}")
    private String pythonBackendUrl;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);

        // 解析 token 参数获取 userId
        String query = session.getUri() != null ? session.getUri().getQuery() : null;
        if (query != null) {
            for (String param : query.split("&")) {
                if (param.startsWith("token=")) {
                    String token = param.substring(6);
                    Long userId = AuthController.getUserIdFromToken(token);
                    if (userId != null) {
                        session.getAttributes().put("userId", userId);
                    }
                    break;
                }
            }
        }
        log.info("WebSocket 连接建立: {}, userId={}", session.getId(), session.getAttributes().get("userId"));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        log.debug("收到文本消息: {}", payload.length() > 200 ? payload.substring(0, 200) + "..." : payload);

        try {
            WebSocketMessage msg = objectMapper.readValue(payload, WebSocketMessage.class);

            if ("text".equals(msg.getType())) {
                handleTextChat(session, msg);
            } else {
                log.warn("未知消息类型: {}", msg.getType());
                sendToClient(session, WebSocketMessage.error("未知消息类型: " + msg.getType()));
            }
        } catch (Exception e) {
            log.error("解析消息失败: {}", e.getMessage(), e);
            trySendToClient(session, WebSocketMessage.error("消息格式错误"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        log.info("WebSocket 连接关闭: {}", session.getId());
    }

    /**
     * 流式读取 SSE 响应，逐行解析并转发给前端
     * @return done 事件中的 total_text（AI 完整回复），用于保存数据库
     */
    private String streamPythonSSE(WebSocketSession session, String endpoint, Map<String, String> formFields) {
        String totalText = "";
        java.net.HttpURLConnection conn = null;
        String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
        try {
            java.net.URL url = new java.net.URL(pythonBackendUrl + endpoint);
            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(120000);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setChunkedStreamingMode(8192);

            // 构建 multipart body（仅表单字段）
            try (var os = conn.getOutputStream();
                 var writer = new java.io.OutputStreamWriter(os, java.nio.charset.StandardCharsets.UTF_8)) {
                for (Map.Entry<String, String> entry : formFields.entrySet()) {
                    if (entry.getValue() == null) continue;
                    writer.write("--" + boundary + "\r\n");
                    writer.write("Content-Disposition: form-data; name=\"" + entry.getKey() + "\"\r\n\r\n");
                    writer.write(entry.getValue() + "\r\n");
                    writer.flush();
                }
                writer.write("--" + boundary + "--\r\n");
                writer.flush();
            }

            // 检查响应状态码
            int responseCode = conn.getResponseCode();
            if (responseCode >= 400) {
                java.io.InputStream errorStream = conn.getErrorStream();
                if (errorStream != null) {
                    String errorBody = new String(errorStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    log.error("Python 返回错误 {}: {}", responseCode, errorBody);
                    trySendToClient(session, WebSocketMessage.error("AI 服务错误: " + responseCode));
                }
                return totalText;
            }

            // 流式读取 SSE 响应
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String currentEvent = "";
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    if (line.startsWith("event: ")) {
                        currentEvent = line.substring(7).trim();
                    } else if (line.startsWith("data: ")) {
                        String data = line.substring(6).trim();
                        try {
                            switch (currentEvent) {
                                case "text_chunk":
                                    Map<String, Object> textData = objectMapper.readValue(data, Map.class);
                                    sendToClient(session, WebSocketMessage.textChunk((String) textData.get("text")));
                                    break;
                                case "done":
                                    Map<String, Object> doneData = objectMapper.readValue(data, Map.class);
                                    totalText = (String) doneData.getOrDefault("total_text", "");
                                    sendToClient(session, WebSocketMessage.done(
                                            (String) doneData.get("session_id"),
                                            totalText
                                    ));
                                    break;
                                case "error":
                                    Map<String, Object> errData = objectMapper.readValue(data, Map.class);
                                    sendToClient(session, WebSocketMessage.error((String) errData.get("message")));
                                    break;
                                default:
                                    break;
                            }
                        } catch (Exception e) {
                            log.error("解析 SSE 数据失败: {}", data, e);
                        }
                    }
                }
            }
        } catch (java.net.SocketTimeoutException e) {
            log.error("Python SSE 流读取超时: {}", e.getMessage());
            trySendToClient(session, WebSocketMessage.error("AI 服务响应超时"));
        } catch (Exception e) {
            log.error("Python SSE 流读取失败: {}", e.getMessage(), e);
            trySendToClient(session, WebSocketMessage.error("AI 服务暂时不可用"));
        } finally {
            if (conn != null) conn.disconnect();
        }
        return totalText;
    }

    /**
     * 处理文字对话：调 Python /api/chat/stream (multipart)，流式转发给前端
     */
    private void handleTextChat(WebSocketSession session, WebSocketMessage msg) {
        log.info("handleTextChat 开始处理: {}", msg.getContent().length() > 30
                ? msg.getContent().substring(0, 30) + "..." : msg.getContent());
        CompletableFuture.runAsync(() -> {
            try {
                // 1. 保存会话和用户消息到数据库
                String sessionId = msg.getSessionId();
                Long conversationId = null;

                if (sessionId != null && !sessionId.isEmpty()) {
                    com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Conversation> wrapper =
                            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Conversation>()
                                    .eq(Conversation::getSessionId, sessionId);
                    Conversation conv = conversationMapper.selectOne(wrapper);
                    if (conv != null) {
                        conversationId = conv.getId();
                    }
                }

                if (conversationId == null) {
                    Conversation newConv = new Conversation();
                    newConv.setSessionId(sessionId != null ? sessionId : java.util.UUID.randomUUID().toString());
                    newConv.setTitle(generateTitle(msg.getContent()));
                    Object userIdObj = session.getAttributes().get("userId");
                    if (userIdObj instanceof Long) {
                        newConv.setUserId((Long) userIdObj);
                    }
                    newConv.setStatus(1);
                    newConv.setCreatedAt(java.time.LocalDateTime.now());
                    newConv.setUpdatedAt(java.time.LocalDateTime.now());
                    conversationMapper.insert(newConv);
                    conversationId = newConv.getId();
                    sessionId = newConv.getSessionId();
                }

                ChatMessage userMsg = new ChatMessage();
                userMsg.setConversationId(conversationId);
                userMsg.setRole("user");
                userMsg.setContent(msg.getContent());
                userMsg.setSentiment(SentimentAnalyzer.analyze(msg.getContent()));
                userMsg.setCreatedAt(java.time.LocalDateTime.now());
                chatMessageMapper.insert(userMsg);

                // 2. 构建表单字段，流式调 Python /api/chat/stream
                Map<String, String> formFields = new java.util.LinkedHashMap<>();
                formFields.put("message", msg.getContent());
                formFields.put("session_id", sessionId != null ? sessionId : "");

                // 流式读取 SSE，边收边转发给前端，返回 AI 完整回复
                String aiReply = streamPythonSSE(session, "/api/chat/stream", formFields);

                // 3. 保存 AI 回复到数据库
                if (aiReply != null && !aiReply.isEmpty()) {
                    ChatMessage aiMsg = new ChatMessage();
                    aiMsg.setConversationId(conversationId);
                    aiMsg.setRole("assistant");
                    aiMsg.setContent(aiReply);
                    aiMsg.setSentiment(SentimentAnalyzer.analyze(aiReply));
                    aiMsg.setCreatedAt(java.time.LocalDateTime.now());
                    chatMessageMapper.insert(aiMsg);
                }

                // 4. 提取用户兴趣标签（从用户消息中）
                Long uid = (Long) session.getAttributes().get("userId");
                if (uid != null) {
                    extractAndUpdateTags(uid, msg.getContent());
                }

            } catch (Exception e) {
                log.error("[ERROR] 文字对话失败: {}", e.getMessage(), e);
                trySendToClient(session, WebSocketMessage.error("AI服务暂时不可用: " + e.getMessage()));
            } catch (Throwable t) {
                log.error("[FATAL] 文字对话严重错误: {}", t.getMessage(), t);
                trySendToClient(session, WebSocketMessage.error("系统错误"));
            }
        }, executor).exceptionally(ex -> {
            log.error("[ASYNC] CompletableFuture 未捕获异常: {}", ex.getMessage(), ex);
            trySendToClient(session, WebSocketMessage.error("异步处理错误"));
            return null;
        });
    }

    /**
     * 从用户消息中提取兴趣标签并更新数据库
     */
    private void extractAndUpdateTags(Long userId, String userMessage) {
        try {
            Map<String, Integer> tags = TagExtractor.extractTags(userMessage);
            for (Map.Entry<String, Integer> entry : tags.entrySet()) {
                String tagName = entry.getKey();
                int score = entry.getValue();
                com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<VisitorProfileTag> wrapper =
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<VisitorProfileTag>()
                                .eq(VisitorProfileTag::getUserId, userId)
                                .eq(VisitorProfileTag::getTagName, tagName);
                VisitorProfileTag existing = visitorProfileTagMapper.selectOne(wrapper);
                if (existing != null) {
                    existing.setTagScore(existing.getTagScore() + score);
                    existing.setUpdatedAt(java.time.LocalDateTime.now());
                    visitorProfileTagMapper.updateById(existing);
                } else {
                    VisitorProfileTag newTag = new VisitorProfileTag();
                    newTag.setUserId(userId);
                    newTag.setTagName(tagName);
                    newTag.setTagScore(score);
                    newTag.setCreatedAt(java.time.LocalDateTime.now());
                    newTag.setUpdatedAt(java.time.LocalDateTime.now());
                    visitorProfileTagMapper.insert(newTag);
                }
            }
        } catch (Exception e) {
            log.warn("标签提取失败: {}", e.getMessage());
        }
    }

    /**
     * 发送消息给客户端
     */
    private void sendToClient(WebSocketSession session, WebSocketMessage msg) {
        try {
            String json = objectMapper.writeValueAsString(msg);
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (IOException e) {
            log.error("发送消息给客户端失败: {}", e.getMessage());
        }
    }

    /**
     * 安全发送（捕获异常不抛出）
     */
    private void trySendToClient(WebSocketSession session, WebSocketMessage msg) {
        try {
            sendToClient(session, msg);
        } catch (Exception e) {
            log.error("发送消息异常: {}", e.getMessage());
        }
    }

    /**
     * 从用户消息生成对话标题（10-20字）
     */
    private String generateTitle(String content) {
        if (content == null || content.isEmpty()) return "新对话";
        String clean = content.replaceAll("[\\s\\n\\r]+", "").trim();
        if (clean.length() <= 16) return clean;
        return clean.substring(0, 16) + "...";
    }
}
