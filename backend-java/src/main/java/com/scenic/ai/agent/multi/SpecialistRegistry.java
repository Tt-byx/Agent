package com.scenic.ai.agent.multi;

import com.scenic.ai.agent.core.AgentToolbox;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * P9 · 专家 Agent 注册表。
 *
 * 每个专家 = 名称 + 职责描述（给 Supervisor 做路由用）+ **允许的工具清单** + 人设。
 *
 * 「分工」的实现要点：专家只能拿到自己名单内的工具（物理隔离，见 {@link AgentToolbox#view}），
 * 而不是靠提示词里写"请不要用别的工具" —— 后者模型随时可以违反。
 */
@Component
@Slf4j
public class SpecialistRegistry {

    public record Specialist(String name, String title, String description,
                             List<String> tools, String persona) {}

    private final AgentToolbox fullToolbox;
    private final List<Specialist> specialists;
    private final String basePrompt;

    public SpecialistRegistry(AgentToolbox fullToolbox,
                              @Value("${agent.system-prompt:prompts/scenic-agent-system.txt}") String systemPromptPath)
            throws IOException {
        this.fullToolbox = fullToolbox;
        this.basePrompt = new ClassPathResource(systemPromptPath).getContentAsString(StandardCharsets.UTF_8);

        this.specialists = List.of(
                new Specialist("knowledge", "景区知识顾问",
                        "解答灵山胜境的历史、文化、景点介绍、建筑参数等事实性问题",
                        List.of("searchKnowledgeBase"),
                        "你是一位资深的景区文化讲解员，擅长讲历史典故与文化内涵，回答生动、有故事感。"),
                new Specialist("itinerary", "行程规划师",
                        "综合实时天气、门票价格、演出时刻与同行人群，安排具体的游览路线",
                        List.of("queryWeather", "calculateTicketPrice", "getNextShowTime",
                                "recommendRoute", "checkSpotStatus", "searchKnowledgeBase"),
                        "你是一位细心的行程规划师，给出的方案必须落到具体时段和先后顺序。"),
                new Specialist("operations", "运营实况分析师",
                        "分析各景点当前的拥挤度、开放状态与天气，判断「现在」适合去哪",
                        List.of("checkSpotStatus", "queryWeather"),
                        "你是一位景区运营分析师，只基于当前时刻的实况给判断和建议。")
        );
        log.info("专家注册表就绪：{}", specialists.stream().map(Specialist::name).toList());
    }

    public List<Specialist> all() {
        return specialists;
    }

    public List<String> names() {
        return specialists.stream().map(Specialist::name).toList();
    }

    public Specialist find(String name) {
        return specialists.stream()
                .filter(s -> s.name().equalsIgnoreCase(name == null ? "" : name.strip()))
                .findFirst().orElse(null);
    }

    /** 专家的工具箱：从主工具箱按名单裁剪 */
    public AgentToolbox toolboxFor(Specialist specialist) {
        return fullToolbox.view(specialist.tools());
    }

    /** 专家的系统提示词 = 基础人设与规则 + 专家人设 */
    public String promptFor(Specialist specialist) {
        return basePrompt + "\n\n【你现在的专属角色】\n" + specialist.persona()
                + "\n你只负责自己职责范围内的事；超出职责的部分，在回答里说明应由谁处理。";
    }
}
