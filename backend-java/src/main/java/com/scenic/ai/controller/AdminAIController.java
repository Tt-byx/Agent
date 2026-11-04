package com.scenic.ai.controller;

import com.scenic.ai.exception.RedisShortCircuitException;
import com.scenic.ai.agent.core.AgentLoop;
import com.scenic.ai.dto.Result;
import com.scenic.ai.entity.Conversation;
import com.scenic.ai.entity.VisitorProfileTag;
import com.scenic.ai.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 管理后台的运营 AI 助手（运营侧，与游客对话链路相互独立）。
 *
 * A2 · Python 退场后改为 JVM 内直调 AgentLoop：原来的实现只是
 * 「Java 拼上下文 → RestTemplate 转发 Python → Python 调一次 LLM」，
 * 迁移后省掉一跳网络，并复用主循环的超时 / 降级 / token 统计。
 */
@RestController
@RequestMapping("/api/admin-ai")
public class AdminAIController {

    private static final Logger log = LoggerFactory.getLogger(AdminAIController.class);

    @Autowired
    private TourismDataMapper tourismDataMapper;

    @Autowired
    private VisitorProfileTagMapper visitorProfileTagMapper;

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private AgentLoop agentLoop;

    /** 运营人员 AI 问答：组装行为数据上下文后交给 Agent */
    @PostMapping("/chat")
    public Result<Map<String, Object>> chat(@RequestBody Map<String, String> body) {
        String message = body.getOrDefault("message", "");
        if (message.isEmpty()) {
            return Result.error(400, "请输入问题");
        }

        try {
            String behaviorContext = buildBehaviorContext();
            String prompt = "你是景区运营数据分析师。下面是游客行为数据的统计摘要：\n"
                    + behaviorContext
                    + "\n\n运营人员的问题：" + message
                    + "\n\n请基于以上数据回答，用纯文本口语化中文，结论要落到可执行的运营动作上。";

            AgentLoop.Result r = agentLoop.run("admin-chat", "admin-chat", prompt);

            Map<String, Object> result = new HashMap<>();
            result.put("reply", r.answer());
            return Result.success(result);
        } catch (Exception e) {
            RedisShortCircuitException.rethrowIf(e);   // 短路必须冒泡，不能被降级逻辑吞掉
            log.error("AI问答失败: {}", e.getMessage());
            return Result.error(500, "AI服务暂时不可用");
        }
    }

    /** 基于游客画像生成营销推荐 */
    @PostMapping("/recommend")
    public Result<Map<String, Object>> recommend(@RequestBody Map<String, Object> body) {
        Long userId = body.containsKey("userId") ? Long.valueOf(body.get("userId").toString()) : null;
        if (userId == null) {
            return Result.error(400, "请选择游客");
        }

        try {
            // 查询画像标签
            List<VisitorProfileTag> tags = visitorProfileTagMapper.selectList(
                    new LambdaQueryWrapper<VisitorProfileTag>()
                            .eq(VisitorProfileTag::getUserId, userId)
                            .orderByDesc(VisitorProfileTag::getTagScore));
            List<String> tagNames = tags.stream()
                    .map(VisitorProfileTag::getTagName)
                    .collect(Collectors.toList());

            // 查询对话情感分布
            Map<String, String> profile = buildUserProfile(userId);

            String prompt = "你是景区营销顾问。以下是某位游客的画像：\n"
                    + "昵称：" + profile.getOrDefault("nickname", "游客") + "\n"
                    + "兴趣标签：" + (tagNames.isEmpty() ? "暂无" : String.join("、", tagNames)) + "\n"
                    + "情感倾向：" + profile.getOrDefault("sentiment", "中性") + "\n"
                    + "消费概况：" + profile.getOrDefault("consumption", "暂无消费记录") + "\n"
                    + "最近对话片段：" + profile.getOrDefault("snippet", "无") + "\n\n"
                    + "请基于该画像给出 3-5 条个性化营销或服务建议（推荐景点、活动、优惠等），"
                    + "纯文本，每条一行，不要编号符号。";

            AgentLoop.Result r = agentLoop.run("admin-recommend-" + userId, "admin-recommend-" + userId, prompt);

            Map<String, Object> result = new HashMap<>();
            result.put("suggestions", r.answer());
            return Result.success(result);
        } catch (Exception e) {
            RedisShortCircuitException.rethrowIf(e);   // 短路必须冒泡，不能被降级逻辑吞掉
            log.error("推荐生成失败: {}", e.getMessage());
            return Result.error(500, "推荐服务暂时不可用");
        }
    }

    /** 获取游客列表（供前端选择） */
    @GetMapping("/visitors")
    public Result<List<Map<String, Object>>> listVisitors() {
        // 获取所有 visitor 角色的用户
        var users = userMapper.selectList(
                new LambdaQueryWrapper<com.scenic.ai.entity.User>()
                        .eq(com.scenic.ai.entity.User::getRole, "visitor")
                        .eq(com.scenic.ai.entity.User::getStatus, 1));

        List<Map<String, Object>> result = new ArrayList<>();
        for (var user : users) {
            // 检查是否有对话记录
            Long convCount = conversationMapper.selectCount(
                    new LambdaQueryWrapper<Conversation>()
                            .eq(Conversation::getUserId, user.getId())
                            .eq(Conversation::getStatus, 1));
            if (convCount == 0) continue;

            Map<String, Object> item = new HashMap<>();
            item.put("userId", user.getId());
            item.put("nickname", user.getNickname());
            item.put("avatarUrl", user.getAvatarUrl());

            // 获取 top 标签
            List<VisitorProfileTag> tags = visitorProfileTagMapper.selectList(
                    new LambdaQueryWrapper<VisitorProfileTag>()
                            .eq(VisitorProfileTag::getUserId, user.getId())
                            .orderByDesc(VisitorProfileTag::getTagScore)
                            .last("LIMIT 3"));
            item.put("topTags", tags.stream().map(VisitorProfileTag::getTagName).collect(Collectors.toList()));

            result.add(item);
        }
        return Result.success(result);
    }

    /** 构建行为数据上下文摘要 */
    private String buildBehaviorContext() {
        StringBuilder ctx = new StringBuilder();

        // 查询总记录数
        Long total = tourismDataMapper.selectCount(null);
        ctx.append("数据总量：").append(total).append("条游客行为记录。\n");

        // 热门景区 Top5
        var topAttractions = tourismDataMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.scenic.ai.entity.TourismData>()
                        .select("attraction_name", "COUNT(*) as cnt", "AVG(total_cost) as avg_cost", "AVG(satisfaction) as avg_sat")
                        .groupBy("attraction_name")
                        .orderByDesc("cnt")
                        .last("LIMIT 5"));
        ctx.append("热门景区Top5：");
        for (var a : topAttractions) {
            ctx.append(a.get("attraction_name")).append("(")
               .append("访问").append(a.get("cnt")).append("次,")
               .append("人均¥").append(String.format("%.0f", toDouble(a.get("avg_cost")))).append(",")
               .append("满意度").append(String.format("%.1f", toDouble(a.get("avg_sat")))).append(") ");
        }
        ctx.append("\n");

        // 消费概况
        var avgCosts = tourismDataMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.scenic.ai.entity.TourismData>()
                        .select("AVG(ticket_cost) as avg_ticket", "AVG(food_cost) as avg_food",
                                "AVG(shopping_cost) as avg_shop", "AVG(total_cost) as avg_total")
                        .last("LIMIT 1"));
        if (!avgCosts.isEmpty()) {
            var row = avgCosts.get(0);
            ctx.append("人均消费：门票¥").append(String.format("%.0f", toDouble(row.get("avg_ticket"))))
               .append("，餐饮¥").append(String.format("%.0f", toDouble(row.get("avg_food"))))
               .append("，购物¥").append(String.format("%.0f", toDouble(row.get("avg_shop"))))
               .append("，总消费¥").append(String.format("%.0f", toDouble(row.get("avg_total")))).append("\n");
        }

        // 年龄分布
        var ageDist = tourismDataMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.scenic.ai.entity.TourismData>()
                        .select("CASE WHEN age<18 THEN '少年' WHEN age BETWEEN 18 AND 35 THEN '青年' WHEN age BETWEEN 36 AND 55 THEN '中年' ELSE '老年' END as age_group",
                                "COUNT(*) as cnt")
                        .groupBy("age_group"));
        ctx.append("年龄分布：");
        for (var a : ageDist) {
            ctx.append(a.get("age_group")).append(a.get("cnt")).append("人 ");
        }

        return ctx.toString();
    }

    /** 构建单个游客的画像 */
    private Map<String, String> buildUserProfile(Long userId) {
        Map<String, String> profile = new HashMap<>();

        var user = userMapper.selectById(userId);
        profile.put("nickname", user != null ? user.getNickname() : "游客");

        // 对话情感统计
        // Wave 0 · 修 B1：原实现用 inSql 拼字符串（"... WHERE user_id = " + userId）。
        // 改为「先查会话 ID，再按 ID 集合查消息」两步走，彻底消除 SQL 拼接，
        // 也避免了会话很多时拼出超长子查询。
        List<Long> conversationIds = conversationMapper.selectList(
                        new LambdaQueryWrapper<com.scenic.ai.entity.Conversation>()
                                .eq(com.scenic.ai.entity.Conversation::getUserId, userId)
                                .select(com.scenic.ai.entity.Conversation::getId))
                .stream().map(com.scenic.ai.entity.Conversation::getId).toList();

        if (conversationIds.isEmpty()) {
            profile.put("sentiment", "中性");
            profile.put("snippet", "");
            return profile;
        }

        var messages = chatMessageMapper.selectList(
                new LambdaQueryWrapper<com.scenic.ai.entity.ChatMessage>()
                        .in(com.scenic.ai.entity.ChatMessage::getConversationId, conversationIds)
                        .eq(com.scenic.ai.entity.ChatMessage::getRole, "user"));
        if (!messages.isEmpty()) {
            long positive = messages.stream().filter(m -> "positive".equals(m.getSentiment())).count();
            long negative = messages.stream().filter(m -> "negative".equals(m.getSentiment())).count();
            long total = messages.size();
            String sentiment = negative > total / 3 ? "偏消极" : (positive > total / 2 ? "偏积极" : "中性");
            profile.put("sentiment", sentiment + " (积极" + positive + "/中性" + (total - positive - negative) + "/消极" + negative + ")");
            // 最近对话摘要
            String snippet = messages.get(messages.size() - 1).getContent();
            profile.put("snippet", snippet.length() > 100 ? snippet.substring(0, 100) + "..." : snippet);
        } else {
            profile.put("sentiment", "中性");
            profile.put("snippet", "");
        }

        return profile;
    }

    private double toDouble(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return 0; }
    }
}
