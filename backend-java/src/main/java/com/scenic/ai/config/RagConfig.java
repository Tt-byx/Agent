package com.scenic.ai.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * RAG 装配：Embedding 模型 + pgvector 向量库。
 *
 * 向量库 bean 标了 {@code @Lazy}：pgvector 没启动时不应拖垮整个应用，
 * 让它延迟到第一次真正检索时再初始化，由 {@code RagRetriever} 兜底降级。
 */
@Configuration
@Slf4j
public class RagConfig {

    @Value("${embedding.base-url}")
    private String embeddingBaseUrl;

    @Value("${embedding.api-key:}")
    private String embeddingApiKey;

    @Value("${embedding.model-name}")
    private String embeddingModelName;

    @Value("${embedding.timeout-seconds:30}")
    private Long embeddingTimeoutSeconds;

    @Value("${embedding.max-retries:2}")
    private Integer embeddingMaxRetries;

    @Value("${pgvector.host}")
    private String pgHost;

    @Value("${pgvector.port:5432}")
    private Integer pgPort;

    @Value("${pgvector.database}")
    private String pgDatabase;

    @Value("${pgvector.user}")
    private String pgUser;

    @Value("${pgvector.password}")
    private String pgPassword;

    @Value("${pgvector.table:scenic_embeddings}")
    private String pgTable;

    @Value("${pgvector.dimension:1024}")
    private Integer pgDimension;

    @Value("${pgvector.create-table:true}")
    private Boolean pgCreateTable;

    @Bean
    public EmbeddingModel embeddingModel() {
        warnIfKeyMissing();
        return OpenAiEmbeddingModel.builder()
                .baseUrl(embeddingBaseUrl)
                .apiKey(effectiveApiKey())
                .modelName(embeddingModelName)
                .timeout(Duration.ofSeconds(embeddingTimeoutSeconds))
                .maxRetries(embeddingMaxRetries)
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    @Bean
    @Primary
    @Lazy
    public EmbeddingStore<TextSegment> embeddingStore() {
        log.info("初始化 pgvector 向量库: {}:{}/{} (table={}, dim={})",
                pgHost, pgPort, pgDatabase, pgTable, pgDimension);
        return PgVectorEmbeddingStore.builder()
                .host(pgHost)
                .port(pgPort)
                .database(pgDatabase)
                .user(pgUser)
                .password(pgPassword)
                .table(pgTable)
                .dimension(pgDimension)
                .createTable(pgCreateTable)
                .build();
    }

    /** P8 · 长期记忆：游客偏好（按人存储的事实性偏好，带向量便于按相关度召回） */
    @Bean(name = "visitorMemoryStore")
    @Lazy
    public EmbeddingStore<TextSegment> visitorMemoryStore() {
        log.info("初始化长期记忆向量库: table=visitor_memory, dim={}", pgDimension);
        return PgVectorEmbeddingStore.builder()
                .host(pgHost)
                .port(pgPort)
                .database(pgDatabase)
                .user(pgUser)
                .password(pgPassword)
                .table("visitor_memory")
                .dimension(pgDimension)
                .createTable(true)
                .build();
    }

    /** P8 · 语义缓存：问题向量 -> 已生成答案，命中后跳过模型调用 */
    @Bean(name = "semanticCacheStore")
    @Lazy
    public EmbeddingStore<TextSegment> semanticCacheStore() {
        log.info("初始化语义缓存向量库: table=semantic_cache, dim={}", pgDimension);
        return PgVectorEmbeddingStore.builder()
                .host(pgHost)
                .port(pgPort)
                .database(pgDatabase)
                .user(pgUser)
                .password(pgPassword)
                .table("semantic_cache")
                .dimension(pgDimension)
                .createTable(true)
                .build();
    }

    private String effectiveApiKey() {
        return (embeddingApiKey == null || embeddingApiKey.isBlank()) ? "not-configured" : embeddingApiKey;
    }

    private void warnIfKeyMissing() {
        if (embeddingApiKey == null || embeddingApiKey.isBlank()) {
            log.warn("========================================================");
            log.warn(" 未配置 Embedding API Key，知识库检索将不可用（问答会降级为无上下文模式）。");
            log.warn(" 请在项目根目录 .env 里设置 SCENIC_EMBEDDING_API_KEY。");
            log.warn(" 当前 base-url = {} , model = {}", embeddingBaseUrl, embeddingModelName);
            log.warn("========================================================");
        }
    }
}
