package com.scenic.ai.controller;

import com.scenic.ai.dto.ChatRequest;
import com.scenic.ai.dto.ChatResponse;
import com.scenic.ai.dto.Result;
import com.scenic.ai.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    @Autowired
    private ChatService chatService;

    /**
     * 文字聊天接口（Phase 0 核心接口）
     */
    @PostMapping
    public Result<ChatResponse> chat(@RequestBody @Valid ChatRequest request) {
        ChatResponse response = chatService.chat(request);
        return Result.success(response);
    }
}
