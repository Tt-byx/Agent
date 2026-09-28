package com.scenic.ai.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface DashboardMapper {

    /** 今日会话数 */
    @Select("SELECT COUNT(*) FROM conversation WHERE DATE(created_at) = CURDATE()")
    int todaySessionCount();

    /** 今日消息数 */
    @Select("SELECT COUNT(*) FROM chat_message WHERE DATE(created_at) = CURDATE()")
    int todayMessageCount();

    /** 总会话数 */
    @Select("SELECT COUNT(*) FROM conversation")
    int totalSessionCount();

    /** 总消息数 */
    @Select("SELECT COUNT(*) FROM chat_message")
    int totalMessageCount();

    /** 情感分布 */
    @Select("SELECT sentiment, COUNT(*) as count FROM chat_message WHERE role='assistant' AND sentiment IS NOT NULL GROUP BY sentiment")
    List<Map<String, Object>> sentimentDistribution();

    /** 最近7天每日会话数趋势 */
    @Select("SELECT DATE(created_at) as date, COUNT(*) as count FROM conversation WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL 7 DAY) GROUP BY DATE(created_at) ORDER BY date")
    List<Map<String, Object>> dailySessionTrend();

    /** 最近7天每日消息数趋势 */
    @Select("SELECT DATE(created_at) as date, COUNT(*) as count FROM chat_message WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL 7 DAY) GROUP BY DATE(created_at) ORDER BY date")
    List<Map<String, Object>> dailyMessageTrend();

    /** 最近20条对话记录（含会话信息） */
    @Select("SELECT c.id as conversation_id, c.title, c.session_id, c.created_at as session_time, " +
            "(SELECT content FROM chat_message WHERE conversation_id = c.id AND role = 'user' ORDER BY id LIMIT 1) as first_user_message, " +
            "(SELECT content FROM chat_message WHERE conversation_id = c.id AND role = 'assistant' ORDER BY id DESC LIMIT 1) as last_ai_reply, " +
            "(SELECT COUNT(*) FROM chat_message WHERE conversation_id = c.id) as message_count " +
            "FROM conversation c WHERE c.status = 1 ORDER BY c.created_at DESC LIMIT 20")
    List<Map<String, Object>> recentConversations();

    /** 热门用户问题 Top10（去重） */
    @Select("SELECT content, COUNT(*) as count FROM chat_message WHERE role = 'user' AND DATE(created_at) >= DATE_SUB(CURDATE(), INTERVAL 7 DAY) GROUP BY content ORDER BY count DESC LIMIT 10")
    List<Map<String, Object>> topQuestions();
}
