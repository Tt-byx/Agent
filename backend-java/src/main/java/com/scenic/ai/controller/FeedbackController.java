package com.scenic.ai.controller;

import com.scenic.ai.dto.Result;
import com.scenic.ai.entity.MessageFeedback;
import com.scenic.ai.mapper.MessageFeedbackMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
        Long messageId = Long.valueOf(body.get("messageId").toString());
        String type = (String) body.get("type");
        Long userId = body.get("userId") != null ? Long.valueOf(body.get("userId").toString()) : null;

        if (!"like".equals(type) && !"dislike".equals(type)) {
            return Result.error(400, "type 必须为 like 或 dislike");
        }

        // 查询是否已有反馈（同一用户同一消息只能反馈一次，可改）
        LambdaQueryWrapper<MessageFeedback> wrapper = new LambdaQueryWrapper<MessageFeedback>()
                .eq(MessageFeedback::getMessageId, messageId);
        if (userId != null) {
            wrapper.eq(MessageFeedback::getUserId, userId);
        }
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
