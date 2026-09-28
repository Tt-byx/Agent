package com.scenic.ai.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.scenic.ai.dto.Result;
import com.scenic.ai.entity.*;
import com.scenic.ai.mapper.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/visitor-profiles")
public class VisitorProfileController {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Autowired
    private VisitorProfileTagMapper visitorProfileTagMapper;

    /** 获取所有游客画像列表 */
    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        // 1. 获取所有游客
        List<User> visitors = userMapper.selectList(
                new LambdaQueryWrapper<User>()
                        .eq(User::getRole, "visitor")
                        .orderByDesc(User::getCreatedAt));
        if (visitors.isEmpty()) return Result.success(Collections.emptyList());

        List<Long> userIds = visitors.stream().map(User::getId).collect(Collectors.toList());

        // 2. 批量获取所有标签
        List<VisitorProfileTag> allTags = visitorProfileTagMapper.selectList(
                new LambdaQueryWrapper<VisitorProfileTag>()
                        .in(VisitorProfileTag::getUserId, userIds));
        Map<Long, List<VisitorProfileTag>> tagsByUser = allTags.stream()
                .collect(Collectors.groupingBy(VisitorProfileTag::getUserId));

        // 3. 批量获取对话数
        List<Map<String, Object>> convCounts = conversationMapper.selectMaps(
                new LambdaQueryWrapper<Conversation>()
                        .in(Conversation::getUserId, userIds)
                        .eq(Conversation::getStatus, 1)
                        .select(Conversation::getUserId)
                        .groupBy(Conversation::getUserId));
        Map<Long, Long> convCountMap = new HashMap<>();
        for (Map<String, Object> row : convCounts) {
            Long uid = ((Number) row.get("user_id")).longValue();
            convCountMap.put(uid, 1L); // just mark as having conversations
        }
        // Actually count properly
        for (Long uid : userIds) {
            long count = conversationMapper.selectCount(
                    new LambdaQueryWrapper<Conversation>()
                            .eq(Conversation::getUserId, uid)
                            .eq(Conversation::getStatus, 1));
            convCountMap.put(uid, count);
        }

        // 4. 批量获取消息数和情感分布
        List<Long> convIds = conversationMapper.selectList(
                new LambdaQueryWrapper<Conversation>()
                        .in(Conversation::getUserId, userIds)
                        .eq(Conversation::getStatus, 1)
                        .select(Conversation::getId, Conversation::getUserId))
                .stream().map(Conversation::getId).collect(Collectors.toList());

        Map<Long, int[]> sentimentByUser = new HashMap<>(); // [positive, neutral, negative]
        for (Long uid : userIds) sentimentByUser.put(uid, new int[]{0, 0, 0});

        if (!convIds.isEmpty()) {
            // Get conversation -> userId mapping
            Map<Long, Long> convToUser = conversationMapper.selectList(
                    new LambdaQueryWrapper<Conversation>()
                            .in(Conversation::getId, convIds)
                            .select(Conversation::getId, Conversation::getUserId))
                    .stream().collect(Collectors.toMap(Conversation::getId, Conversation::getUserId));

            List<ChatMessage> assistantMsgs = chatMessageMapper.selectList(
                    new LambdaQueryWrapper<ChatMessage>()
                            .in(ChatMessage::getConversationId, convIds)
                            .eq(ChatMessage::getRole, "assistant")
                            .isNotNull(ChatMessage::getSentiment)
                            .select(ChatMessage::getConversationId, ChatMessage::getSentiment));

            for (ChatMessage msg : assistantMsgs) {
                Long uid = convToUser.get(msg.getConversationId());
                if (uid == null) continue;
                int[] s = sentimentByUser.get(uid);
                if (s == null) continue;
                switch (msg.getSentiment()) {
                    case "positive": s[0]++; break;
                    case "neutral": s[1]++; break;
                    case "negative": s[2]++; break;
                }
            }
        }

        // 5. 组装结果
        List<Map<String, Object>> results = new ArrayList<>();
        for (User user : visitors) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("userId", user.getId());
            item.put("nickname", user.getNickname());
            item.put("avatarUrl", user.getAvatarUrl());
            item.put("createdAt", user.getCreatedAt());

            long convCount = convCountMap.getOrDefault(user.getId(), 0L);
            item.put("conversationCount", convCount);

            // Tags sorted by score
            List<VisitorProfileTag> tags = tagsByUser.getOrDefault(user.getId(), Collections.emptyList());
            tags.sort((a, b) -> b.getTagScore() - a.getTagScore());
            List<Map<String, Object>> tagList = tags.stream().map(t -> {
                Map<String, Object> tm = new LinkedHashMap<>();
                tm.put("name", t.getTagName());
                tm.put("score", t.getTagScore());
                return tm;
            }).collect(Collectors.toList());
            item.put("tags", tagList);

            int[] sentiment = sentimentByUser.getOrDefault(user.getId(), new int[]{0, 0, 0});
            Map<String, Object> sentimentMap = new LinkedHashMap<>();
            sentimentMap.put("positive", sentiment[0]);
            sentimentMap.put("neutral", sentiment[1]);
            sentimentMap.put("negative", sentiment[2]);
            item.put("sentiment", sentimentMap);

            results.add(item);
        }

        return Result.success(results);
    }
}
