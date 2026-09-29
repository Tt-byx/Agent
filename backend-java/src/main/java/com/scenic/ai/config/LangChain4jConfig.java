package com.scenic.ai.config;

import com.scenic.ai.gateway.AgentMetrics;
import com.scenic.ai.gateway.RoutedChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
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

    @Value("${llm.fallback.enabled:true}")
    private boolean fallbackEnabled;

    @Value("${llm.fallback.base-url:https://api.deepseek.com/v1}")
    private String fallbackBaseUrl;

    @Value("${llm.fallback.api-key:${SCENIC_FALLBACK_API_KEY:}}")
    private String fallbackApiKey;

    @Value("${llm.fallback.model:deepseek-v4-pro}")
    private String fallbackModel;

    @Bean
    public ChatModel chatModel(AgentMetrics metrics) {
        warnIfKeyMissing();

        // P8 · 主备路由：DeepSeek 同厂商双档 —— 主 flash（快、省），备 pro（更强）。
        // 主模型异常时自动切备，保可用性优先；硅基流动的 key 只用于 embedding/rerank，不做对话。
        List<RoutedChatModel.Route> routes = new ArrayList<>();
        routes.add(new RoutedChatModel.Route(
                "primary:" + chatModelName,
                OpenAiChatModel.builder()
                        .baseUrl(baseUrl)
                        .apiKey(effectiveApiKey())
                        .modelName(chatModelName)
                        .temperature(temperature)
                        .maxTokens(maxTokens)
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .maxRetries(maxRetries)
                        .logRequests(false)
                        .logResponses(false)
                        .build()));

        if (fallbackEnabled && fallbackApiKey != null && !fallbackApiKey.isBlank()) {
            routes.add(new RoutedChatModel.Route(
                    "fallback:" + fallbackModel,
                    OpenAiChatModel.builder()
                            .baseUrl(fallbackBaseUrl)
                            .apiKey(fallbackApiKey)
                            .modelName(fallbackModel)
                            .temperature(temperature)
                            .maxTokens(maxTokens)
                            .timeout(Duration.ofSeconds(timeoutSeconds))
                            .maxRetries(1)
                            .logRequests(false)
                            .logResponses(false)
                            .build()));
            log.info("[ROUTE] 已启用备用模型：{} @ {}", fallbackModel, fallbackBaseUrl);
        } else {
            log.warn("[ROUTE] 未配置备用模型（llm.fallback.api-key 为空），主模型故障时将直接报错");
        }

        return new RoutedChatModel(routes, metrics);
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
