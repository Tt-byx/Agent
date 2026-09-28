package com.scenic.ai.gateway;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * P8 · 语义缓存。
 *
 * 原理：把「问题向量 -> 已生成的答案」存进 pgvector；新问题来了先算向量，
 * 若与缓存中的某个问题相似度 ≥ 阈值，就直接返回那份答案，**跳过整条 Agent 链路**
 * （不检索、不调工具、不调模型）。
 *
 * 收益是双重的：
 *   1. 延迟从秒级降到毫秒级；
 *   2. 省掉一次完整的模型调用费用 —— 这是 P8 验收里「单次调用成本」能降下来的关键。
 *
 * 阈值取 0.92（很高）：宁可少命中，也不能把「灵山大佛多高」错命中成「灵山梵宫多高」。
 */
@Component
@Slf4j
public class SemanticCache {

    private final EmbeddingStore<TextSegment> store;
    private final EmbeddingModel embeddingModel;
    private final boolean enabled;
    private final double minScore;

    public SemanticCache(@Lazy @Qualifier("semanticCacheStore") EmbeddingStore<TextSegment> store,
                         EmbeddingModel embeddingModel,
                         @Value("${cache.semantic.enabled:true}") boolean enabled,
                         @Value("${cache.semantic.min-score:0.92}") double minScore) {
        this.store = store;
        this.embeddingModel = embeddingModel;
        this.enabled = enabled;
        this.minScore = minScore;
        log.info("语义缓存就绪：enabled={}, minScore={}", enabled, minScore);
    }

    /** 命中则返回缓存的答案 */
    public Optional<String> lookup(String query) {
        if (!enabled || query == null || query.isBlank()) {
            return Optional.empty();
        }
        try {
            List<EmbeddingMatch<TextSegment>> matches = store.search(
                    EmbeddingSearchRequest.builder()
                            .queryEmbedding(embeddingModel.embed(query).content())
                            .maxResults(1)
                            .minScore(minScore)
                            .build()
            ).matches();
            if (matches.isEmpty()) {
                return Optional.empty();
            }
            String answer = matches.get(0).embedded().text();
            log.info("[CACHE] 命中（score={}）：{}", String.format("%.3f", matches.get(0).score()),
                    query);
            return Optional.ofNullable(answer);
        } catch (Exception e) {
            log.warn("语义缓存查询失败（降级为未命中）: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 写入缓存。注意：**向量化的是问题**（它是检索的键），答案作为文本载荷返回 */
    public void store(String query, String answer) {
        if (!enabled || query == null || query.isBlank()
                || answer == null || answer.isBlank()) {
            return;
        }
        try {
            Metadata metadata = Metadata.from(java.util.Map.of("query", query));
            TextSegment segment = TextSegment.from(answer, metadata);
            store.addAll(List.of(embeddingModel.embed(query).content()), List.of(segment));
            log.info("[CACHE] 已缓存问答对（query={}）", query);
        } catch (Exception e) {
            log.warn("语义缓存写入失败: {}", e.getMessage());
        }
    }

    public boolean isEnabled() {
        return enabled;
    }
}
