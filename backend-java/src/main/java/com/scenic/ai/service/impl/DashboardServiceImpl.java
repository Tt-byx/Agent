package com.scenic.ai.service.impl;

import com.scenic.ai.mapper.DashboardMapper;
import com.scenic.ai.service.DashboardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class DashboardServiceImpl implements DashboardService {

    @Autowired
    private DashboardMapper dashboardMapper;

    @Override
    public Map<String, Object> getOverview() {
        Map<String, Object> result = new HashMap<>();
        result.put("todaySessions", dashboardMapper.todaySessionCount());
        result.put("todayMessages", dashboardMapper.todayMessageCount());
        result.put("totalSessions", dashboardMapper.totalSessionCount());
        result.put("totalMessages", dashboardMapper.totalMessageCount());
        return result;
    }

    @Override
    public Map<String, Object> getSentimentDistribution() {
        Map<String, Object> result = new HashMap<>();
        result.put("distribution", dashboardMapper.sentimentDistribution());
        return result;
    }

    @Override
    public Map<String, Object> getTrends() {
        Map<String, Object> result = new HashMap<>();
        result.put("sessions", dashboardMapper.dailySessionTrend());
        result.put("messages", dashboardMapper.dailyMessageTrend());
        return result;
    }

    @Override
    public Map<String, Object> getRecentConversations() {
        Map<String, Object> result = new HashMap<>();
        result.put("conversations", dashboardMapper.recentConversations());
        return result;
    }

    @Override
    public Map<String, Object> getTopQuestions() {
        Map<String, Object> result = new HashMap<>();
        result.put("questions", dashboardMapper.topQuestions());
        return result;
    }
}
