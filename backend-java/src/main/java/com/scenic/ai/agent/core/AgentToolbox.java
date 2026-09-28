package com.scenic.ai.agent.core;

import com.scenic.ai.agent.tools.DebugTools;
import com.scenic.ai.agent.tools.KnowledgeTools;
import com.scenic.ai.agent.tools.MemoryTools;
import com.scenic.ai.agent.tools.ScenicTools;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具箱：把若干「带 {@code @Tool} 注解的对象」聚合成 Agent 可用的工具集。
 *
 * 三个能力：
 *   1. **工具定义不重复写** —— 用 {@link ToolSpecifications} 从注解反射生成 schema；
 *   2. **执行异常不外抛** —— {@link #execute} 把工具异常转成错误文本回灌给模型（P7）；
 *   3. **派生子工具箱** —— {@link #view(List)} 按名单裁剪出子集（P9 多 Agent：每个专家只拿到自己该用的工具）。
 */
@Component
@Slf4j
public class AgentToolbox {

    private final List<ToolSpecification> specifications;
    private final Map<String, Object> ownerByTool;
    private final List<String> toolNames;

    private AgentToolbox(List<ToolSpecification> specifications,
                         Map<String, Object> ownerByTool,
                         List<String> toolNames) {
        this.specifications = List.copyOf(specifications);
        this.ownerByTool = Map.copyOf(ownerByTool);
        this.toolNames = List.copyOf(toolNames);
    }

    /** Spring 主工具箱：全部业务工具 */
    @org.springframework.beans.factory.annotation.Autowired
    public AgentToolbox(ScenicTools scenicTools,
                        KnowledgeTools knowledgeTools,
                        MemoryTools memoryTools,
                        ObjectProvider<DebugTools> debugToolsProvider) {
        List<Object> toolObjects = new ArrayList<>(List.of(scenicTools, knowledgeTools, memoryTools));
        // 故障注入工具仅在 agent.debug-tools.enabled=true 时存在
        DebugTools debugTools = debugToolsProvider.getIfAvailable();
        if (debugTools != null) {
            toolObjects.add(debugTools);
        }

        List<ToolSpecification> specs = new ArrayList<>();
        Map<String, Object> owners = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        for (Object toolObject : toolObjects) {
            for (ToolSpecification spec : ToolSpecifications.toolSpecificationsFrom(toolObject)) {
                specs.add(spec);
                owners.put(spec.name(), toolObject);
                names.add(spec.name());
            }
        }
        this.specifications = List.copyOf(specs);
        this.ownerByTool = Map.copyOf(owners);
        this.toolNames = List.copyOf(names);

        if (debugTools != null) {
            log.warn("已启用故障注入工具（agent.debug-tools.enabled=true）");
        }
        log.info("Agent 工具箱就绪，共 {} 个工具：{}", specifications.size(), toolNames);
    }

    /** 从任意「带 @Tool 注解的对象」构建工具箱 —— P9 多 Agent 用（Supervisor 只有委派工具） */
    public static AgentToolbox of(List<Object> toolObjects) {
        List<ToolSpecification> specs = new ArrayList<>();
        Map<String, Object> owners = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        for (Object toolObject : toolObjects) {
            for (ToolSpecification spec : ToolSpecifications.toolSpecificationsFrom(toolObject)) {
                specs.add(spec);
                owners.put(spec.name(), toolObject);
                names.add(spec.name());
            }
        }
        return new AgentToolbox(specs, owners, names);
    }

    /**
     * 派生一个只含指定工具的**子工具箱**（P9 多 Agent 编排）。
     * 名单外的工具对专家**不可见** —— 这是"分工"的物理保证，而不是靠提示词约定。
     */
    public AgentToolbox view(List<String> allowedToolNames) {
        List<ToolSpecification> specs = new ArrayList<>();
        Map<String, Object> owners = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        for (String name : allowedToolNames) {
            ToolSpecification spec = specifications.stream()
                    .filter(s -> s.name().equals(name)).findFirst().orElse(null);
            Object owner = ownerByTool.get(name);
            if (spec == null || owner == null) {
                log.warn("子工具箱请求了不存在的工具，已跳过: {}", name);
                continue;
            }
            specs.add(spec);
            owners.put(name, owner);
            names.add(name);
        }
        return new AgentToolbox(specs, owners, names);
    }

    public List<ToolSpecification> specifications() {
        return specifications;
    }

    public int size() {
        return specifications.size();
    }

    public List<String> toolNames() {
        return toolNames;
    }

    /**
     * 执行工具。**任何异常都在这里被拦下并转成错误文本**，让模型能读到错误并自行调整，
     * 而不是让整轮对话崩掉。
     */
    public String execute(ToolExecutionRequest request, String memoryId) {
        Object owner = ownerByTool.get(request.name());
        if (owner == null) {
            String msg = "错误：不存在名为「" + request.name() + "」的工具。当前可用工具：" + toolNames;
            log.warn(msg);
            return msg;
        }

        try {
            return new DefaultToolExecutor(owner, request).execute(request, memoryId);
        } catch (Exception e) {
            String reason = rootMessage(e);
            log.warn("工具 [{}] 执行失败（错误将回灌给模型自纠）: {}", request.name(), reason);
            return "工具「" + request.name() + "」执行失败：" + reason
                    + "。请据此调整：可以换用其他工具、调整参数重试，或者直接如实告知游客"
                    + "该信息暂时查询不到、并给出可行建议。不要编造结果。";
        }
    }

    private String rootMessage(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
