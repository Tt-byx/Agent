package com.scenic.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class ChatServiceImpl implements ChatService {

    @Value("${python.backend.url:http://localhost:8000}")
    private String pythonBackendUrl;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Override
    @SuppressWarnings("unchecked")
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
                // 会话不存在，创建新的
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

        // 3. 调用 Python 后端
        Map<String, String> pythonRequest = new HashMap<>();
        pythonRequest.put("message", request.getMessage());

        try {
            Map<String, Object> pythonResponse = restTemplate.postForObject(
                    pythonBackendUrl + "/api/chat",
                    pythonRequest,
                    Map.class
            );

            String reply = (String) pythonResponse.get("reply");

            // 4. 保存 AI 回复（含情感分析）
            ChatMessage aiMsg = new ChatMessage();
            aiMsg.setConversationId(conversationId);
            aiMsg.setRole("assistant");
            aiMsg.setContent(reply);
            aiMsg.setSentiment(SentimentAnalyzer.analyze(reply));
            chatMessageMapper.insert(aiMsg);

            return new ChatResponse(reply, sessionId);
        } catch (Exception e) {
            log.error("调用Python后端失败: {}", e.getMessage());
            return new ChatResponse("抱歉，AI服务暂时不可用，请稍后再试。", sessionId);
        }
    }
}
