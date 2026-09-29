package com.scenic.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.scenic.ai.agent.core.AgentLoop;
import com.scenic.ai.agent.multi.MultiAgentOrchestrator;
import com.scenic.ai.dto.ChatRequest;
import com.scenic.ai.dto.ChatResponse;
import com.scenic.ai.entity.ChatMessage;
import com.scenic.ai.entity.Conversation;
import com.scenic.ai.exception.ApiException;
import com.scenic.ai.gateway.IdempotencyStore;
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
    private IdempotencyStore idempotencyStore;

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Override
    public ChatResponse chat(ChatRequest request) {
        Long currentUserId = CurrentUser.id();   // 游客未登录时为 null

        // ── Wave 4 · 幂等：同一身份 + 同一 requestId 只真正执行一次 ──
        // 作用域用「服务端认定的身份」而不是请求里声明的 visitorId，避免 A 用 B 的 requestId 拿到 B 的结果。
        String idemKey = null;
        if (request.getRequestId() != null && !request.getRequestId().isBlank()) {
            String identity;
            if (currentUserId != null) {
                identity = "user:" + currentUserId;
            } else {
                String guestId = jwtService.parseGuestVisitorId(
                        jwtService.stripBearer(request.getVisitorToken()));
                identity = guestId != null ? guestId : "anonymous";
            }
            idemKey = idempotencyStore.buildKey(identity, request.getRequestId());

            ChatResponse firstResult = idempotencyStore.find(idemKey);
            if (firstResult != null) {
                log.info("[IDEMPOTENT] requestId={} 已处理过，直接返回首次结果（不再调用模型）",
                        request.getRequestId());
                return firstResult;
            }
            if (!idempotencyStore.tryLock(idemKey)) {
                // 已有同请求在处理中：明确拒绝，而不是并发重复跑一遍
                throw ApiException.conflict("相同的请求正在处理中，请勿重复提交");
            }
        }

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

        ChatResponse response = new ChatResponse(reply, sessionId);
        if (idemKey != null) {
            // 成功：落幂等结果并释放占位锁。
            // 失败路径不落结果，锁由 TTL（默认 60s）自动过期，客户端可安全重试。
            idempotencyStore.saveAndUnlock(idemKey, response);
        }
        return response;
    }

    /**
     * Wave 5 · 流式对话。会话处理、归属校验、落库与非流式**完全共用**，
     * 差别只有「答案怎么产出」——由 {@code AgentLoop.runStreaming} 以增量回调给出。
     *
     * 注意：流式路径**不接入幂等** —— SSE 的响应是增量流，无法像整段响应那样缓存复用；
     * 防重复提交仍由限流与前端控制（这一点写进 ADR，避免被误认为遗漏）。
     */
    @Override
    public void chatStream(ChatRequest request, AgentLoop.StreamSink sink) {
        Long currentUserId = CurrentUser.id();

        String sessionId = request.getSessionId();
        Long conversationId;
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
            conversationId = createConversation(sessionId, request.getMessage(), currentUserId);
        } else {
            Conversation conversation = conversationMapper.selectOne(
                    new LambdaQueryWrapper<Conversation>()
                            .eq(Conversation::getSessionId, sessionId));
            if (conversation == null) {
                conversationId = createConversation(sessionId, request.getMessage(), currentUserId);
            } else {
                assertConversationOwnership(conversation, currentUserId);
                conversationId = conversation.getId();
            }
        }

        ChatMessage userMsg = new ChatMessage();
        userMsg.setConversationId(conversationId);
        userMsg.setRole("user");
        userMsg.setContent(request.getMessage());
        userMsg.setSentiment(SentimentAnalyzer.analyze(request.getMessage()));
        chatMessageMapper.insert(userMsg);

        String memoryKey = resolveMemoryKey(request, sessionId);
        AgentLoop.Result result = agentLoop.runStreaming(sessionId, memoryKey, request.getMessage(), sink);

        ChatMessage aiMsg = new ChatMessage();
        aiMsg.setConversationId(conversationId);
        aiMsg.setRole("assistant");
        aiMsg.setContent(result.answer());
        aiMsg.setSentiment(SentimentAnalyzer.analyze(result.answer()));
        chatMessageMapper.insert(aiMsg);

        log.info("[STREAM] 对话完成：{} 步、{} 次工具调用、收敛={}、终止原因={}",
                result.stepCount(), result.toolCallCount(), result.converged(), result.stopReason());
    }

    private Long createConversation(String sessionId, String message, Long userId) {
        Conversation conversation = new Conversation();
        conversation.setSessionId(sessionId);
        // Wave 0 · 修 B8：REST 链路原先不回填 userId，导致会话在「我的会话」里查不到
        conversation.setUserId(userId);
        conversation.setTitle(message.substring(0, Math.min(20, message.length())));
        try {
            conversationMapper.insert(conversation);
            return conversation.getId();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // Wave 2 · B9：并发下两个请求可能同时判定「会话不存在」而各自创建。
            // 表上已有唯一索引 uk_session_id 兜底，这里让冲突方**复用已存在的会话**，
            // 而不是把 500 抛给游客（原实现就是后者）。
            Conversation existing = conversationMapper.selectOne(
                    new LambdaQueryWrapper<Conversation>().eq(Conversation::getSessionId, sessionId));
            if (existing == null) {
                throw e;
            }
            log.info("[CONVERSATION] session_id={} 并发创建冲突，复用已存在会话 id={}",
                    sessionId, existing.getId());
            return existing.getId();
        }
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
