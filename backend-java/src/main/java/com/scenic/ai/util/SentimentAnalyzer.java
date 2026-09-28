package com.scenic.ai.util;

import java.util.Set;

/**
 * 基于关键词的简单情感分析工具
 */
public class SentimentAnalyzer {

    private static final Set<String> POSITIVE_WORDS = Set.of(
        "好的", "谢谢", "感谢", "太好了", "不错", "很好", "棒", "赞", "喜欢",
        "开心", "高兴", "满意", "精彩", "美丽", "漂亮", "方便", "有用",
        "明白了", "知道了", "了解", "可以", "没问题"
    );

    private static final Set<String> NEGATIVE_WORDS = Set.of(
        "抱歉", "无法", "不知道", "没有", "失败", "错误", "不支持", "不能",
        "不行", "遗憾", "困难", "问题", "投诉", "不满", "差", "糟糕"
    );

    /**
     * 分析文本情感
     * @return "positive", "neutral", 或 "negative"
     */
    public static String analyze(String text) {
        if (text == null || text.isEmpty()) return "neutral";

        int positiveScore = 0;
        int negativeScore = 0;

        for (String word : POSITIVE_WORDS) {
            if (text.contains(word)) positiveScore++;
        }
        for (String word : NEGATIVE_WORDS) {
            if (text.contains(word)) negativeScore++;
        }

        if (positiveScore > negativeScore) return "positive";
        if (negativeScore > positiveScore) return "negative";
        return "neutral";
    }
}
