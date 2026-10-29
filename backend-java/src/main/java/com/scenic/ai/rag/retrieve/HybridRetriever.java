package com.scenic.ai.rag.retrieve;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索（P4 核心）：向量路 + BM25 关键词路，用 **RRF** 融合，可选再接 rerank。
 *
 * RRF（Reciprocal Rank Fusion）不依赖两路分数量纲，只用排名：
 *   score(d) = Σ 1 / (k + rank_i(d))，k 取 60（论文推荐值）
 * 这正是它比"加权求和"更稳的原因 —— 向量相似度（0~1）和 BM25 分数（无上界）没法直接加权。
 */
@Service
@Slf4j
public class HybridRetriever {

    /** 检索模式，供 P4 对照实验使用 */
    public enum Mode {
        /** 纯向量（P3 现状） */
        VECTOR,
        /** 向量 + BM25 + RRF */
        HYBRID,
        /** 混合 + rerank */
        HYBRID_RERANK
    }

    public static final String VECTOR_KEY = "vector";
    public static final String HYBRID_KEY = "hybrid";
    public static final String HYBRID_RERANK_KEY = "hybrid+rerank";

    private static final int RRF_K = 60;

    private final RagRetriever vectorRetriever;
    private final Bm25Index bm25Index;
    private final Reranker reranker;
    private final int topK;
    private final int poolSize;
    private final int maxContextChars;
    private final Mode defaultMode;

    public HybridRetriever(RagRetriever vectorRetriever,
                           Bm25Index bm25Index,
                           Reranker reranker,
                           @org.springframework.beans.factory.annotation.Value("${rag.top-k:5}") int topK,
                           @org.springframework.beans.factory.annotation.Value("${rag.pool-size:20}") int poolSize,
                           @org.springframework.beans.factory.annotation.Value("${rag.max-context-chars:1500}") int maxContextChars,
                           @org.springframework.beans.factory.annotation.Value("${rag.mode:hybrid+rerank}") String modeName) {
        this.vectorRetriever = vectorRetriever;
        this.bm25Index = bm25Index;
        this.reranker = reranker;
        this.topK = topK;
        this.poolSize = Math.max(poolSize, topK);
        this.maxContextChars = maxContextChars;
        this.defaultMode = parseMode(modeName);
        log.info("混合检索默认模式: {}", defaultMode);
    }

    /** 对话链路入口：按 `rag.mode` 配置的模式检索并拼装上下文 */
    public String retrieveContext(String query) {
        return toContext(retrieve(query, defaultMode), maxContextChars);
    }

    private static Mode parseMode(String name) {
        if (name == null || name.isBlank()) {
            return Mode.HYBRID_RERANK;
        }
        return switch (name.trim().toLowerCase()) {
            case "vector" -> Mode.VECTOR;
            case "hybrid" -> Mode.HYBRID;
            default -> Mode.HYBRID_RERANK;
        };
    }

    public List<RagRetriever.RetrievedChunk> retrieve(String query, Mode mode) {
        if (mode == Mode.VECTOR) {
            return vectorRetriever.retrieve(query);
        }

        // 1. 向量路（取候选池，先不设阈值）
        List<RagRetriever.RetrievedChunk> vectorHits = vectorRetriever.retrieve(query, poolSize, 0.0);
        // 2. 关键词路
        // D11：search 直接返回带内容的 Hit，不再需要拿 index 回查 ——
        // 原来 chunkAt(lexicalHits.get(rank).index()) 是一个「跨快照会失效」的句柄，
        // reload 之后同一个下标可能指向另一条切片。
        // D24：关键词路与向量路取同样大的候选池（poolSize），
        // 否则 RRF 融合时两路候选数差 4 倍（5 vs 20），关键词路的长尾命中永远进不了池子。
        List<Bm25Index.Hit> lexicalHits = bm25Index.search(query, poolSize);

        // 3. RRF 融合
        Map<String, Double> fusedScores = new LinkedHashMap<>();
        Map<String, RagRetriever.RetrievedChunk> byText = new HashMap<>();

        for (int rank = 0; rank < vectorHits.size(); rank++) {
            RagRetriever.RetrievedChunk chunk = vectorHits.get(rank);
            byText.putIfAbsent(chunk.text(), chunk);
            fusedScores.merge(chunk.text(), 1.0 / (RRF_K + rank + 1), Double::sum);
        }
        for (int rank = 0; rank < lexicalHits.size(); rank++) {
            Bm25Index.Hit hit = lexicalHits.get(rank);
            String text = hit.chunk().text();
            byText.putIfAbsent(text,
                    new RagRetriever.RetrievedChunk(text, hit.chunk().docTitle(), hit.score()));
            fusedScores.merge(text, 1.0 / (RRF_K + rank + 1), Double::sum);
        }

        List<RagRetriever.RetrievedChunk> merged = fusedScores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .map(e -> byText.get(e.getKey()))
                .toList();

        log.info("混合检索：向量 {} 条 + BM25 {} 条 → RRF 融合 {} 条", vectorHits.size(), lexicalHits.size(), merged.size());

        if (mode == Mode.HYBRID) {
            return merged.stream().limit(topK).toList();
        }

        // 4. rerank（失败自动回退到融合结果）
        List<RagRetriever.RetrievedChunk> pool = merged.stream().limit(poolSize).toList();
        List<String> documents = pool.stream().map(RagRetriever.RetrievedChunk::text).toList();
        List<Reranker.Ranked> ranked = reranker.rerank(query, documents);

        if (ranked.isEmpty()) {
            return pool.stream().limit(topK).toList();
        }

        List<RagRetriever.RetrievedChunk> result = new ArrayList<>();
        for (Reranker.Ranked item : ranked) {
            if (item.index() < 0 || item.index() >= pool.size()) {
                continue;
            }
            RagRetriever.RetrievedChunk chunk = pool.get(item.index());
            result.add(new RagRetriever.RetrievedChunk(chunk.text(), chunk.docTitle(), item.relevanceScore()));
            if (result.size() >= topK) {
                break;
            }
        }
        return result;
    }

    /**
     * 把上下文拼装成注入提示词的文本（格式与 P3 一致）。
     *
     * <p>2026-10-29（D23）：超预算时**整块丢弃**，不再事后一刀切。
     * 原实现是拼完整个字符串再 {@code substring(0, maxContextChars)}，
     * 截断点可能落在半句话、甚至半个词中间 —— 模型会读到
     * 「【参考资料5】(来源: …) 灵山大佛通高 88」这种断头内容，
     * 比少给一条参考资料更糟：它看起来像一条完整信息，但语义不完整。
     *
     * <p>现在逐块累加，放不下就停在这一块之前。这样每一条注入的参考资料都是完整的。
     */
    public String toContext(List<RagRetriever.RetrievedChunk> chunks, int maxContextChars) {
        if (chunks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int i = 0; i < chunks.size(); i++) {
            RagRetriever.RetrievedChunk chunk = chunks.get(i);
            String block = "【参考资料" + (i + 1) + "】(来源: " + chunk.docTitle() + ")\n" + chunk.text();
            int cost = block.length() + (sb.length() > 0 ? 2 : 0);
            if (used + cost > maxContextChars) {
                if (sb.length() == 0) {
                    // 第一条就超预算：仍然给它，否则「预算配小了」会变成「完全没有参考资料」。
                    // 这是唯一保留的「超预算」路径，属于配置异常时的兜底。
                    log.warn("上下文预算 {} 字小于最小切片（{} 字），仍注入第一条", maxContextChars, cost);
                    sb.append(block);
                } else {
                    log.info("上下文预算 {} 字：自第 {} 条起放不下，本轮注入 {} 条",
                            maxContextChars, i + 1, i);
                }
                break;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(block);
            used += cost;
        }
        return sb.toString();
    }
}
