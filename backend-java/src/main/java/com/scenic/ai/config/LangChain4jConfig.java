package com.scenic.ai.config;

import com.scenic.ai.gateway.AgentMetrics;
import com.scenic.ai.gateway.ModelRoute;
import com.scenic.ai.gateway.RetryPolicy;
import com.scenic.ai.gateway.RoutedChatModel;
import com.scenic.ai.gateway.RoutedStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * LangChain4j 装配：大模型客户端（同步 + 流式两条路径，共享同一批熔断器）。
 *
 * 模型走 OpenAI 兼容协议，替换 base-url / chat-model 即可切到 MiMo / DeepSeek / 百炼。
 * 密钥不写进仓库：来自环境变量或 application-local.yml（见 application.yml 的注释）。
 */
@Configuration
@Slf4j
public class LangChain4jConfig {

    @Value("${llm.base-url}")
    private String baseUrl;

    @Value("${llm.api-key:}")
    private String apiKey;

    @Value("${llm.chat-model}")
    private String chatModelName;

    @Value("${llm.temperature:0.7}")
    private Double temperature;

    @Value("${llm.max-tokens:1024}")
    private Integer maxTokens;

    @Value("${llm.timeout-seconds:60}")
    private Long timeoutSeconds;

    @Value("${llm.max-retries:2}")
    private Integer maxRetries;

    /** Wave 5 · 流式整体等待上限（单次模型调用的硬超时，防止流卡住不返回） */
    @Value("${llm.stream-wait-timeout-seconds:180}")
    private long streamWaitTimeoutSeconds;

    // Wave 1 · 熔断参数
    @Value("${llm.circuit-breaker.sliding-window-size:10}")
    private int cbSlidingWindowSize;

    @Value("${llm.circuit-breaker.minimum-number-of-calls:5}")
    private int cbMinimumCalls;

    @Value("${llm.circuit-breaker.failure-rate-threshold:50}")
    private float cbFailureRateThreshold;

    @Value("${llm.circuit-breaker.wait-duration-seconds:30}")
    private long cbWaitSeconds;

    @Value("${llm.circuit-breaker.half-open-calls:2}")
    private int cbHalfOpenCalls;

    /**
     * 2026-10-29（D28）· 慢调用也算故障。
     *
     * <p>只按失败率熔断会漏掉 LLM 最典型的故障模式：**变慢**。
     * 正常 2 秒、故障 30 秒，但状态码仍是 200 —— 失败率 0%，熔断器永远不打开，
     * 每个用户都白等 30 秒。这比直接报错更糟：报错至少能快速降级。
     *
     * <p>阈值定在 40 秒是有意的：一次长回答 10~20 秒是正常的，压到 20 秒会误熔断；
     * 40 秒基本只可能出现在下游拥塞时。慢调用率 80% 才打开，留出偶发抖动的余量。
     */
    @Value("${llm.circuit-breaker.slow-call-duration-seconds:40}")
    private long cbSlowCallSeconds;

    @Value("${llm.circuit-breaker.slow-call-rate-threshold:80}")
    private float cbSlowCallRateThreshold;

    @Value("${llm.fallback.enabled:true}")
    private boolean fallbackEnabled;

    @Value("${llm.fallback.base-url:https://api.deepseek.com/v1}")
    private String fallbackBaseUrl;

    @Value("${llm.fallback.api-key:${SCENIC_FALLBACK_API_KEY:}}")
    private String fallbackApiKey;

    @Value("${llm.fallback.model:deepseek-v4-pro}")
    private String fallbackModel;

    // ───────────────────────── 同步路径（/api/chat） ─────────────────────────

    @Bean
    public ChatModel chatModel(AgentMetrics metrics, RetryPolicy retryPolicy,
                               CircuitBreakerRegistry circuitBreakerRegistry) {
        warnIfKeyMissing();

        // P8 · 主备路由：DeepSeek 同厂商双档 —— 主 flash（快、省），备 pro（更强）。
        // 主模型异常时自动切备，保可用性优先；硅基流动的 key 只用于 embedding/rerank，不做对话。
        // 2026-10-29（D02）：档位改为**显式声明**。原实现靠「候选名含 pro」判断强弱，
        // 而默认配置下主备都是 deepseek-v4-pro —— 两条都被判为强模型，排序结果与配置顺序一致，
        // 语义路由从未生效。现在 tier 写在这里，装配期还会校验档位是否真的分得开。
        // 2026-10-29（D15）：候选类型统一为 ModelRoute<ChatModel>，与流式路径共用一份定义。
        List<ModelRoute<ChatModel>> routes = new ArrayList<>();
        String primaryName = "primary:" + chatModelName;
        routes.add(new ModelRoute<>(
                primaryName,
                ModelRoute.Tier.FAST,
                OpenAiChatModel.builder()
                        .baseUrl(baseUrl)
                        .apiKey(effectiveApiKey())
                        .modelName(chatModelName)
                        .temperature(temperature)
                        .maxTokens(maxTokens)
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        // Wave 1：关闭内置「无差别重试」，改由 RetryPolicy 按错误类型分级重试
                        .maxRetries(0)
                        .logRequests(false)
                        .logResponses(false)
                        .build(),
                circuitBreakerRegistry.circuitBreaker(primaryName)));

        if (fallbackEnabled && fallbackApiKey != null && !fallbackApiKey.isBlank()) {
            String fallbackName = "fallback:" + fallbackModel;
            routes.add(new ModelRoute<>(
                    fallbackName,
                    ModelRoute.Tier.STRONG,
                    OpenAiChatModel.builder()
                            .baseUrl(fallbackBaseUrl)
                            .apiKey(fallbackApiKey)
                            .modelName(fallbackModel)
                            .temperature(temperature)
                            .maxTokens(maxTokens)
                            .timeout(Duration.ofSeconds(timeoutSeconds))
                            .maxRetries(0)
                            .logRequests(false)
                            .logResponses(false)
                            .build(),
                    circuitBreakerRegistry.circuitBreaker(fallbackName)));
            log.info("[ROUTE] 已启用备用模型：{} @ {}", fallbackModel, fallbackBaseUrl);
        } else {
            log.warn("[ROUTE] 未配置备用模型（llm.fallback.api-key 为空），主模型故障时将直接报错");
        }

        warnIfRoutingDegraded(routes, chatModelName, fallbackModel);

        log.info("[ROUTE] 熔断参数：窗口 {} 次 / 最少样本 {} 次 / 失败率阈值 {}% / "
                        + "慢调用 {}s 以上且占比 {}% / 打开 {}s / 半开放行 {} 次",
                cbSlidingWindowSize, cbMinimumCalls, cbFailureRateThreshold,
                cbSlowCallSeconds, cbSlowCallRateThreshold, cbWaitSeconds, cbHalfOpenCalls);

        return new RoutedChatModel(routes, metrics, retryPolicy);
    }

    // ───────────────────────── 流式路径（/api/chat/stream，Wave 5） ─────────────────────────

    /**
     * Wave 5 · 流式模型（SSE 用）。
     *
     * 与同步路径**共享同一批熔断器**（同一个 Registry、同样的候选名）：
     * 主模型故障时，无论请求走哪条路径都会被跳过，健康状态是一致的 ——
     * 这是「两条路径并存」而不是「用体验换可靠性」的关键。
     *
     * 用 {@link OpenAiStreamingChatModel} 而不是给同步模型套壳：
     * 只有真正的流式客户端才能把首字延迟（TTFT）前移到「模型吐出第一个 token」的时刻。
     */
    @Bean
    public StreamingChatModel streamingChatModel(AgentMetrics metrics, RetryPolicy retryPolicy,
                                                 CircuitBreakerRegistry circuitBreakerRegistry) {
        // 2026-10-29（D15）：与同步路径共用 ModelRoute，档位也保持一致
        List<ModelRoute<StreamingChatModel>> routes = new ArrayList<>();

        String primaryName = "primary:" + chatModelName;
        routes.add(new ModelRoute<>(
                primaryName,
                ModelRoute.Tier.FAST,
                OpenAiStreamingChatModel.builder()
                        .baseUrl(baseUrl)
                        .apiKey(effectiveApiKey())
                        .modelName(chatModelName)
                        .temperature(temperature)
                        .maxTokens(maxTokens)
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .logRequests(false)
                        .logResponses(false)
                        .build(),
                circuitBreakerRegistry.circuitBreaker(primaryName)));

        if (fallbackEnabled && fallbackApiKey != null && !fallbackApiKey.isBlank()) {
            String fallbackName = "fallback:" + fallbackModel;
            routes.add(new ModelRoute<>(
                    fallbackName,
                    ModelRoute.Tier.STRONG,
                    OpenAiStreamingChatModel.builder()
                            .baseUrl(fallbackBaseUrl)
                            .apiKey(fallbackApiKey)
                            .modelName(fallbackModel)
                            .temperature(temperature)
                            .maxTokens(maxTokens)
                            .timeout(Duration.ofSeconds(timeoutSeconds))
                            .logRequests(false)
                            .logResponses(false)
                            .build(),
                    circuitBreakerRegistry.circuitBreaker(fallbackName)));
        }

        return new RoutedStreamingChatModel(routes, metrics, retryPolicy, streamWaitTimeoutSeconds);
    }

    // ───────────────────────── 共享的熔断器注册表 ─────────────────────────

    /**
     * Wave 1 建立、Wave 5 提升为 Bean · 熔断器注册表。
     *
     * 提升为 Bean 的原因：**同步与流式两条路径必须共享同一批熔断器**。
     * 如果各自 new 一个 Registry，主模型故障时会出现「同步路径已熔断、流式路径还在傻打它」的割裂状态。
     */
    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(cbSlidingWindowSize)
                .minimumNumberOfCalls(cbMinimumCalls)
                .failureRateThreshold(cbFailureRateThreshold)
                // D28：慢调用与失败一样，都是「这个候选现在不能用」的信号
                .slowCallDurationThreshold(Duration.ofSeconds(cbSlowCallSeconds))
                .slowCallRateThreshold(cbSlowCallRateThreshold)
                .waitDurationInOpenState(Duration.ofSeconds(cbWaitSeconds))
                .permittedNumberOfCallsInHalfOpenState(cbHalfOpenCalls)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
        return CircuitBreakerRegistry.of(config);
    }

    /*
     * P7 的取舍与 Wave 5 的更新（ADR）：
     *   P7 曾移除 streamingChatModel，选择「整段推送」换取循环可控性 ——
     *   因为 AiServices 的内层工具循环不暴露「最大步数 / 整体超时 / 逐步可观测」三个旋钮。
     *
     *   Wave 5 的做法不是推翻 P7，而是**两条路径并存**：
     *     /api/chat        保持非流式（稳定、可控，服务端可完整掌握每一步）
     *     /api/chat/stream 走 SSE 真流式（首字延迟前移，代价是「首包后不可重试/切换」）
     *   两条路径复用同一个 AgentLoop 骨架（工具执行、记忆、缓存、收口逻辑全部共享），
     *   只有「模型调用的输出方式」不同。这样既拿到了流式体验，也没有退化原链路的可靠性。
     *
     *   工具声明仍然复用 @Tool 注解 —— AgentLoop 用 ToolSpecifications + DefaultToolExecutor
     *   自己组装循环，从未依赖 AiServices。
     */

    /**
     * LangChain4j 会校验 apiKey 非空，为空时直接抛异常会让整个应用起不来。
     * 这里放哨兵值保证可启动；真正调用时由模型端返回 401，业务层兜底成友好提示。
     */
    private String effectiveApiKey() {
        return (apiKey == null || apiKey.isBlank()) ? "not-configured" : apiKey;
    }

    /**
     * 2026-10-29（D02）· 语义路由有效性校验。两类失效都要在启动期吼出来：
     *
     * <ol>
     *   <li><b>档位分不开</b>：所有候选同一档 → {@code orderByComplexity} 排序结果等于配置顺序，
     *       路由看起来在工作、实际零效果。</li>
     *   <li><b>档位分开了但模型是同一个</b>：主备都配成 {@code deepseek-v4-pro} 就是这种情况 ——
     *       排序真的会变，但两个候选指向同一个模型，成本/质量分层依然拿不到。
     *       这是默认配置下的真实状态，比第 1 类更隐蔽：日志显示"候选顺序已调整"，
     *       看起来一切正常。</li>
     * </ol>
     */
    private void warnIfRoutingDegraded(List<? extends ModelRoute<?>> routes,
                                       String primaryModel, String fallbackModel) {
        if (routes.size() < 2) {
            return;
        }
        long strong = routes.stream()
                .filter(r -> r.tier() == ModelRoute.Tier.STRONG)
                .count();
        boolean tiersIndistinguishable = strong == 0 || strong == routes.size();
        boolean sameModel = primaryModel != null && primaryModel.equals(fallbackModel);

        if (!tiersIndistinguishable && !sameModel) {
            return;
        }

        log.warn("========================================================");
        if (tiersIndistinguishable) {
            log.warn(" [ROUTE] 语义路由不会生效：{} 条候选全部是 {} 档位", routes.size(),
                    strong == 0 ? "FAST" : "STRONG");
        }
        if (sameModel) {
            log.warn(" [ROUTE] 主备是同一个模型（{}）：档位不同，但候选指向同一个模型 ——",
                    primaryModel);
            log.warn("   语义路由的排序会变，成本与质量分层却拿不到任何收益。");
            log.warn("   要让双档真正生效，把 llm.chat-model 配成快模型（如 deepseek-v4-flash），");
            log.warn("   llm.fallback.model 保持强模型（如 deepseek-v4-pro）。");
        }
        log.warn("  候选：{}", routes.stream().map(r -> r.name() + " / " + r.tier()).toList());
        log.warn("========================================================");
    }

    private void warnIfKeyMissing() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("========================================================");
            log.warn(" 未配置大模型 API Key，AI 对话将不可用。请任选一种方式：");
            log.warn("   1) 设置环境变量 SCENIC_LLM_API_KEY=xxx");
            log.warn("   2) 新建 backend-java/src/main/resources/application-local.yml:");
            log.warn("        llm:");
            log.warn("          api-key: xxx");
            log.warn(" 当前 base-url = {} , chat-model = {}", baseUrl, chatModelName);
            log.warn("========================================================");
        }
    }
}
