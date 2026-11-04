package com.scenic.ai.rag.ingest;

import com.scenic.ai.exception.RedisShortCircuitException;
import com.scenic.ai.gateway.AgentMetrics;
import com.scenic.ai.rag.retrieve.Bm25Index;
import com.scenic.ai.rag.store.VectorStoreAdmin;
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
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 文档导入：解析 → 切分 → 向量化 → 入 pgvector。
 *
 * Wave 2 · 修 B2：**从「先删后写」改为「先写新、后删旧」**。
 *
 * 原实现是 `removeByDocId` → `embedAll` → `addAll`，中间任何一步失败都会让**旧数据已删、新数据没进**，
 * 且不可逆。这里把顺序重排为：
 *
 *   ① embedding（远程调用，最易失败）—— 失败时旧数据完好无损
 *   ② 快照旧向量 ID（PG 直连查询）
 *   ③ 写入新向量 —— 失败则回滚本次残留，仍保持「只有旧数据」
 *   ④ 精确删除旧 ID 对应的向量 —— 失败也不抛（最坏是检索结果短暂重复，重跑一次即自愈）
 *   ⑤ 重建关键词索引
 *
 * 为什么不上分布式事务：业务表在 MySQL、向量在 PostgreSQL，**跨库本地事务不成立**；
 * 为一次导入引入 Seata 这类 2PC 组件，代价远大于收益。这里用
 * 「顺序重排 + 补偿回滚 + 对账兜底（{@code /api/rag/reconcile}）」三层替代，
 * 把「不可逆丢数据」降级为「可自愈的重复」。
 */
@Service
@Slf4j
public class DocumentIngestionService {

    private final DocumentTextExtractor extractor;
    private final DocumentChunker chunker;
    private final ObjectProvider<EmbeddingStore<TextSegment>> storeProvider;
    private final EmbeddingModel embeddingModel;
    private final Bm25Index bm25Index;
    private final VectorStoreAdmin vectorStoreAdmin;
    private final AgentMetrics metrics;

    public DocumentIngestionService(DocumentTextExtractor extractor,
                                    DocumentChunker chunker,
                                    ObjectProvider<EmbeddingStore<TextSegment>> storeProvider,
                                    EmbeddingModel embeddingModel,
                                    Bm25Index bm25Index,
                                    VectorStoreAdmin vectorStoreAdmin,
                                    AgentMetrics metrics) {
        this.extractor = extractor;
        this.chunker = chunker;
        this.storeProvider = storeProvider;
        this.embeddingModel = embeddingModel;
        this.bm25Index = bm25Index;
        this.vectorStoreAdmin = vectorStoreAdmin;
        this.metrics = metrics;
    }

    /** 导入结果 */
    public record IngestResult(Long docId, String title, int chunkCount, int textLength) {}

    public IngestResult ingest(Long docId, String docTitle, Path file, String fileType) throws IOException {
        return ingest(docId, docTitle, file, fileType, null);
    }

    /**
     * 带进度回调的导入，供知识库上传流程回报阶段进度。
     * 回调参数为 (阶段名, 百分比)，阶段取值：parsing / chunking / embedding / storing / completed。
     */
    public IngestResult ingest(Long docId, String docTitle, Path file, String fileType,
                               BiConsumer<String, Integer> onProgress) throws IOException {
        report(onProgress, "parsing", 10);
        String text = extractor.extract(file, fileType);

        report(onProgress, "chunking", 30);
        List<DocumentChunker.Chunk> chunks = chunker.chunk(text);

        if (chunks.isEmpty()) {
            log.warn("文档 '{}' 解析后无有效内容，跳过入库", docTitle);
            return new IngestResult(docId, docTitle, 0, text.length());
        }

        EmbeddingStore<TextSegment> store = storeProvider.getObject();

        List<TextSegment> segments = new ArrayList<>(chunks.size());
        for (DocumentChunker.Chunk chunk : chunks) {
            Metadata metadata = Metadata.from(Map.of(
                    "doc_id", String.valueOf(docId),
                    "doc_title", docTitle == null ? "" : docTitle,
                    "chunk_index", String.valueOf(chunk.index())
            ));
            segments.add(TextSegment.from(chunk.text(), metadata));
        }

        // ① 先做最可能失败的远程调用；这一步抛出时，库里旧向量完全没被碰过
        report(onProgress, "embedding", 50);
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();

        // ② 写入前快照旧向量 ID（用于写入成功后精确删除）
        List<String> oldEmbeddingIds = vectorStoreAdmin.findEmbeddingIds(docId);

        // ③ 写入新向量
        report(onProgress, "storing", 85);
        try {
            store.addAll(embeddings, segments);
        } catch (Exception e) {
            RedisShortCircuitException.rethrowIf(e);   // 短路必须冒泡，不能被降级逻辑吞掉
            rollbackPartialWrite(store, docId, oldEmbeddingIds);
            throw new IOException("向量写入失败：" + e.getMessage(), e);
        }

        // ④ 新数据就位后才删旧数据
        removeOldEmbeddings(store, docId, oldEmbeddingIds);

        // ⑤ 向量库变了，关键词索引必须同步重建，否则 BM25 那一路检索不到新文档
        bm25Index.reload();

        report(onProgress, "completed", 100);
        log.info("文档 '{}' (id={}) 入库完成：{} 个 chunk（旧向量 {} 条）",
                docTitle, docId, segments.size(), oldEmbeddingIds.size());
        return new IngestResult(docId, docTitle, segments.size(), text.length());
    }

    /**
     * 删除文档及其全部向量，并同步刷新关键词索引。**失败会抛出**。
     *
     * <p>2026-10-29（D09）：这里刻意与导入流程的「清旧失败只告警」分开，两种语义不能混用 ——
     *
     * <ul>
     *   <li><b>导入</b>时新向量已经就位，清旧失败最坏是新旧共存（检索结果重复，重跑一次即自愈），
     *       所以不阻断；</li>
     *   <li><b>删除</b>时如果也吞掉异常，调用方就会继续把业务记录软删掉，留下
     *       「管理端看不到这篇文档、但用户提问仍能检索到它」的隐性脏数据 ——
     *       不可见、不可发现、也没人会去修。</li>
     * </ul>
     *
     * 所以删除路径必须是「先删向量，删成功了才软删记录」。
     */
    public void deleteDocumentOrThrow(Long docId) {
        storeProvider.getObject().removeAll(
                dev.langchain4j.store.embedding.filter.MetadataFilterBuilder
                        .metadataKey("doc_id").isEqualTo(String.valueOf(docId)));
        log.info("已清除文档 id={} 的全部向量", docId);
        // 向量库变了，关键词索引必须同步重建，否则 BM25 那一路还能检索到已删文档
        bm25Index.reload();
    }

    /**
     * 写入失败时的补偿：删除本次已写入的残留
     * （= 当前该文档的向量中不属于旧快照的部分），让系统回到「只有旧数据」的一致状态。
     */
    private void rollbackPartialWrite(EmbeddingStore<TextSegment> store, Long docId,
                                      List<String> oldEmbeddingIds) {
        metrics.degrade("ingest_write");
        List<String> current = vectorStoreAdmin.findEmbeddingIds(docId);
        current.removeAll(oldEmbeddingIds);
        if (current.isEmpty()) {
            return;
        }
        try {
            store.removeAll(current);
            log.warn("文档 {} 写入失败，已回滚本次写入的 {} 条残留向量（旧数据保持不变）",
                    docId, current.size());
        } catch (Exception e) {
            RedisShortCircuitException.rethrowIf(e);   // 短路必须冒泡，不能被降级逻辑吞掉
            log.error("文档 {} 的残留向量回滚失败，需通过 /api/rag/reconcile 对账后重跑导入", docId, e);
        }
    }

    /**
     * 删除旧向量。
     * **失败不抛**：此时新向量已就位，最坏结果是「新旧共存导致检索结果重复」——
     * 属于可自愈状态（重跑一次导入即可），远比「删了没写」安全。
     */
    private void removeOldEmbeddings(EmbeddingStore<TextSegment> store, Long docId,
                                     List<String> oldEmbeddingIds) {
        if (oldEmbeddingIds.isEmpty()) {
            return;
        }
        try {
            store.removeAll(oldEmbeddingIds);
            log.info("已清理文档 id={} 的 {} 条旧向量", docId, oldEmbeddingIds.size());
        } catch (Exception e) {
            RedisShortCircuitException.rethrowIf(e);   // 短路必须冒泡，不能被降级逻辑吞掉
            metrics.degrade("ingest_cleanup");
            log.warn("文档 {} 的旧向量清理失败（新旧共存，重跑一次导入即可自愈）: {}",
                    docId, e.getMessage());
        }
    }

    private void report(BiConsumer<String, Integer> onProgress, String stage, int percent) {
        if (onProgress == null) {
            return;
        }
        try {
            onProgress.accept(stage, percent);
        } catch (Exception e) {
            RedisShortCircuitException.rethrowIf(e);   // 短路必须冒泡，不能被降级逻辑吞掉
            log.warn("进度回调失败（不影响导入）: {}", e.getMessage());
        }
    }

}
