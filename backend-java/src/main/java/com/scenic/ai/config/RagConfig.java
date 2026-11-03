package com.scenic.ai.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
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

import javax.sql.DataSource;
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

    /**
     * 2026-11-03 新增：pgvector 连接超时。
     *
     * <p>此前三个 store 都用 {@code PgVectorEmbeddingStore.builder().host(...)} 那套，
     * 内部会自建 HikariCP，**connectionTimeout 取默认的 30 秒**。
     * 实测停掉 pgvector 后，一次问答从 3.8s 涨到 32.75s —— 等的就是这个超时。
     *
     * <p>RAG 是「增强」不是「必需」，依赖挂掉时必须快速降级。
     * 5 秒已经覆盖正常建连（本地 <50ms），也不会让用户干等半分钟。
     */
    @Value("${pgvector.connect-timeout-ms:5000}")
    private Long pgConnectTimeoutMs;

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

    /**
     * 三个向量 store 共用的数据源。
     *
     * <p>单独抽出来是为了**控制连接超时**：builder().host(...) 那套内部自建 HikariCP，
     * connectionTimeout 只能用默认 30 秒。这里显式建池，把它压到 5 秒。
     *
     * <p>{@code initializationFailTimeout(-1)}：pgvector 没起来也允许应用启动，
     * 真正的失败留到第一次用时由上层 catch 降级（与原来 @Lazy 的意图一致）。
     */
    /**
     * pgvector 连接池的持有者。
     *
     * <p><b>为什么不用 {@code @Bean DataSource} 直接暴露</b>：
     * Spring Boot 的 {@code DataSourceAutoConfiguration} 带
     * {@code @ConditionalOnMissingBean(DataSource.class)} —— 只要容器里出现**任何**
     * DataSource bean，MySQL 的自动配置就整体退让。实测直接把 HikariDataSource 注册成
     * bean 之后，MyBatis 拿到了 pgvector 的数据源，查 MySQL 的 conversation 表时报
     * {@code relation "conversation" does not exist}，整个应用 500。
     *
     * <p>所以这里用一个**自定义类型**包装，既能让三个 store 共用同一个池、
     * 又能自己控制 close，还不干扰自动配置。
     */
    public static class PgVectorDataSourceHolder {
        private final HikariDataSource dataSource;

        PgVectorDataSourceHolder(HikariDataSource dataSource) {
            this.dataSource = dataSource;
        }

        public DataSource dataSource() {
            return dataSource;
        }

        @jakarta.annotation.PreDestroy
        public void close() {
            dataSource.close();
        }
    }

    @Bean
    @Lazy
    public PgVectorDataSourceHolder pgVectorDataSourceHolder() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:postgresql://" + pgHost + ":" + pgPort + "/" + pgDatabase);
        cfg.setUsername(pgUser);
        cfg.setPassword(pgPassword);
        cfg.setMaximumPoolSize(4);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(pgConnectTimeoutMs);   // 关键：30s -> 5s
        cfg.setValidationTimeout(Math.min(pgConnectTimeoutMs, 2000));
        cfg.setInitializationFailTimeout(-1);           // 不因 pgvector 未就绪而启动失败
        cfg.setPoolName("scenic-pgvector");
        log.info("初始化 pgvector 连接池: {}:{}/{}，连接超时 {}ms",
                pgHost, pgPort, pgDatabase, pgConnectTimeoutMs);
        return new PgVectorDataSourceHolder(new HikariDataSource(cfg));
    }

    @Bean
    @Primary
    @Lazy
    public EmbeddingStore<TextSegment> embeddingStore(PgVectorDataSourceHolder pgHolder) {
        log.info("初始化 pgvector 向量库: table={}, dim={}", pgTable, pgDimension);
        return PgVectorEmbeddingStore.datasourceBuilder()
                .datasource(pgHolder.dataSource())
                .table(pgTable)
                .dimension(pgDimension)
                .createTable(pgCreateTable)
                .build();
    }

    /** P8 · 长期记忆：游客偏好（按人存储的事实性偏好，带向量便于按相关度召回） */
    @Bean(name = "visitorMemoryStore")
    @Lazy
    public EmbeddingStore<TextSegment> visitorMemoryStore(PgVectorDataSourceHolder pgHolder) {
        log.info("初始化长期记忆向量库: table=visitor_memory, dim={}", pgDimension);
        return PgVectorEmbeddingStore.datasourceBuilder()
                .datasource(pgHolder.dataSource())
                .table("visitor_memory")
                .dimension(pgDimension)
                .createTable(true)
                .build();
    }

    /** P8 · 语义缓存：问题向量 -> 已生成答案，命中后跳过模型调用 */
    @Bean(name = "semanticCacheStore")
    @Lazy
    public EmbeddingStore<TextSegment> semanticCacheStore(PgVectorDataSourceHolder pgHolder) {
        log.info("初始化语义缓存向量库: table=semantic_cache, dim={}", pgDimension);
        return PgVectorEmbeddingStore.datasourceBuilder()
                .datasource(pgHolder.dataSource())
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
