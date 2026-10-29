package com.scenic.ai.memory;

import com.scenic.ai.rag.store.VectorStoreAdmin;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
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
import java.util.Map;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * P8 · 长期记忆（pgvector）。
 *
 * 存游客的**事实性偏好**（例如「对历史文化感兴趣」「带小孩出行」「不想走太多路」），
 * 带向量存储，召回时按与当前问题的相关度排序 —— 所以跨会话问「推荐个路线」时，
 * 能把之前说过的偏好带上。
 *
 * key 说明：登录用户用 {@code user:{id}}，匿名用服务端签发的 visitorId；
 * 同一 key 的记忆彼此隔离。检索时按 memory_key 过滤，避免把 A 的偏好召回给 B。
 *
 * <h3>2026-10-29（D18）· 补上「只增不删」的治理</h3>
 *
 * <p>原实现只有 {@code remember} / {@code recall}，三个后果：
 *
 * <ol>
 *   <li><b>重复偏好占满召回名额</b>：同义句存两次，两条向量几乎重合，
 *       {@code top-k=3} 里有 2 个位置给同一件事，实际只召回 2 条有效信息。</li>
 *   <li><b>矛盾偏好共存</b>：去年「喜欢爬山」、今年「膝盖不好不想爬山」——
 *       这两句**向量相似度恰恰最高**（都在说爬山），一定一起被召回，
 *       而 {@code recall} 只返回文本、不返回时间，模型无法判断哪个是当前状态。</li>
 *   <li><b>表无限增长</b>：没有 TTL（Redis 短期记忆是 24h），每次 {@code remember}
 *       都要调一次 embedding API。</li>
 * </ol>
 *
 * <p>现在的治理：<b>语义去重</b>（相似度 ≥ 阈值视为同一条）+ <b>每 key 容量上限</b>
 * （超出按 {@code created_at} 淘汰最旧的）。
 *
 * <p><b>仍然没解决的</b>：矛盾偏好。去重只能消除「同义重复」，
 * 「喜欢爬山 vs 不能爬山」是两条不同的事实，都会被保留。
 * 彻底解决需要记忆版本化（新事实 supersedes 旧事实），那是另一个量级的工程 ——
 * 对景区导览场景不值得。这里选择**承认它**，而不是假装解决了。
 */
@Component
@Slf4j
public class LongTermMemory {

    /** 长期记忆所在的向量表（与 RagConfig#visitorMemoryStore 保持一致） */
    private static final String TABLE = "visitor_memory";
    private static final String KEY_MEMORY = "memory_key";
    private static final String KEY_CREATED_AT = "created_at";

    private final EmbeddingStore<TextSegment> store;
    private final EmbeddingModel embeddingModel;
    private final VectorStoreAdmin vectorStoreAdmin;
    private final int topK;
    private final double minScore;
    /** 判为「同一条偏好」的相似度阈值（D18） */
    private final double duplicateScore;
    /** 每个游客最多保留多少条偏好（D18） */
    private final int maxFactsPerVisitor;

    public LongTermMemory(@Lazy @Qualifier("visitorMemoryStore") EmbeddingStore<TextSegment> store,
                          EmbeddingModel embeddingModel,
                          VectorStoreAdmin vectorStoreAdmin,
                          @Value("${memory.long-term.top-k:3}") int topK,
                          @Value("${memory.long-term.min-score:0.55}") double minScore,
                          @Value("${memory.long-term.duplicate-score:0.95}") double duplicateScore,
                          @Value("${memory.long-term.max-facts-per-visitor:40}") int maxFactsPerVisitor) {
        this.store = store;
        this.embeddingModel = embeddingModel;
        this.vectorStoreAdmin = vectorStoreAdmin;
        this.topK = topK;
        this.minScore = minScore;
        this.duplicateScore = duplicateScore;
        this.maxFactsPerVisitor = Math.max(1, maxFactsPerVisitor);
        log.info("长期记忆就绪：topK={}, minScore={}, 去重阈值={}, 每人上限={} 条",
                topK, minScore, duplicateScore, this.maxFactsPerVisitor);
    }

    /**
     * 记住一条偏好。
     *
     * <p>三步：**语义去重 → 写入（带时间戳）→ 容量淘汰**。
     * 任何一步失败都只 warn，不阻断对话主流程 —— 记忆是增强，不是必需。
     */
    public void remember(String memoryKey, String fact) {
        if (memoryKey == null || memoryKey.isBlank() || fact == null || fact.isBlank()) {
            return;
        }
        try {
            Embedding embedding = embeddingModel.embed(fact).content();

            // ① 语义去重：同一件事用不同说法存两次，会在召回时占掉多个名额
            if (isDuplicate(memoryKey, embedding)) {
                return;
            }

            // ② 写入（metadata 带 created_at，容量淘汰时按它排序）
            Metadata metadata = Metadata.from(Map.of(
                    KEY_MEMORY, memoryKey,
                    "fact", fact,
                    KEY_CREATED_AT, String.valueOf(System.currentTimeMillis())
            ));
            TextSegment segment = TextSegment.from(fact, metadata);
            store.addAll(List.of(embedding), List.of(segment));
            log.info("[MEMORY] 长期记忆已保存（key={}）：{}", memoryKey, fact);

            // ③ 容量上限：表没有 TTL，不治理就会无限增长
            enforceCapacity(memoryKey);

        } catch (Exception e) {
            log.warn("长期记忆写入失败（不影响主流程）: {}", e.getMessage());
        }
    }

    /**
     * 语义去重：用同样的 embedding 反查该游客已有的记忆。
     *
     * <p>用「语义相似度」而不是「字符串相等」：模型每次的措辞都不一样
     * （「喜欢拍照」/「对摄影有兴趣」/「爱拍照」），字符串比对基本拦不住。
     */
    private boolean isDuplicate(String memoryKey, Embedding embedding) {
        try {
            List<EmbeddingMatch<TextSegment>> matches = store.search(
                    EmbeddingSearchRequest.builder()
                            .queryEmbedding(embedding)
                            .maxResults(1)
                            .minScore(duplicateScore)
                            .filter(metadataKey(KEY_MEMORY).isEqualTo(memoryKey))
                            .build()
            ).matches();
            if (matches.isEmpty()) {
                return false;
            }
            EmbeddingMatch<TextSegment> match = matches.get(0);
            log.info("[MEMORY] 已有等价偏好（score={}，原句「{}」），跳过写入",
                    String.format("%.3f", match.score()), match.embedded().text());
            return true;
        } catch (Exception e) {
            // 查重失败时**放行写入**：宁可重复一条，也不要因为查重挂了把记忆丢掉
            log.warn("长期记忆去重检查失败（按「不重复」处理，继续写入）: {}", e.getMessage());
            return false;
        }
    }

    private void enforceCapacity(String memoryKey) {
        try {
            long total = vectorStoreAdmin.countByMetadata(TABLE, KEY_MEMORY, memoryKey);
            if (total <= maxFactsPerVisitor) {
                return;
            }
            int deleted = vectorStoreAdmin.deleteOldestByMetadata(
                    TABLE, KEY_MEMORY, memoryKey, KEY_CREATED_AT, maxFactsPerVisitor);
            if (deleted > 0) {
                log.info("[MEMORY] key={} 的偏好条数 {} 超过上限 {}，已淘汰最旧 {} 条",
                        memoryKey, total, maxFactsPerVisitor, deleted);
            }
        } catch (Exception e) {
            // 淘汰失败不影响读写：最坏是表偏大
            log.warn("长期记忆容量淘汰失败（不影响读写）: {}", e.getMessage());
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
                            .filter(metadataKey(KEY_MEMORY).isEqualTo(memoryKey))
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
