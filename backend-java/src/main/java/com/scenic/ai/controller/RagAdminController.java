package com.scenic.ai.controller;

import com.scenic.ai.exception.RedisShortCircuitException;
import com.scenic.ai.dto.Result;
import com.scenic.ai.rag.eval.RagEvalRunner;
import com.scenic.ai.rag.ingest.DocumentIngestionService;
import com.scenic.ai.rag.ingest.DocumentTextExtractor;
import com.scenic.ai.rag.retrieve.Bm25Index;
import com.scenic.ai.rag.retrieve.RagRetriever;
import com.scenic.ai.rag.store.VectorStoreAdmin;
import com.scenic.ai.service.KnowledgeDocService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * RAG 知识库运维接口（P3 新增，Wave 2 加固）。
 *
 * 用于把赛题资料包导入 pgvector，以及直接观察检索结果（P4 评测集也基于此）。
 *
 * Wave 2 变化：
 *   1. 导入的两条路径统一 docId 规则（修 B7，见 {@link KnowledgeDocService#resolveDocIdByPath}）；
 *   2. 新增 {@code /reconcile} 对账接口，比对业务表 chunk 数与向量库实际条数。
 *
 * 权限：由 {@code SecurityConfig} 统一要求 ROLE_ADMIN（Wave 0 之前这里是裸奔的）。
 */
@RestController
@RequestMapping("/api/rag")
@Slf4j
public class RagAdminController {

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private RagRetriever ragRetriever;

    @Autowired
    private Bm25Index bm25Index;

    @Autowired
    private RagEvalRunner evalRunner;

    @Autowired
    private KnowledgeDocService knowledgeDocService;

    @Autowired
    private VectorStoreAdmin vectorStoreAdmin;

    @Value("${scenic.upload.dir:D:/scenic_uploads}")
    private String uploadDir;

    /** 导入单个文件（服务端路径） */
    @PostMapping("/ingest")
    public Result<?> ingest(@RequestParam String path,
                            @RequestParam(required = false) Long docId,
                            @RequestParam(required = false) String title) {
        Path file = Path.of(path);
        if (!Files.isRegularFile(file)) {
            return Result.error(404, "文件不存在: " + path);
        }
        String type = extension(file);
        if (!DocumentTextExtractor.SUPPORTED_TYPES.contains(type)) {
            return Result.error(400, "不支持的文件类型: " + type
                    + "，支持 " + DocumentTextExtractor.SUPPORTED_TYPES);
        }
        try {
            // Wave 2 · B7：未显式指定 docId 时，先登记到业务表再取主键 ——
            // 与 /api/knowledge/upload 路径共用同一套 ID 规则（原先用路径 hash，会产生两套向量）
            Long effectiveDocId = docId != null
                    ? docId
                    : knowledgeDocService.resolveDocIdByPath(file, title);
            var result = ingestionService.ingest(
                    effectiveDocId,
                    title != null ? title : file.getFileName().toString(),
                    file, type);
            return Result.success(result);
        } catch (Exception e) {
            RedisShortCircuitException.rethrowIf(e);   // 短路必须冒泡，不能被降级逻辑吞掉
            log.error("导入失败: {}", path, e);
            return Result.error(500, "导入失败: " + e.getMessage());
        }
    }

    /** 扫描上传目录，批量导入全部受支持的文件 */
    @PostMapping("/reindex")
    public Result<?> reindex() {
        Path dir = Path.of(uploadDir);
        if (!Files.isDirectory(dir)) {
            return Result.error(404, "上传目录不存在: " + uploadDir);
        }

        List<Object> results = new ArrayList<>();
        //从 dir 目录开始，递归遍历它下面的所有文件和子目录，生成一个由路径组成的流
        try (Stream<Path> stream = Files.walk(dir)) {
            //从一批路径中筛选出"受支持的普通文件"，并按路径排序，最后收集成一个 List
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> DocumentTextExtractor.SUPPORTED_TYPES.contains(extension(p)))
                    .sorted()
                    .toList();

            for (Path file : files) {
                try {
                    // Wave 2 · B7：统一走业务表 docId
                    Long registeredId = knowledgeDocService.resolveDocIdByPath(
                            file, file.getFileName().toString());
                    results.add(ingestionService.ingest(
                            registeredId, file.getFileName().toString(), file, extension(file)));
                } catch (Exception e) {
                    RedisShortCircuitException.rethrowIf(e);   // 短路必须冒泡，不能被降级逻辑吞掉
                    log.error("文件导入失败: {}", file, e);
                    results.add(Map.of(
                            "file", file.getFileName().toString(),
                            "error", String.valueOf(e.getMessage())));
                }
            }
        } catch (Exception e) {
            RedisShortCircuitException.rethrowIf(e);   // 短路必须冒泡，不能被降级逻辑吞掉
            return Result.error(500, "扫描目录失败: " + e.getMessage());
        }

        return Result.success(Map.of(
                "dir", uploadDir,
                "supported", DocumentTextExtractor.SUPPORTED_TYPES,
                "results", results));
    }

    /** 检索探针：直接看某个问题命中了哪些切片及其得分（P4 评测集的基础） */
    @PostMapping("/probe")
    public Result<?> probe(@RequestParam String query) {
        return Result.success(ragRetriever.retrieve(query));
    }

    /**
     * Wave 2 · 对账：比对「业务表记录的 chunkCount」与「向量库按 doc_id 的实际条数」。
     *
     * 存在的意义：入库已改成「先写新后删旧 + 补偿回滚」，但跨库（MySQL + PostgreSQL）
     * 无法使用本地事务，残留的不一致必须靠**对账**发现 —— 典型症状是
     * 「向量写入失败但业务表已标记完成」或「旧向量清理失败导致条数偏多」。
     */
    @GetMapping("/reconcile")
    public Result<?> reconcile() {
        if (!vectorStoreAdmin.isReachable()) {
            // 向量库没起时给明确结论，而不是抛异常变成 500
            return Result.success(Map.of(
                    "vectorStoreReachable", false,
                    "message", "向量库不可达（pgvector 未启动？），无法对账"));
        }

        List<com.scenic.ai.entity.KnowledgeDoc> docs = knowledgeDocService.listAll();
        Map<Long, Long> vectorCounts = vectorStoreAdmin.countByDocId();

        List<Map<String, Object>> mismatches = new ArrayList<>();
        int consistent = 0;
        for (com.scenic.ai.entity.KnowledgeDoc doc : docs) {
            // 只对「已向量化」（vectorStatus=2）的文档要求条数一致
            if (doc.getVectorStatus() == null || doc.getVectorStatus() != 2) {
                continue;
            }
            long expected = doc.getChunkCount() == null ? 0 : doc.getChunkCount();
            long actual = vectorCounts.getOrDefault(doc.getId(), 0L);
            if (expected == actual) {
                consistent++;
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("docId", doc.getId());
            item.put("title", doc.getTitle());
            item.put("expectedChunks", expected);
            item.put("actualVectors", actual);
            item.put("action", actual == 0
                    ? "向量缺失：需要重新处理（POST /api/knowledge/reprocess/{id}）"
                    : (actual > expected ? "向量偏多（旧向量未清理）：重跑一次导入即可自愈" : "向量偏少：重新处理"));
            mismatches.add(item);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("vectorStoreReachable", vectorStoreAdmin.isReachable());
        report.put("totalVectorsInStore", vectorStoreAdmin.countAll());
        report.put("documentsChecked", consistent + mismatches.size());
        report.put("documentsConsistent", consistent);
        report.put("documentsMismatched", mismatches.size());
        report.put("mismatches", mismatches);
        log.info("[RECONCILE] 对账完成：一致 {} 篇，不一致 {} 篇", consistent, mismatches.size());
        return Result.success(report);
    }

    /** 重建 BM25 关键词索引（文档增删后调用） */
    @PostMapping("/reload-index")
    public Result<?> reloadIndex() {
        bm25Index.reload();
        return Result.success(Map.of("loaded", bm25Index.isLoaded(), "chunks", bm25Index.size()));
    }

    /**
     * 检索质量评测：一次给出 Recall@K / Hit@1 / MRR。
     * Wave 6 起三项指标都在代码内计算（原先只有 Recall@K，其余靠人工核算），可自动回归。
     */
    @PostMapping("/eval")
    public Result<?> eval() {
        return Result.success(evalRunner.run());
    }

    /**
     * Wave 6 · 对抗样本检验：用「语义相近但答案不同」的问题对，
     * 验证语义缓存的相似度阈值是否安全（会不会把 A 的答案给 B）。
     */
    @GetMapping("/adversarial")
    public Result<?> adversarial(@RequestParam(required = false) Double threshold) {
        double effectiveThreshold = threshold != null ? threshold : 0.92;
        return Result.success(evalRunner.adversarialReport(effectiveThreshold));
    }

    private String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }
}
