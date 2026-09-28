package com.scenic.ai.config;

import com.scenic.ai.agent.ScenicAssistant;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * LangChain4j 装配：大模型客户端 + 声明式 AiService。
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

    @Bean
    public ChatModel chatModel() {
        warnIfKeyMissing();
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(effectiveApiKey())
                .modelName(chatModelName)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .maxRetries(maxRetries)
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    @Bean
    public StreamingChatModel streamingChatModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(effectiveApiKey())
                .modelName(chatModelName)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    /**
     * 声明式 AiService。P7 之前它就是「单轮模型调用」，
     * 后续 RAG（P3）/ 工具（P5）/ 记忆（P8）都挂在这一层之上。
     */
    @Bean
    public ScenicAssistant scenicAssistant(ChatModel chatModel, StreamingChatModel streamingChatModel) {
        return AiServices.builder(ScenicAssistant.class)
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .build();
    }

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
