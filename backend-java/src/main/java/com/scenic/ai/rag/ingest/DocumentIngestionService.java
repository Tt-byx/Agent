package com.scenic.ai.rag.ingest;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * 文档导入：解析 → 切分 → 向量化 → 入 pgvector。
 *
 * 对应 Python 侧 {@code document_parser + text_chunker + embedding_service + vector_store}
 * 四段串联。重复导入同一 docId 会先清旧向量，保证幂等。
 */
@Service
@Slf4j
public class DocumentIngestionService {

    private final DocumentTextExtractor extractor;
    private final DocumentChunker chunker;
    private final ObjectProvider<EmbeddingStore<TextSegment>> storeProvider;
    private final EmbeddingModel embeddingModel;

    public DocumentIngestionService(DocumentTextExtractor extractor,
                                    DocumentChunker chunker,
                                    ObjectProvider<EmbeddingStore<TextSegment>> storeProvider,
                                    EmbeddingModel embeddingModel) {
        this.extractor = extractor;
        this.chunker = chunker;
        this.storeProvider = storeProvider;
        this.embeddingModel = embeddingModel;
    }

    /** 导入结果 */
    public record IngestResult(Long docId, String title, int chunkCount, int textLength) {}

    public IngestResult ingest(Long docId, String docTitle, Path file, String fileType) throws IOException {
        String text = extractor.extract(file, fileType);
        List<DocumentChunker.Chunk> chunks = chunker.chunk(text);

        if (chunks.isEmpty()) {
            log.warn("文档 '{}' 解析后无有效内容，跳过入库", docTitle);
            return new IngestResult(docId, docTitle, 0, text.length());
        }

        EmbeddingStore<TextSegment> store = storeProvider.getObject();

        // 幂等：先清掉该文档的旧向量
        removeByDocId(docId);

        List<TextSegment> segments = new ArrayList<>(chunks.size());
        for (DocumentChunker.Chunk chunk : chunks) {
            Metadata metadata = Metadata.from(java.util.Map.of(
                    "doc_id", String.valueOf(docId),
                    "doc_title", docTitle == null ? "" : docTitle,
                    "chunk_index", String.valueOf(chunk.index())
            ));
            segments.add(TextSegment.from(chunk.text(), metadata));
        }

        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        store.addAll(embeddings, segments);

        log.info("文档 '{}' (id={}) 入库完成：{} 个 chunk", docTitle, docId, segments.size());
        return new IngestResult(docId, docTitle, segments.size(), text.length());
    }

    /** 删除某文档的全部向量 */
    public void removeByDocId(Long docId) {
        try {
            storeProvider.getObject().removeAll(
                    metadataKey("doc_id").isEqualTo(String.valueOf(docId)));
            log.info("已清除文档 id={} 的旧向量", docId);
        } catch (Exception e) {
            // 清旧失败通常意味着本来就没有（或向量库未就绪），不阻断导入
            log.warn("清除文档 id={} 旧向量失败（可能本来就不存在）: {}", docId, e.getMessage());
        }
    }
}
