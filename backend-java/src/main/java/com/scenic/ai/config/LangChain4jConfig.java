package com.scenic.ai.config;

import com.scenic.ai.gateway.AgentMetrics;
import com.scenic.ai.gateway.RetryPolicy;
import com.scenic.ai.gateway.RoutedChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
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
 * LangChain4j 装配：大模型客户端（P8 起为主备路由）。
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

    @Value("${llm.fallback.enabled:true}")
    private boolean fallbackEnabled;

    @Value("${llm.fallback.base-url:https://api.deepseek.com/v1}")
    private String fallbackBaseUrl;

    @Value("${llm.fallback.api-key:${SCENIC_FALLBACK_API_KEY:}}")
    private String fallbackApiKey;

    @Value("${llm.fallback.model:deepseek-v4-pro}")
    private String fallbackModel;

    @Bean
    public ChatModel chatModel(AgentMetrics metrics, RetryPolicy retryPolicy) {
        warnIfKeyMissing();

        // Wave 1 · 熔断：主备各自独立的失败率窗口。
        // 主模型持续故障时，熔断打开后请求**直接跳过它**，不再每次白打一次再切备。
        CircuitBreakerRegistry circuitBreakerRegistry = circuitBreakerRegistry();

        // P8 · 主备路由：DeepSeek 同厂商双档 —— 主 flash（快、省），备 pro（更强）。
        // 主模型异常时自动切备，保可用性优先；硅基流动的 key 只用于 embedding/rerank，不做对话。
        List<RoutedChatModel.Route> routes = new ArrayList<>();
        String primaryName = "primary:" + chatModelName;
        routes.add(new RoutedChatModel.Route(
                primaryName,
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
            routes.add(new RoutedChatModel.Route(
                    fallbackName,
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

        log.info("[ROUTE] 熔断参数：窗口 {} 次 / 最少样本 {} 次 / 失败率阈值 {}% / 打开 {}s / 半开放行 {} 次",
                cbSlidingWindowSize, cbMinimumCalls, cbFailureRateThreshold,
                cbWaitSeconds, cbHalfOpenCalls);

        return new RoutedChatModel(routes, metrics, retryPolicy);
    }

    /** Wave 1 · 熔断器注册表：主备各一个实例，共用一份配置 */
    private CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(cbSlidingWindowSize)
                .minimumNumberOfCalls(cbMinimumCalls)
                .failureRateThreshold(cbFailureRateThreshold)
                .waitDurationInOpenState(Duration.ofSeconds(cbWaitSeconds))
                .permittedNumberOfCallsInHalfOpenState(cbHalfOpenCalls)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
        return CircuitBreakerRegistry.of(config);
    }

    // P7：原先这里还有两个 Bean —— streamingChatModel 与声明式 AiService（ScenicAssistant），
    // 二者都已随 AgentLoop 的引入而不再需要：
    //   - AiServices 的内层工具循环**不暴露**「最大步数 / 整体超时 / 逐步可观测」这三样 P7 必需的旋钮；
    //   - 因此 AgentLoop 直接用 ChatModel + ToolSpecifications + DefaultToolExecutor 自己组装循环，
    //     并且**仍然复用 @Tool 注解**，工具声明不用重写一遍。
    // 若将来要恢复逐 token 流式，把 streamingChatModel 加回来即可。

    /**
     * LangChain4j 会校验 apiKey 非空，为空时直接抛异常会让整个应用起不来。
     * 这里放哨兵值保证可启动；真正调用时由模型端返回 401，业务层兜底成友好提示。
     */
    private String effectiveApiKey() {
        return (apiKey == null || apiKey.isBlank()) ? "not-configured" : apiKey;
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
