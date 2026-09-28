package com.scenic.ai.controller;

import com.scenic.ai.dto.Result;
import com.scenic.ai.service.DashboardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    @Autowired
    private DashboardService dashboardService;

    /** 总览数据：今日/总会话数、今日/总消息数 */
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        return Result.success(dashboardService.getOverview());
    }

    /** 情感分布：positive/neutral/negative 数量 */
    @GetMapping("/sentiment")
    public Result<Map<String, Object>> sentiment() {
        return Result.success(dashboardService.getSentimentDistribution());
    }

    /** 趋势数据：最近7天会话数和消息数 */
    @GetMapping("/trends")
    public Result<Map<String, Object>> trends() {
        return Result.success(dashboardService.getTrends());
    }

    /** 最近对话记录 */
    @GetMapping("/conversations")
    public Result<Map<String, Object>> conversations() {
        return Result.success(dashboardService.getRecentConversations());
    }

    /** 热门问题 Top10 */
    @GetMapping("/top-questions")
    public Result<Map<String, Object>> topQuestions() {
        return Result.success(dashboardService.getTopQuestions());
    }
}
