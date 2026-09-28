package com.scenic.ai.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.scenic.ai.dto.Result;
import com.scenic.ai.entity.DigitalHumanConfig;
import com.scenic.ai.mapper.DigitalHumanConfigMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/digital-human")
public class DigitalHumanController {

    @Autowired
    private DigitalHumanConfigMapper configMapper;

    /** 获取所有配置 */
    @GetMapping("/config")
    public Result<Map<String, String>> getConfig() {
        List<DigitalHumanConfig> configs = configMapper.selectList(null);
        Map<String, String> map = new HashMap<>();
        for (DigitalHumanConfig c : configs) {
            map.put(c.getConfigKey(), c.getConfigValue());
        }
        // 填充默认值
        map.putIfAbsent("voice", "zh-CN-XiaoxiaoNeural");
        map.putIfAbsent("voice_speed", "1.0");
        map.putIfAbsent("model", "aniya");
        return Result.success(map);
    }

    /** 更新单个配置项 */
    @PutMapping("/config")
    public Result<String> updateConfig(@RequestBody Map<String, String> body) {
        for (Map.Entry<String, String> entry : body.entrySet()) {
            LambdaQueryWrapper<DigitalHumanConfig> wrapper = new LambdaQueryWrapper<DigitalHumanConfig>()
                    .eq(DigitalHumanConfig::getConfigKey, entry.getKey());
            DigitalHumanConfig existing = configMapper.selectOne(wrapper);

            if (existing != null) {
                existing.setConfigValue(entry.getValue());
                existing.setUpdatedAt(LocalDateTime.now());
                configMapper.updateById(existing);
            } else {
                DigitalHumanConfig config = new DigitalHumanConfig();
                config.setConfigKey(entry.getKey());
                config.setConfigValue(entry.getValue());
                config.setUpdatedAt(LocalDateTime.now());
                configMapper.insert(config);
            }
        }
        return Result.success("ok");
    }

    /** 获取可用音色列表 */
    @GetMapping("/voices")
    public Result<Map<String, Object>> getVoices() {
        Map<String, Object> voices = new HashMap<>();
        voices.put("zh-CN-XiaoxiaoNeural", Map.of("name", "晓晓", "gender", "女", "style", "温和自然"));
        voices.put("zh-CN-YunxiNeural", Map.of("name", "云希", "gender", "男", "style", "阳光活力"));
        voices.put("zh-CN-YunyangNeural", Map.of("name", "云扬", "gender", "男", "style", "专业沉稳"));
        voices.put("zh-CN-XiaoyiNeural", Map.of("name", "晓艺", "gender", "女", "style", "活泼可爱"));
        voices.put("zh-CN-YunjianNeural", Map.of("name", "云健", "gender", "男", "style", "成熟磁性"));
        voices.put("zh-CN-XiaochenNeural", Map.of("name", "晓辰", "gender", "女", "style", "知性优雅"));
        return Result.success(voices);
    }
}
