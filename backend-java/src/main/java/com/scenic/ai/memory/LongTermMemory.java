package com.scenic.ai.memory;

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

import java.util.ArrayList;
import java.util.List;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * P8 · 长期记忆（pgvector）。
 *
 * 存游客的**事实性偏好**（例如「对历史文化感兴趣」「带小孩出行」「不想走太多路」），
 * 带向量存储，召回时按与当前问题的相关度排序 —— 所以跨会话问「推荐个路线」时，
 * 能把之前说过的偏好带上。
 *
 * key 说明：登录用户用 userId，匿名用 sessionId；同一 key 的记忆彼此隔离。
 * 检索时按 memory_key 过滤，避免把 A 的偏好召回给 B。
 */
@Component
@Slf4j
public class LongTermMemory {

    private final EmbeddingStore<TextSegment> store;
    private final EmbeddingModel embeddingModel;
    private final int topK;
    private final double minScore;

    public LongTermMemory(@Lazy @Qualifier("visitorMemoryStore") EmbeddingStore<TextSegment> store,
                          EmbeddingModel embeddingModel,
                          @Value("${memory.long-term.top-k:3}") int topK,
                          @Value("${memory.long-term.min-score:0.55}") double minScore) {
        this.store = store;
        this.embeddingModel = embeddingModel;
        this.topK = topK;
        this.minScore = minScore;
        log.info("长期记忆就绪：topK={}, minScore={}", topK, minScore);
    }

    /** 记住一条偏好 */
    public void remember(String memoryKey, String fact) {
        if (memoryKey == null || memoryKey.isBlank() || fact == null || fact.isBlank()) {
            return;
        }
        try {
            Metadata metadata = Metadata.from(java.util.Map.of(
                    "memory_key", memoryKey,
                    "fact", fact
            ));
            TextSegment segment = TextSegment.from(fact, metadata);
            store.addAll(List.of(embeddingModel.embed(segment).content()), List.of(segment));
            log.info("[MEMORY] 长期记忆已保存（key={}）：{}", memoryKey, fact);
        } catch (Exception e) {
            log.warn("长期记忆写入失败（不影响主流程）: {}", e.getMessage());
        }
    }

    /** 按当前问题召回相关偏好 */
    public List<String> recall(String memoryKey, String query) {
        List<String> facts = new ArrayList<>();
        if (memoryKey == null || memoryKey.isBlank() || query == null || query.isBlank()) {
            return facts;
        }
        try {
            List<EmbeddingMatch<TextSegment>> matches = store.search(
                    EmbeddingSearchRequest.builder()
                            .queryEmbedding(embeddingModel.embed(query).content())
                            .maxResults(topK)
                            .minScore(minScore)
                            .filter(metadataKey("memory_key").isEqualTo(memoryKey))
                            .build()
            ).matches();
            for (EmbeddingMatch<TextSegment> match : matches) {
                facts.add(match.embedded().text());
            }
            if (!facts.isEmpty()) {
                log.info("[MEMORY] 召回 {} 条偏好（key={}）：{}", facts.size(), memoryKey, facts);
            }
        } catch (Exception e) {
            log.warn("长期记忆召回失败（降级为无偏好）: {}", e.getMessage());
        }
        return facts;
    }
}
