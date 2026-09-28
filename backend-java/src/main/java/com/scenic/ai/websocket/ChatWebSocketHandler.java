package com.scenic.ai.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scenic.ai.agent.ScenicAssistant;
import com.scenic.ai.controller.AuthController;
import com.scenic.ai.entity.ChatMessage;
import com.scenic.ai.entity.Conversation;
import com.scenic.ai.entity.VisitorProfileTag;
import com.scenic.ai.mapper.ChatMessageMapper;
import com.scenic.ai.mapper.ConversationMapper;
import com.scenic.ai.mapper.VisitorProfileTagMapper;
import com.scenic.ai.rag.retrieve.RagRetriever;
import com.scenic.ai.util.SentimentAnalyzer;
import com.scenic.ai.util.TagExtractor;
import dev.langchain4j.service.TokenStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 纯文本对话 WebSocket 处理器（P2：模型调用已从 Python 迁到 JVM 内）。
 *
 * 数字人与语音链路已于 2026-09-28 移除；Python 侧的 SSE 转发已于 P2 移除，
 * 现在由 LangChain4j 的 TokenStream 直接向前端推送 token。
 */
@Component
@Slf4j
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ScenicAssistant scenicAssistant;

    @Autowired
    private RagRetriever ragRetriever;

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Autowired
    private VisitorProfileTagMapper visitorProfileTagMapper;

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
     * 处理文字对话：落库 → LangChain4j 流式生成 → 逐 token 推送前端 → 落库 AI 回复
     */
    private void handleTextChat(WebSocketSession session, WebSocketMessage msg) {
        log.info("handleTextChat 开始处理: {}", msg.getContent().length() > 30
                ? msg.getContent().substring(0, 30) + "..." : msg.getContent());

        CompletableFuture.runAsync(() -> {
            try {
                // 1. 解析 / 创建会话
                String sessionId = msg.getSessionId();
                Long conversationId = null;

                if (sessionId != null && !sessionId.isEmpty()) {
                    Conversation conv = conversationMapper.selectOne(
                            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Conversation>()
                                    .eq(Conversation::getSessionId, sessionId));
                    if (conv != null) {
                        conversationId = conv.getId();
                    }
                }

                if (conversationId == null) {
                    Conversation newConv = new Conversation();
                    newConv.setSessionId(sessionId != null && !sessionId.isEmpty()
                            ? sessionId : UUID.randomUUID().toString());
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

                final String finalSessionId = sessionId;
                final Long finalConversationId = conversationId;
                final Long userId = (Long) session.getAttributes().get("userId");
                final String userContent = msg.getContent();

                // 2. 用户消息落库
                ChatMessage userMsg = new ChatMessage();
                userMsg.setConversationId(finalConversationId);
                userMsg.setRole("user");
                userMsg.setContent(userContent);
                userMsg.setSentiment(SentimentAnalyzer.analyze(userContent));
                userMsg.setCreatedAt(java.time.LocalDateTime.now());
                chatMessageMapper.insert(userMsg);

                // 3. RAG 检索：知识库上下文注入系统提示词（检索失败降级为空上下文）
                String context = ragRetriever.retrieveContext(userContent);

                // 4. 流式生成：JVM 内直接调模型，token 边生成边推送
                StringBuilder buffer = new StringBuilder();
                TokenStream stream = scenicAssistant.chatStream(context, userContent);

                stream.onPartialResponse(token -> {
                            buffer.append(token);
                            sendToClient(session, WebSocketMessage.textChunk(token));
                        })
                        .onCompleteResponse(response -> {
                            String aiReply = buffer.toString();
                            log.info("生成完成, sessionId={}, 长度={}", finalSessionId, aiReply.length());

                            // 4. AI 回复落库
                            if (!aiReply.isEmpty()) {
                                ChatMessage aiMsg = new ChatMessage();
                                aiMsg.setConversationId(finalConversationId);
                                aiMsg.setRole("assistant");
                                aiMsg.setContent(aiReply);
                                aiMsg.setSentiment(SentimentAnalyzer.analyze(aiReply));
                                aiMsg.setCreatedAt(java.time.LocalDateTime.now());
                                chatMessageMapper.insert(aiMsg);
                            }

                            sendToClient(session, WebSocketMessage.done(finalSessionId, aiReply));

                            // 5. 兴趣标签提取
                            if (userId != null) {
                                extractAndUpdateTags(userId, userContent);
                            }
                        })
                        .onError(error -> {
                            log.error("流式生成失败, sessionId={}: {}", finalSessionId, error.getMessage(), error);
                            trySendToClient(session, WebSocketMessage.error("AI 服务暂时不可用: " + error.getMessage()));
                        })
                        .start();

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
