package com.scenic.ai.service;

import com.scenic.ai.dto.ChatRequest;
import com.scenic.ai.dto.ChatResponse;

public interface ChatService {

    ChatResponse chat(ChatRequest request);
}
