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
        List<Bm25Index.Scored> lexicalHits = bm25Index.search(query);

        // 3. RRF 融合
        Map<String, Double> fusedScores = new LinkedHashMap<>();
        Map<String, RagRetriever.RetrievedChunk> byText = new HashMap<>();

        for (int rank = 0; rank < vectorHits.size(); rank++) {
            RagRetriever.RetrievedChunk chunk = vectorHits.get(rank);
            byText.putIfAbsent(chunk.text(), chunk);
            fusedScores.merge(chunk.text(), 1.0 / (RRF_K + rank + 1), Double::sum);
        }
        for (int rank = 0; rank < lexicalHits.size(); rank++) {
            Bm25Index.Chunk chunk = bm25Index.chunkAt(lexicalHits.get(rank).index());
            byText.putIfAbsent(chunk.text(),
                    new RagRetriever.RetrievedChunk(chunk.text(), chunk.docTitle(), lexicalHits.get(rank).score()));
            fusedScores.merge(chunk.text(), 1.0 / (RRF_K + rank + 1), Double::sum);
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

    /** 把上下文拼装成注入提示词的文本（格式与 P3 一致） */
    public String toContext(List<RagRetriever.RetrievedChunk> chunks, int maxContextChars) {
        if (chunks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            RagRetriever.RetrievedChunk chunk = chunks.get(i);
            sb.append("【参考资料").append(i + 1).append("】(来源: ")
                    .append(chunk.docTitle()).append(")\n")
                    .append(chunk.text());
        }
        String context = sb.toString();
        return context.length() > maxContextChars ? context.substring(0, maxContextChars) + "..." : context;
    }
}
