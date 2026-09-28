package com.scenic.ai.controller;

import com.scenic.ai.dto.ChatRequest;
import com.scenic.ai.dto.ChatResponse;
import com.scenic.ai.dto.Result;
import com.scenic.ai.gateway.RateLimiter;
import com.scenic.ai.service.ChatService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 对话入口。
 *
 * P8 · 在进入业务前做限流：优先用 visitorId（业务身份），没有就用客户端 IP。
 * 限流的目的是保护模型调用预算，不是惩罚用户 —— 所以被限流时返回明确提示而不是 500。
 */
@RestController
@RequestMapping("/api/chat")
@Slf4j
public class ChatController {

    @Autowired
    private ChatService chatService;

    @Autowired
    private RateLimiter rateLimiter;

    @PostMapping
    public Result<ChatResponse> chat(@RequestBody @Valid ChatRequest request,
                                     HttpServletRequest httpRequest) {
        String key = (request.getVisitorId() != null && !request.getVisitorId().isBlank())
                ? request.getVisitorId()
                : clientIp(httpRequest);

        if (!rateLimiter.allow(key)) {
            log.info("[RATE] 拒绝请求：key={}", key);
            return Result.error(429, "您的提问有点太频繁了，请休息一下再问～");
        }

        ChatResponse response = chatService.chat(request);
        return Result.success(response);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].strip();
        }
        return request.getRemoteAddr();
    }
}
