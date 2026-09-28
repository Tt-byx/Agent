package com.scenic.ai.controller;

import com.scenic.ai.dto.Result;
import com.scenic.ai.rag.ingest.DocumentIngestionService;
import com.scenic.ai.rag.ingest.DocumentTextExtractor;
import com.scenic.ai.rag.retrieve.RagRetriever;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * RAG 知识库运维接口（P3 新增）。
 *
 * 用于把赛题资料包导入 pgvector，以及直接观察检索结果，方便做检索质量验证（P4 评测集也基于此）。
 * 注意：这是开发/运维用途的接口，上线前应加权限控制。
 */
@RestController
@RequestMapping("/api/rag")
@Slf4j
public class RagAdminController {

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private RagRetriever ragRetriever;

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
            var result = ingestionService.ingest(
                    docId != null ? docId : stableId(file),
                    title != null ? title : file.getFileName().toString(),
                    file, type);
            return Result.success(result);
        } catch (Exception e) {
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
        try (Stream<Path> stream = Files.walk(dir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> DocumentTextExtractor.SUPPORTED_TYPES.contains(extension(p)))
                    .sorted()
                    .toList();

            for (Path file : files) {
                try {
                    results.add(ingestionService.ingest(
                            stableId(file), file.getFileName().toString(), file, extension(file)));
                } catch (Exception e) {
                    log.error("文件导入失败: {}", file, e);
                    results.add(Map.of(
                            "file", file.getFileName().toString(),
                            "error", String.valueOf(e.getMessage())));
                }
            }
        } catch (Exception e) {
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

    private String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    /** 用文件路径生成稳定的 docId，保证重复导入落到同一条文档上 */
    private long stableId(Path path) {
        return Math.abs((long) path.toAbsolutePath().normalize().toString().hashCode());
    }
}
