package com.scenic.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.scenic.ai.agent.ScenicAssistant;
import com.scenic.ai.dto.ChatRequest;
import com.scenic.ai.dto.ChatResponse;
import com.scenic.ai.entity.ChatMessage;
import com.scenic.ai.entity.Conversation;
import com.scenic.ai.mapper.ChatMessageMapper;
import com.scenic.ai.mapper.ConversationMapper;
import com.scenic.ai.service.ChatService;
import com.scenic.ai.util.SentimentAnalyzer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 对话入口（P2 之后不再转发 Python）。
 * 当前是「单轮模型调用」；P3 起在调用前挂 RAG 检索，P7 起换成 Agent 循环。
 */
@Service
@Slf4j
public class ChatServiceImpl implements ChatService {

    @Autowired
    private ScenicAssistant scenicAssistant;

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Override
    public ChatResponse chat(ChatRequest request) {
        // 1. 处理会话ID
        String sessionId = request.getSessionId();
        Long conversationId;

        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
            Conversation conversation = new Conversation();
            conversation.setSessionId(sessionId);
            String title = request.getMessage();
            conversation.setTitle(title.substring(0, Math.min(20, title.length())));
            conversationMapper.insert(conversation);
            conversationId = conversation.getId();
        } else {
            Conversation conversation = conversationMapper.selectOne(
                    new LambdaQueryWrapper<Conversation>()
                            .eq(Conversation::getSessionId, sessionId)
            );
            if (conversation == null) {
                Conversation newConv = new Conversation();
                newConv.setSessionId(sessionId);
                String title = request.getMessage();
                newConv.setTitle(title.substring(0, Math.min(20, title.length())));
                conversationMapper.insert(newConv);
                conversationId = newConv.getId();
            } else {
                conversationId = conversation.getId();
            }
        }

        // 2. 保存用户消息（含情感分析）
        ChatMessage userMsg = new ChatMessage();
        userMsg.setConversationId(conversationId);
        userMsg.setRole("user");
        userMsg.setContent(request.getMessage());
        userMsg.setSentiment(SentimentAnalyzer.analyze(request.getMessage()));
        chatMessageMapper.insert(userMsg);

        // 3. 调用 JVM 内的大模型（LangChain4j），不再走 Python
        String reply;
        try {
            reply = scenicAssistant.chat(request.getMessage());
            if (reply == null || reply.isBlank()) {
                log.warn("模型返回空内容, sessionId={}", sessionId);
                reply = "抱歉，我这边没有组织出合适的回答，能换个问法再试一次吗？";
            }
        } catch (Exception e) {
            log.error("大模型调用失败, sessionId={}: {}", sessionId, e.getMessage(), e);
            reply = "抱歉，AI 服务暂时不可用，请稍后再试。";
        }

        // 4. 保存 AI 回复（含情感分析）
        ChatMessage aiMsg = new ChatMessage();
        aiMsg.setConversationId(conversationId);
        aiMsg.setRole("assistant");
        aiMsg.setContent(reply);
        aiMsg.setSentiment(SentimentAnalyzer.analyze(reply));
        chatMessageMapper.insert(aiMsg);

        return new ChatResponse(reply, sessionId);
    }
}
