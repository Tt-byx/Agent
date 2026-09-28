package com.scenic.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.scenic.ai.agent.ScenicAssistant;
import com.scenic.ai.dto.ChatRequest;
import com.scenic.ai.dto.ChatResponse;
import com.scenic.ai.entity.ChatMessage;
import com.scenic.ai.entity.Conversation;
import com.scenic.ai.mapper.ChatMessageMapper;
import com.scenic.ai.mapper.ConversationMapper;
import com.scenic.ai.rag.retrieve.RagRetriever;
import com.scenic.ai.service.ChatService;
import com.scenic.ai.util.SentimentAnalyzer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 对话入口（P2 起不再转发 Python，P3 起接入 RAG）。
 *
 * 当前链路：检索知识库 → 上下文注入系统提示词 → 模型生成。
 * P7 会把这里换成 Agent 循环（可自主决定是否检索、是否调工具）。
 */
@Service
@Slf4j
public class ChatServiceImpl implements ChatService {

    @Autowired
    private ScenicAssistant scenicAssistant;

    @Autowired
    private RagRetriever ragRetriever;

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

        // 3. RAG 检索：把知识库上下文注入系统提示词（检索失败会降级为空上下文）
        String context = ragRetriever.retrieveContext(request.getMessage());

        // 4. 调用 JVM 内的大模型（LangChain4j）
        String reply;
        try {
            reply = scenicAssistant.chat(context, request.getMessage());
            if (reply == null || reply.isBlank()) {
                log.warn("模型返回空内容, sessionId={}", sessionId);
                reply = "抱歉，我这边没有组织出合适的回答，能换个问法再试一次吗？";
            }
        } catch (Exception e) {
            log.error("大模型调用失败, sessionId={}: {}", sessionId, e.getMessage(), e);
            reply = "抱歉，AI 服务暂时不可用，请稍后再试。";
        }

        // 5. 保存 AI 回复（含情感分析）
        ChatMessage aiMsg = new ChatMessage();
        aiMsg.setConversationId(conversationId);
        aiMsg.setRole("assistant");
        aiMsg.setContent(reply);
        aiMsg.setSentiment(SentimentAnalyzer.analyze(reply));
        chatMessageMapper.insert(aiMsg);

        return new ChatResponse(reply, sessionId);
    }
}
