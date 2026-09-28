package com.scenic.ai.service;

import java.util.Map;

public interface DashboardService {
    Map<String, Object> getOverview();
    Map<String, Object> getSentimentDistribution();
    Map<String, Object> getTrends();
    Map<String, Object> getRecentConversations();
    Map<String, Object> getTopQuestions();
}
