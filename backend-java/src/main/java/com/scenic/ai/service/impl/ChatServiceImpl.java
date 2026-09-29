package com.scenic.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.scenic.ai.agent.core.AgentLoop;
import com.scenic.ai.agent.multi.MultiAgentOrchestrator;
import com.scenic.ai.dto.ChatRequest;
import com.scenic.ai.dto.ChatResponse;
import com.scenic.ai.entity.ChatMessage;
import com.scenic.ai.entity.Conversation;
import com.scenic.ai.exception.ApiException;
import com.scenic.ai.mapper.ChatMessageMapper;
import com.scenic.ai.mapper.ConversationMapper;
import com.scenic.ai.security.AuthUser;
import com.scenic.ai.security.CurrentUser;
import com.scenic.ai.security.JwtService;
import com.scenic.ai.service.ChatService;
import com.scenic.ai.util.SentimentAnalyzer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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
    private MultiAgentOrchestrator multiAgentOrchestrator;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Override
    public ChatResponse chat(ChatRequest request) {
        Long currentUserId = CurrentUser.id();   // 游客未登录时为 null

        // 1. 处理会话ID（Wave 0：增加归属校验与 userId 回填）
        String sessionId = request.getSessionId();
        Long conversationId;

        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
            conversationId = createConversation(sessionId, request.getMessage(), currentUserId);
        } else {
            Conversation conversation = conversationMapper.selectOne(
                    new LambdaQueryWrapper<Conversation>()
                            .eq(Conversation::getSessionId, sessionId)
            );
            if (conversation == null) {
                conversationId = createConversation(sessionId, request.getMessage(), currentUserId);
            } else {
                assertConversationOwnership(conversation, currentUserId);
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

        // 3. 交给 Agent：默认单 Agent（ReAct）；开启 agent.multi 后走 Supervisor 编排
        //    Wave 0：隔离键不再信任客户端声明的 visitorId，见 resolveMemoryKey
        String memoryKey = resolveMemoryKey(request, sessionId);
        AgentLoop.Result result = multiAgentOrchestrator.isEnabled()
                ? multiAgentOrchestrator.orchestrate(sessionId, memoryKey, request.getMessage())
                : agentLoop.run(sessionId, memoryKey, request.getMessage());
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

    private Long createConversation(String sessionId, String message, Long userId) {
        Conversation conversation = new Conversation();
        conversation.setSessionId(sessionId);
        // Wave 0 · 修 B8：REST 链路原先不回填 userId，导致会话在「我的会话」里查不到
        conversation.setUserId(userId);
        conversation.setTitle(message.substring(0, Math.min(20, message.length())));
        conversationMapper.insert(conversation);
        return conversation.getId();
    }

    /**
     * Wave 0 · 会话归属校验。
     *
     * 修复的问题：sessionId 由客户端提供且无归属检查，枚举 sessionId 即可往他人会话里写消息。
     * 规则：
     *   - 会话已归属他人 → 403；
     *   - 历史无主会话（改造前 REST 建的 userId 为空）→ 首次由登录用户继续时回填归属，
     *     避免历史数据全部作废。
     */
    private void assertConversationOwnership(Conversation conversation, Long currentUserId) {
        Long owner = conversation.getUserId();
        if (owner == null) {
            if (currentUserId != null) {
                conversation.setUserId(currentUserId);
                conversation.setUpdatedAt(LocalDateTime.now());
                conversationMapper.updateById(conversation);
            }
            return;
        }
        if (!owner.equals(currentUserId)) {
            throw ApiException.forbidden("该会话不属于当前账号");
        }
    }

    /**
     * Wave 0 · 隔离键解析（修 B5：原先直接采用客户端声明的 visitorId，可伪造读他人长期记忆）。
     *
     * 三级策略：
     *   1. 已登录 → user:{id}，**完全忽略**客户端声明；
     *   2. 游客 → 只认服务端签发凭证（/api/auth/guest）里的 visitorId；
     *   3. 无有效凭证 → 退化为会话级隔离：拿不到跨会话记忆，但**也读不到别人的数据**（安全默认）。
     */
    private String resolveMemoryKey(ChatRequest request, String sessionId) {
        AuthUser current = CurrentUser.get();
        if (current != null) {
            return current.memoryKey();
        }
        String visitorId = jwtService.parseGuestVisitorId(
                jwtService.stripBearer(request.getVisitorToken()));
        if (visitorId != null) {
            return visitorId;
        }
        return sessionId;
    }
}
