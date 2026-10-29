package com.scenic.ai.controller;

import com.scenic.ai.dto.Result;
import com.scenic.ai.entity.MessageFeedback;
import com.scenic.ai.mapper.MessageFeedbackMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.scenic.ai.security.CurrentUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    @Autowired
    private MessageFeedbackMapper feedbackMapper;

    /** 提交反馈 */
    @PostMapping
    public Result<String> submitFeedback(@RequestBody Map<String, Object> body) {
        // 2026-10-29（D04）：身份只从认证上下文取，**忽略 body 里的 userId**。
        // 原实现直接读 body.get("userId")，已登录用户 A 传 {"messageId":123,"userId":8}
        // 就能把 B 的 like 改成 dislike（updateById 改的是 B 那条记录）。
        // 这条与本项目 resolveMemoryKey 已建立的标准一致：客户端能写的字段是「声明」，不是身份。
        Long userId = CurrentUser.id();
        if (userId == null) {
            return Result.error(401, "未登录");
        }

        if (body.get("messageId") == null) {
            return Result.error(400, "messageId 不能为空");
        }
        Long messageId;
        try {
            messageId = Long.valueOf(body.get("messageId").toString());
        } catch (NumberFormatException e) {
            return Result.error(400, "messageId 必须是数字");
        }
        String type = (String) body.get("type");

        if (!"like".equals(type) && !"dislike".equals(type)) {
            return Result.error(400, "type 必须为 like 或 dislike");
        }

        // 查询是否已有反馈（同一用户同一消息只能反馈一次，可改）
        LambdaQueryWrapper<MessageFeedback> wrapper = new LambdaQueryWrapper<MessageFeedback>()
                .eq(MessageFeedback::getMessageId, messageId)
                .eq(MessageFeedback::getUserId, userId);
        MessageFeedback existing = feedbackMapper.selectOne(wrapper);

        if (existing != null) {
            existing.setFeedbackType(type);
            feedbackMapper.updateById(existing);
        } else {
            MessageFeedback feedback = new MessageFeedback();
            feedback.setMessageId(messageId);
            feedback.setUserId(userId);
            feedback.setFeedbackType(type);
            feedback.setCreatedAt(LocalDateTime.now());
            feedbackMapper.insert(feedback);
        }

        return Result.success("ok");
    }

    /** 反馈统计 */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        long likes = feedbackMapper.selectCount(
                new LambdaQueryWrapper<MessageFeedback>().eq(MessageFeedback::getFeedbackType, "like"));
        long dislikes = feedbackMapper.selectCount(
                new LambdaQueryWrapper<MessageFeedback>().eq(MessageFeedback::getFeedbackType, "dislike"));

        Map<String, Object> stats = new HashMap<>();
        stats.put("likes", likes);
        stats.put("dislikes", dislikes);
        stats.put("total", likes + dislikes);
        stats.put("satisfactionRate", (likes + dislikes) > 0
                ? Math.round(likes * 100.0 / (likes + dislikes)) : 0);

        return Result.success(stats);
    }
}
