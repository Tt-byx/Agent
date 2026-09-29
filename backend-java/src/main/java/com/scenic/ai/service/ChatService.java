package com.scenic.ai.service;

import com.scenic.ai.agent.core.AgentLoop;
import com.scenic.ai.dto.ChatRequest;
import com.scenic.ai.dto.ChatResponse;

public interface ChatService {

    ChatResponse chat(ChatRequest request);

    /**
     * Wave 5 · 流式对话（供 SSE 端点调用）。
     *
     * 与会话/落库逻辑与非流式完全一致，只是把「最终答案」以增量形式
     * 通过 {@link AgentLoop.StreamSink} 实时推给调用方。
     */
    void chatStream(ChatRequest request, AgentLoop.StreamSink sink);
}
