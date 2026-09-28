package com.scenic.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.scenic.ai.agent.core.AgentLoop;
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
 * 对话入口（REST）。
 *
 * P7 起改为走 {@link AgentLoop}：由 Agent 自主决定是否检索知识库、是否调工具，
 * 并带最大步数、超时、异常自纠等可靠性保障。
 * （此前是「无条件先检索 → 单次模型调用」。）
 */
@Service
@Slf4j
public class ChatServiceImpl implements ChatService {

    @Autowired
    private AgentLoop agentLoop;

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

        // 3. 交给 Agent 循环：自主检索 / 调工具 / 多步收敛
        AgentLoop.Result result = agentLoop.run(sessionId, request.getMessage());
        log.info("Agent 执行完成：{} 步、{} 次工具调用、收敛={}、终止原因={}",
                result.stepCount(), result.toolCallCount(), result.converged(), result.stopReason());

        String reply = result.answer();

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
