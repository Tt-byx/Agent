package com.scenic.ai.gateway;

import com.scenic.ai.rag.store.VectorStoreAdmin;
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
import java.util.Map;
import java.util.Optional;

/**
 * 语义缓存。
 *
 * 原理：把「问题向量 -> 已生成的答案」存进 pgvector；新问题来了先算向量，
 * 若与缓存中的某个问题相似度 ≥ 阈值，就直接返回那份答案，**跳过整条 Agent 链路**
 * （不检索、不调工具、不调模型）。阈值取 0.92（很高）：宁可少命中，
 * 也不能把「灵山大佛多高」错命中成「灵山梵宫多高」。
 *
 * Wave 4 补上三块治理能力（原实现只有「读写 + 阈值」）：
 *
 *   1. **TTL**：写入时在 metadata 记 `cached_at`，命中后校验有效期。
 *      没有该字段的历史条目按**已过期**处理（首轮会自然淘汰，等价于一次冷启动）。
 *      没有用向量库 filter 实现，是因为 jsonb 里数值与字符串比较存在类型歧义，
 *      取回后在应用层校验更可控（`maxResults=3` 以跳过已过期的最近条目）。
 *
 *   2. **容量上限**：写入后若条目数超过 `max-entries`，按 `cached_at` 升序淘汰最旧的，
 *      避免缓存表无限增长（近似 LRU）。
 *
 *   3. **B4 决策（跨用户共享的安全阀）**：缓存表没有用户维度、答案是跨用户共享的。
 *      **决策：保留共享** —— 景区知识类答案与提问者无关，共享带来的是纯收益；
 *      但增加安全阀：**疑似包含个性化内容的答案不写入缓存**，
 *      避免「A 的个人化回答被 B 命中」。这是「收益保留 + 风险封堵」的折中，
 *      而不是简单地把 visitorId 加进缓存键（那样命中率会塌掉，得不偿失）。
 */
@Component
@Slf4j
public class SemanticCache {

    /** 疑似个性化内容的标记（命中任一即不缓存） */
    private static final List<String> PERSONAL_MARKERS = List.of(
            "你的偏好", "您的偏好", "你之前", "您之前", "根据你", "根据您",
            "你提到", "您提到", "为你推荐", "为您推荐", "记住你", "记住您",
            "你的历史", "您的历史", "你上次", "您上次");

    /**
     * 2026-10-29（D03）· 时效性内容标记：命中任一即不缓存。
     *
     * <p>这是「用过工具就不缓存」（{@code AgentLoop} 侧的主闸门）之外的**兜底**：
     * 模型有时会不打工具、直接凭上下文或记忆作答（"今天应该挺热的"），
     * 这种回答同样不可跨请求复用。宁可少缓存一条，也不能把过期信息发给下一位游客 ——
     * 缓存未命中的代价是多一次模型调用，缓存污染的代价是用户拿到错误答案。
     *
     * <p>代价说明：像「今天开放吗」这类本身稳定的问题也会被判为时效性而不缓存。
     * 这是**有意的**保守：正确性优先于命中率。
     */
    private static final List<String> REALTIME_MARKERS = List.of(
            "今天", "今日", "现在", "当前", "此刻", "目前", "实时",
            "刚才", "下一场", "本场", "还有约", "还剩");

    private static final String TABLE = "semantic_cache";
    private static final String TIMESTAMP_KEY = "cached_at";

    private final EmbeddingStore<TextSegment> store;
    private final EmbeddingModel embeddingModel;
    private final VectorStoreAdmin vectorStoreAdmin;
    private final AgentMetrics metrics;
    private final boolean enabled;
    private final double minScore;
    private final long ttlMillis;
    private final int maxEntries;

    public SemanticCache(@Lazy @Qualifier("semanticCacheStore") EmbeddingStore<TextSegment> store,
                         EmbeddingModel embeddingModel,
                         VectorStoreAdmin vectorStoreAdmin,
                         AgentMetrics metrics,
                         @Value("${cache.semantic.enabled:true}") boolean enabled,
                         @Value("${cache.semantic.min-score:0.92}") double minScore,
                         @Value("${cache.semantic.ttl-hours:24}") long ttlHours,
                         @Value("${cache.semantic.max-entries:5000}") int maxEntries) {
        this.store = store;
        this.embeddingModel = embeddingModel;
        this.vectorStoreAdmin = vectorStoreAdmin;
        this.metrics = metrics;
        this.enabled = enabled;
        this.minScore = minScore;
        this.ttlMillis = Math.max(1, ttlHours) * 3600_000L;
        this.maxEntries = Math.max(1, maxEntries);
        log.info("语义缓存就绪：enabled={}, minScore={}, TTL={}h, 容量上限={} 条",
                enabled, minScore, ttlHours, this.maxEntries);
    }

    /** 命中则返回缓存的答案（已校验 TTL 与阈值） */
    public Optional<String> lookup(String query) {
        if (!enabled || query == null || query.isBlank()) {
            return Optional.empty();
        }
        try {
            List<EmbeddingMatch<TextSegment>> matches = store.search(
                    EmbeddingSearchRequest.builder()
                            .queryEmbedding(embeddingModel.embed(query).content())
                            .maxResults(3)          // 取 3 条：最近的一条可能已过期，跳过它再取
                            .minScore(minScore)
                            .build()
            ).matches();

            for (EmbeddingMatch<TextSegment> match : matches) {
                if (isExpired(match.embedded().metadata())) {
                    log.debug("[CACHE] 命中但已超过 TTL，跳过该条");
                    continue;
                }
                String answer = match.embedded().text();
                log.info("[CACHE] 命中（score={}）：{}",
                        String.format("%.3f", match.score()), query);
                return Optional.ofNullable(answer);
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("语义缓存查询失败（降级为未命中）: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 写入缓存。注意：**向量化的是问题**（它是检索的键），答案作为文本载荷返回。
     *
     * 两道闸门：① 个性化答案不缓存（B4 安全阀）；② 写入后做容量淘汰。
     */
    public void store(String query, String answer) {
        if (!enabled || query == null || query.isBlank()
                || answer == null || answer.isBlank()) {
            return;
        }
        if (looksPersonalized(answer)) {
            log.info("[CACHE] 答案疑似含个性化内容，跳过缓存（跨用户共享安全阀）：{}", abbreviate(query));
            return;
        }
        // D03 · 第二道闸门：时效性内容不缓存（主闸门在 AgentLoop：用过工具的轮次不写缓存）
        if (looksRealtime(answer)) {
            log.info("[CACHE] 答案含时效性内容，跳过缓存：{}", abbreviate(query));
            return;
        }
        try {
            Metadata metadata = Metadata.from(Map.of(
                    "query", query,
                    TIMESTAMP_KEY, System.currentTimeMillis()
            ));
            TextSegment segment = TextSegment.from(answer, metadata);
            store.addAll(List.of(embeddingModel.embed(query).content()), List.of(segment));
            log.info("[CACHE] 已缓存问答对（query={}）", abbreviate(query));
            enforceCapacity();
        } catch (Exception e) {
            log.warn("语义缓存写入失败: {}", e.getMessage());
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 校验条目是否过期：没有 cached_at 的历史条目按已过期处理 */
    private boolean isExpired(Metadata metadata) {
        if (metadata == null) {
            return true;
        }
        String raw = metadata.getString(TIMESTAMP_KEY);
        if (raw == null || raw.isBlank()) {
            return true;
        }
        try {
            long cachedAt = Long.parseLong(raw);
            return System.currentTimeMillis() - cachedAt > ttlMillis;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    /** 容量上限：超出后按写入时间淘汰最旧的（近似 LRU） */
    private void enforceCapacity() {
        try {
            vectorStoreAdmin.deleteOldestEntries(TABLE, TIMESTAMP_KEY, maxEntries);
        } catch (Exception e) {
            // 淘汰失败不影响读写：最坏是缓存表偏大
            log.warn("语义缓存容量淘汰失败（不影响读写）: {}", e.getMessage());
        }
    }

    /**
     * B4 安全阀：答案是否疑似个性化。
     * 命中标记即不缓存 —— 保守策略：宁可少缓存一些，也不能让 A 的个人化回答被 B 命中。
     */
    private boolean looksPersonalized(String answer) {
        for (String marker : PERSONAL_MARKERS) {
            if (answer.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * D03 兜底闸门：答案是否含时效性内容。
     * 命中标记即不缓存 —— 见 {@link #REALTIME_MARKERS} 的代价说明。
     */
    private boolean looksRealtime(String answer) {
        for (String marker : REALTIME_MARKERS) {
            if (answer.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private String abbreviate(String text) {
        return text.length() > 30 ? text.substring(0, 30) + "..." : text;
    }
}
