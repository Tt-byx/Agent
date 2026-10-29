package com.scenic.ai.agent.core;

import com.scenic.ai.agent.tools.DebugTools;
import com.scenic.ai.agent.tools.KnowledgeTools;
import com.scenic.ai.agent.tools.MemoryTools;
import com.scenic.ai.agent.tools.ScenicTools;
import com.scenic.ai.gateway.AgentMetrics;
import com.scenic.ai.gateway.RetryPolicy;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具箱：把若干「带 {@code @Tool} 注解的对象」聚合成 Agent 可用的工具集。
 *
 * 能力：
 *   1. **工具定义不重复写** —— 用 {@link ToolSpecifications} 从注解反射生成 schema；
 *   2. **执行异常不外抛** —— 把工具异常转成错误文本回灌给模型（P7 的「错误自纠」实现点）；
 *   3. **派生子工具箱** —— {@link #view(List)} 按名单裁剪出子集（P9 多 Agent：专家只拿到白名单内的工具）；
 *   4. **Wave 1 加固：工具级超时 + 有限重试**。
 *      原实现直接同步调用工具 —— 一个卡死的下游会把整轮对话拖到 Agent 整体超时（120s）才收口，
 *      且完全没有重试。现在每个工具跑在独立线程池里、超时即中断，可重试错误做有限次退避重试。
 *
 * **重试前提**：本项目工具都是「查询类且幂等」（查天气/算票价/查开放状态/检索知识库）。
 * 若将来加入下单、支付这类非幂等工具，必须单独标注禁止重试，否则会重复扣款。
 */
@Component
@Slf4j
public class AgentToolbox {

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    private final List<ToolSpecification> specifications;
    private final Map<String, Object> ownerByTool;
    private final List<String> toolNames;

    /** 执行资源：所有派生工具箱共享同一个线程池，避免每个实例各建一个池 */
    private final ExecutorService toolExecutor; //工具线程池（超时控制）
    private final long toolTimeoutMs;   //单工具超时15s
    private final int toolMaxAttempts;  //最多尝试2词
    private final RetryPolicy retryPolicy;  //重试策略（判断哪些错误能重试）
    private final AgentMetrics metrics; //指标上报

    private AgentToolbox(List<ToolSpecification> specifications,
                         Map<String, Object> ownerByTool,
                         List<String> toolNames,
                         ExecutorService toolExecutor,
                         long toolTimeoutMs,
                         int toolMaxAttempts,
                         RetryPolicy retryPolicy,
                         AgentMetrics metrics) {
        this.specifications = List.copyOf(specifications);
        this.ownerByTool = Map.copyOf(ownerByTool);
        this.toolNames = List.copyOf(toolNames);
        this.toolExecutor = toolExecutor;
        this.toolTimeoutMs = toolTimeoutMs;
        this.toolMaxAttempts = toolMaxAttempts;
        this.retryPolicy = retryPolicy;
        this.metrics = metrics;
    }

    /** Spring 主工具箱：全部业务工具 */
    @org.springframework.beans.factory.annotation.Autowired
    public AgentToolbox(ScenicTools scenicTools,
                        KnowledgeTools knowledgeTools,
                        MemoryTools memoryTools,
                        ObjectProvider<DebugTools> debugToolsProvider,
                        RetryPolicy retryPolicy,
                        AgentMetrics metrics,
                        @Value("${agent.tool.timeout-ms:15000}") long toolTimeoutMs,
                        @Value("${agent.tool.max-attempts:2}") int toolMaxAttempts) {
        List<Object> toolObjects = new ArrayList<>(List.of(scenicTools, knowledgeTools, memoryTools));
        // 故障注入工具仅在 agent.debug-tools.enabled=true 时存在
        DebugTools debugTools = debugToolsProvider.getIfAvailable();
        if (debugTools != null) {
            toolObjects.add(debugTools);
        }

        BuildResult built = buildFrom(toolObjects);
        this.specifications = built.specifications();
        this.ownerByTool = built.ownerByTool();
        this.toolNames = built.toolNames();

        this.toolExecutor = createToolPool();
        this.toolTimeoutMs = toolTimeoutMs;
        this.toolMaxAttempts = Math.max(1, toolMaxAttempts);
        this.retryPolicy = retryPolicy;
        this.metrics = metrics;

        if (debugTools != null) {
            log.warn("已启用故障注入工具（agent.debug-tools.enabled=true）");
        }
        log.info("Agent 工具箱就绪，共 {} 个工具：{}；单工具超时 {}ms，最多 {} 次尝试",
                specifications.size(), toolNames, toolTimeoutMs, this.toolMaxAttempts);
    }

    /** 反射组装结果 */
    private record BuildResult(List<ToolSpecification> specifications,
                               Map<String, Object> ownerByTool,
                               List<String> toolNames) {}

    private static BuildResult buildFrom(List<Object> toolObjects) {
        List<ToolSpecification> specs = new ArrayList<>();
        Map<String, Object> owners = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        for (Object toolObject : toolObjects) {
            for (ToolSpecification spec : ToolSpecifications.toolSpecificationsFrom(toolObject)) {
                // 2026-10-29（D25）：重名必须启动期就炸。
                // 原实现是静默覆盖 —— specs 里留两条同名、owners 只留后来的实现。
                // 结果：模型看到两份 schema（可能参数不同），实际执行的是另一个方法，
                // 表现为「工具调对了但结果不对」，排查成本极高。
                // 判断依据：owners 是 LinkedHashMap，putIfAbsent 返回非 null 即说明已存在。
                Object previousOwner = owners.putIfAbsent(spec.name(), toolObject);
                if (previousOwner != null) {
                    throw new IllegalStateException(
                            "工具名重复：「" + spec.name() + "」同时来自 "
                                    + previousOwner.getClass().getSimpleName() + " 与 "
                                    + toolObject.getClass().getSimpleName()
                                    + "。同名工具会让模型看到两份 schema、却只执行其中一个实现。"
                                    + "请在 @Tool 注解上改名。");
                }
                specs.add(spec);
                names.add(spec.name());
            }
        }
        return new BuildResult(List.copyOf(specs), Map.copyOf(owners), List.copyOf(names));
    }

    /**
     * 用一组全新的工具对象重建工具箱，**复用当前的执行资源**（线程池 / 重试策略 / 指标 / 超时配置）。
     * P9 多 Agent 编排用它给 Supervisor 装上「只有委派工具」的工具箱。
     */
    public AgentToolbox withTools(List<Object> toolObjects) {
        BuildResult built = buildFrom(toolObjects);
        return new AgentToolbox(built.specifications(), built.ownerByTool(), built.toolNames(),
                this.toolExecutor, this.toolTimeoutMs, this.toolMaxAttempts,
                this.retryPolicy, this.metrics);
    }

    /**
     * 派生一个只含指定工具的**子工具箱**（P9 多 Agent 编排）。
     * 名单外的工具对专家**不可见** —— 这是「分工」的物理保证，而不是靠提示词约定。
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
        return new AgentToolbox(specs, owners, names,
                this.toolExecutor, this.toolTimeoutMs, this.toolMaxAttempts,
                this.retryPolicy, this.metrics);
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
     * 执行工具。
     *
     * Wave 1 行为：
     *   - 在独立线程池中执行并设**超时**（默认 15s），超时即中断并回灌超时说明；
     *   - 对**可重试错误**（IO / 超时类）做有限次退避重试；
     *   - 所有失败都转成以「工具「x」执行失败：」开头的文本回灌，模型可据此自纠或更换参数。
     */
    public String execute(ToolExecutionRequest request, String memoryId) {
        Object owner = ownerByTool.get(request.name());
        if (owner == null) {
            String msg = "错误：不存在名为「" + request.name() + "」的工具。当前可用工具：" + toolNames;
            log.warn(msg);
            return msg;
        }

        for (int attempt = 1; attempt <= toolMaxAttempts; attempt++) {
            Future<String> future = toolExecutor.submit(
                    () -> new DefaultToolExecutor(owner, request).execute(request, memoryId));
            try {
                return future.get(toolTimeoutMs, TimeUnit.MILLISECONDS);

            } catch (java.util.concurrent.TimeoutException e) {
                future.cancel(true);
                metrics.degrade("tool");
                log.warn("工具 [{}] 执行超时（>{}ms），已中断", request.name(), toolTimeoutMs);
                // 超时不重试：既无法确认下游是否已产生副作用，重试也会让延迟翻倍
                return "工具「" + request.name() + "」执行失败：执行超时（超过 " + toolTimeoutMs
                        + " 毫秒未返回）。请换用其它工具，或如实告知游客该信息暂时查询不到。";

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "工具「" + request.name() + "」执行失败：调用被中断。";

            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                String reason = rootMessage(cause);
                boolean retriable = retryPolicy.isRetriable(cause);
                boolean hasNextAttempt = attempt < toolMaxAttempts;

                if (retriable && hasNextAttempt) {
                    log.warn("工具 [{}] 第 {}/{} 次执行失败（可重试）：{}",
                            request.name(), attempt, toolMaxAttempts, reason);
                    if (!retryPolicy.backoff(attempt)) {
                        break;
                    }
                    continue;
                }

                metrics.degrade("tool");
                log.warn("工具 [{}] 执行失败（错误将回灌给模型自纠）: {}", request.name(), reason);
                return "工具「" + request.name() + "」执行失败：" + reason
                        + "。请据此调整：可以换用其他工具、调整参数重试，或者直接如实告知游客"
                        + "该信息暂时查询不到、并给出可行建议。不要编造结果。";
            }
        }

        metrics.degrade("tool");
        return "工具「" + request.name() + "」执行失败：重试 " + toolMaxAttempts + " 次仍未成功。"
                + "请换用其它工具，或如实告知游客该信息暂时查询不到。";
    }

    /** 工具执行线程池：有界队列 + CallerRuns 背压（满时由 Agent 线程自己执行，而不是丢任务） */
    private ExecutorService createToolPool() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                4, 16, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(128));
        pool.setThreadFactory(runnable -> {
            Thread thread = new Thread(runnable, "scenic-tool-" + THREAD_SEQ.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        pool.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        return pool;
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
