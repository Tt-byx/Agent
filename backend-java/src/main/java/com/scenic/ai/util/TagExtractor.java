package com.scenic.ai.util;

import java.util.*;

/**
 * 基于关键词的游客兴趣标签提取工具
 */
public class TagExtractor {

    private static final Map<String, List<String>> TAG_KEYWORDS = new LinkedHashMap<>();

    static {
        TAG_KEYWORDS.put("历史文化", Arrays.asList(
                "历史", "朝代", "文物", "古建筑", "博物馆", "文化遗址", "古代", "遗迹", "古迹", "传统"
        ));
        TAG_KEYWORDS.put("自然风光", Arrays.asList(
                "风景", "山水", "花草", "树木", "日出", "湖", "瀑布", "森林", "山峰", "溪流"
        ));
        TAG_KEYWORDS.put("亲子互动", Arrays.asList(
                "孩子", "小朋友", "亲子", "家庭", "儿童", "宝宝", "带娃", "小孩", "乐园"
        ));
        TAG_KEYWORDS.put("摄影打卡", Arrays.asList(
                "拍照", "打卡", "风景照", "取景", "摄影", "出片", "机位", "拍摄", "留影"
        ));
        TAG_KEYWORDS.put("美食禅意", Arrays.asList(
                "美食", "小吃", "禅意", "素食", "茶道", "素斋", "特产", "餐厅", "茶室"
        ));
    }

    /**
     * 从文本中提取兴趣标签
     * @param text 用户消息文本
     * @return 标签名 → 匹配次数
     */
    public static Map<String, Integer> extractTags(String text) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (text == null || text.isEmpty()) return result;

        for (Map.Entry<String, List<String>> entry : TAG_KEYWORDS.entrySet()) {
            int count = 0;
            for (String keyword : entry.getValue()) {
                int idx = 0;
                while ((idx = text.indexOf(keyword, idx)) != -1) {
                    count++;
                    idx += keyword.length();
                }
            }
            if (count > 0) {
                result.put(entry.getKey(), count);
            }
        }
        return result;
    }
}
