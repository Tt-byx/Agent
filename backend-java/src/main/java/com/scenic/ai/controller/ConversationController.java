package com.scenic.ai.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.scenic.ai.dto.Result;
import com.scenic.ai.entity.ChatMessage;
import com.scenic.ai.entity.Conversation;
import com.scenic.ai.mapper.ChatMessageMapper;
import com.scenic.ai.mapper.ConversationMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    /** 获取当前用户的所有对话（按更新时间倒序） */
    @GetMapping
    public Result<List<Conversation>> list(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        Long userId = AuthController.getUserIdFromToken(extractToken(auth));
        if (userId == null) return Result.error(401, "未登录");

        List<Conversation> conversations = conversationMapper.selectList(
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getUserId, userId)
                        .eq(Conversation::getStatus, 1)
                        .orderByDesc(Conversation::getUpdatedAt));
        return Result.success(conversations);
    }

    /** 获取指定对话的全部消息 */
    @GetMapping("/{id}/messages")
    public Result<List<ChatMessage>> messages(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        Long userId = AuthController.getUserIdFromToken(extractToken(auth));
        if (userId == null) return Result.error(401, "未登录");

        Conversation conv = conversationMapper.selectById(id);
        if (conv == null || !userId.equals(conv.getUserId())) {
            return Result.error(403, "无权访问");
        }

        List<ChatMessage> messages = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getConversationId, id)
                        .orderByAsc(ChatMessage::getCreatedAt));
        return Result.success(messages);
    }

    /** 删除对话（软删除） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        Long userId = AuthController.getUserIdFromToken(extractToken(auth));
        if (userId == null) return Result.error(401, "未登录");

        Conversation conv = conversationMapper.selectById(id);
        if (conv == null || !userId.equals(conv.getUserId())) {
            return Result.error(403, "无权访问");
        }

        conv.setStatus(0);
        conv.setUpdatedAt(LocalDateTime.now());
        conversationMapper.updateById(conv);
        return Result.success(null);
    }

    /** 重命名对话 */
    @PutMapping("/{id}/title")
    public Result<Void> rename(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        Long userId = AuthController.getUserIdFromToken(extractToken(auth));
        if (userId == null) return Result.error(401, "未登录");

        Conversation conv = conversationMapper.selectById(id);
        if (conv == null || !userId.equals(conv.getUserId())) {
            return Result.error(403, "无权访问");
        }

        String newTitle = body.get("title");
        if (newTitle == null || newTitle.isBlank()) {
            return Result.error(400, "标题不能为空");
        }

        conv.setTitle(newTitle.trim());
        conv.setUpdatedAt(LocalDateTime.now());
        conversationMapper.updateById(conv);
        return Result.success(null);
    }

    /** 搜索对话历史（关键词 + 时间范围） */
    @GetMapping("/search")
    public Result<List<Map<String, Object>>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        Long userId = AuthController.getUserIdFromToken(extractToken(auth));
        if (userId == null) return Result.error(401, "未登录");

        boolean hasKeyword = keyword != null && !keyword.isBlank();
        String kw = hasKeyword ? keyword.trim() : null;

        // 1. 按标题搜索匹配的对话
        LambdaQueryWrapper<Conversation> titleQuery = new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .eq(Conversation::getStatus, 1);
        if (hasKeyword) {
            titleQuery.like(Conversation::getTitle, kw);
        }
        if (startDate != null && !startDate.isBlank()) {
            titleQuery.ge(Conversation::getCreatedAt, LocalDate.parse(startDate).atStartOfDay());
        }
        if (endDate != null && !endDate.isBlank()) {
            titleQuery.le(Conversation::getCreatedAt, LocalDate.parse(endDate).atTime(23, 59, 59));
        }
        titleQuery.orderByDesc(Conversation::getUpdatedAt);
        List<Conversation> titleMatches = conversationMapper.selectList(titleQuery);

        // 2. 按消息内容搜索匹配的对话（补充标题未匹配的）
        Set<Long> titleMatchIds = titleMatches.stream().map(Conversation::getId).collect(Collectors.toSet());
        List<Conversation> contentMatches = new ArrayList<>();
        if (hasKeyword) {
            LambdaQueryWrapper<ChatMessage> msgQuery = new LambdaQueryWrapper<ChatMessage>()
                    .like(ChatMessage::getContent, kw)
                    .select(ChatMessage::getConversationId);
            List<Long> msgConvIds = chatMessageMapper.selectList(msgQuery).stream()
                    .map(ChatMessage::getConversationId).distinct().collect(Collectors.toList());

            if (!msgConvIds.isEmpty()) {
                List<Long> newIds = msgConvIds.stream().filter(id -> !titleMatchIds.contains(id)).collect(Collectors.toList());
                if (!newIds.isEmpty()) {
                    LambdaQueryWrapper<Conversation> convQuery = new LambdaQueryWrapper<Conversation>()
                            .in(Conversation::getId, newIds)
                            .eq(Conversation::getUserId, userId)
                            .eq(Conversation::getStatus, 1);
                    if (startDate != null && !startDate.isBlank()) {
                        convQuery.ge(Conversation::getCreatedAt, LocalDate.parse(startDate).atStartOfDay());
                    }
                    if (endDate != null && !endDate.isBlank()) {
                        convQuery.le(Conversation::getCreatedAt, LocalDate.parse(endDate).atTime(23, 59, 59));
                    }
                    contentMatches = conversationMapper.selectList(convQuery);
                }
            }
        }

        // 3. 合并结果并按更新时间排序
        List<Conversation> allConvs = new ArrayList<>(titleMatches);
        allConvs.addAll(contentMatches);
        allConvs.sort((a, b) -> b.getUpdatedAt().compareTo(a.getUpdatedAt()));

        // 4. 获取每条对话的匹配消息片段
        List<Long> convIds = allConvs.stream().map(Conversation::getId).collect(Collectors.toList());
        Map<Long, String> snippetMap = new HashMap<>();
        if (hasKeyword && !convIds.isEmpty()) {
            LambdaQueryWrapper<ChatMessage> snippetQuery = new LambdaQueryWrapper<ChatMessage>()
                    .in(ChatMessage::getConversationId, convIds)
                    .like(ChatMessage::getContent, kw)
                    .orderByAsc(ChatMessage::getId)
                    .select(ChatMessage::getConversationId, ChatMessage::getContent);
            List<ChatMessage> snippets = chatMessageMapper.selectList(snippetQuery);
            for (ChatMessage msg : snippets) {
                snippetMap.putIfAbsent(msg.getConversationId(), msg.getContent());
            }
        }

        // 5. 组装返回结果
        List<Map<String, Object>> results = new ArrayList<>();
        for (Conversation conv : allConvs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", conv.getId());
            item.put("title", conv.getTitle());
            item.put("sessionId", conv.getSessionId());
            item.put("createdAt", conv.getCreatedAt());
            item.put("updatedAt", conv.getUpdatedAt());
            String snippet = snippetMap.get(conv.getId());
            if (snippet != null && snippet.length() > 100) {
                snippet = snippet.substring(0, 100) + "...";
            }
            item.put("matchedSnippet", snippet);
            results.add(item);
        }

        return Result.success(results);
    }

    private String extractToken(String auth) {
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7);
        }
        return null;
    }
}
