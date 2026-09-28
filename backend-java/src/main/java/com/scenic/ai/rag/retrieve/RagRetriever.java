package com.scenic.ai.rag.retrieve;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库检索 —— 对应 {@code backend-python/app/services/rag_service.py} 的 {@code retrieve_context}。
 *
 * 与 Python 实现的差异：
 *   1. 阈值语义相反 —— ChromaDB 用 cosine distance（越小越相关，上限 0.7），
 *      LangChain4j 返回 similarity score（越大越相关，下限 min-score）。
 *   2. 向量库 bean 用 ObjectProvider 延迟获取：pgvector 没起时只降级，不让应用崩。
 */
@Service
@Slf4j
public class RagRetriever {

    private final ObjectProvider<EmbeddingStore<TextSegment>> storeProvider;
    private final EmbeddingModel embeddingModel;

    private final int topK;
    private final int maxContextChars;
    private final double minScore;

    public RagRetriever(ObjectProvider<EmbeddingStore<TextSegment>> storeProvider,
                        EmbeddingModel embeddingModel,
                        @Value("${rag.top-k:5}") int topK,
                        @Value("${rag.max-context-chars:1500}") int maxContextChars,
                        @Value("${rag.min-score:0.3}") double minScore) {
        this.storeProvider = storeProvider;
        this.embeddingModel = embeddingModel;
        this.topK = topK;
        this.maxContextChars = maxContextChars;
        this.minScore = minScore;
    }

    /** 检索到的单条切片 */
    public record RetrievedChunk(String text, String docTitle, double score) {}

    /**
     * 检索原始结果（供 P4 评测集直接算 Recall@K 用）。
     * 任何异常都不抛出，返回空列表表示「本次无可用上下文」。
     */
    public List<RetrievedChunk> retrieve(String query) {
        return retrieve(query, topK, minScore);
    }

    /** 可指定条数与阈值的重载 —— 供 P4 混合检索取候选池使用 */
    public List<RetrievedChunk> retrieve(String query, int maxResults, double minScoreThreshold) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            EmbeddingStore<TextSegment> store = storeProvider.getObject();
            Embedding queryEmbedding = embeddingModel.embed(query).content();

            List<EmbeddingMatch<TextSegment>> matches = store.search(
                    EmbeddingSearchRequest.builder()
                            .queryEmbedding(queryEmbedding)
                            .maxResults(maxResults)
                            .minScore(minScoreThreshold)
                            .build()
            ).matches();

            List<RetrievedChunk> chunks = new ArrayList<>(matches.size());
            for (EmbeddingMatch<TextSegment> match : matches) {
                TextSegment segment = match.embedded();
                String docTitle = segment.metadata() == null
                        ? ""
                        : segment.metadata().getString("doc_title");
                chunks.add(new RetrievedChunk(
                        segment.text(),
                        docTitle == null ? "" : docTitle,
                        match.score()));
            }
            log.info("知识库检索到 {} 条相关资料（topK={}, minScore={}）", chunks.size(), topK, minScore);
            return chunks;

        } catch (Exception e) {
            log.warn("知识库检索失败，将使用无上下文模式: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 组装注入到系统提示词里的上下文文本，格式与 Python 侧保持一致：
     * {@code 【参考资料N】(来源: 文档名)\n正文}
     */
    public String retrieveContext(String query) {
        List<RetrievedChunk> chunks = retrieve(query);
        if (chunks.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            RetrievedChunk chunk = chunks.get(i);
            sb.append("【参考资料").append(i + 1).append("】(来源: ")
                    .append(chunk.docTitle()).append(")\n")
                    .append(chunk.text());
        }

        String context = sb.toString();
        if (context.length() > maxContextChars) {
            context = context.substring(0, maxContextChars) + "...";
            log.info("上下文超过 {} 字，已截断", maxContextChars);
        }
        return context;
    }
}
