package com.scenic.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.scenic.ai.entity.KnowledgeDoc;
import com.scenic.ai.mapper.KnowledgeDocMapper;
import com.scenic.ai.rag.ingest.DocumentIngestionService;
import com.scenic.ai.service.KnowledgeDocService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 知识库文档服务。
 *
 * 2026-09-28（A1 修复）：向量化与删除向量**不再转发 Python**，
 * 改为直接调用 JVM 内的 {@link DocumentIngestionService}。
 * 原先那段「手工拼请求 + 逐行解析 SSE 进度」的代码（约 60 行）已整体删除。
 */
@Service
public class KnowledgeDocServiceImpl implements KnowledgeDocService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocServiceImpl.class);

    @Autowired
    private KnowledgeDocMapper knowledgeDocMapper;

    @Autowired
    private DocumentIngestionService ingestionService;

    @Value("${scenic.upload.dir}")
    private String uploadDir;

    @Override
    public KnowledgeDoc upload(String title, MultipartFile file, String category, String scenicArea) {
        // 修复标题编码
        title = fixFilenameEncoding(title);

        // 1. 保存文件到磁盘
        String originalFilename = fixFilenameEncoding(file.getOriginalFilename());
        String fileType = getFileExtension(originalFilename);
        String savedFileName = UUID.randomUUID() + "." + fileType;
        String filePath = uploadDir + "/" + savedFileName;

        File dir = new File(uploadDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        try {
            file.transferTo(new File(filePath));
        } catch (IOException e) {
            throw new RuntimeException("文件保存失败: " + e.getMessage(), e);
        }

        // 2. 创建数据库记录（vectorStatus=0 待处理）
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setTitle(title);
        doc.setFileName(originalFilename);
        doc.setFileUrl(filePath);
        doc.setFileType(fileType);
        doc.setCategory(category != null ? category : "未分类");
        doc.setScenicArea(scenicArea != null ? scenicArea : "");
        doc.setVectorStatus(0); // 待处理
        doc.setProcessProgress(0);
        doc.setStatus(1);
        doc.setCreatedAt(LocalDateTime.now());
        doc.setUpdatedAt(LocalDateTime.now());
        knowledgeDocMapper.insert(doc);

        // 注意：这里**不**自动向量化。前端流程是「先 /upload 拿到 id，再显式调 /process/{id}」，
        // 在这里触发会让同一份文档被处理两次（幂等但白白多烧一轮 embedding）。
        return doc;
    }

    @Override
    @Async
    public void process(Long id) {
        KnowledgeDoc doc = knowledgeDocMapper.selectById(id);
        if (doc == null) {
            throw new RuntimeException("文档不存在: " + id);
        }

        updateProgress(id, 1, "parsing", 0);
        log.info("开始处理文档: id={}, file={}", id, doc.getFileUrl());

        try {
            DocumentIngestionService.IngestResult result = ingestionService.ingest(
                    doc.getId(),
                    doc.getTitle(),
                    Path.of(doc.getFileUrl()),
                    doc.getFileType(),
                    (stage, percent) -> updateProgress(id, 1, stage, percent));

            KnowledgeDoc latest = knowledgeDocMapper.selectById(id);
            if (latest != null) {
                latest.setVectorStatus(2); // 完成
                latest.setProcessStage("completed");
                latest.setProcessProgress(100);
                latest.setChunkCount(result.chunkCount());
                latest.setUpdatedAt(LocalDateTime.now());
                knowledgeDocMapper.updateById(latest);
            }
            log.info("文档处理完成: id={}, chunkCount={}", id, result.chunkCount());

        } catch (Exception e) {
            log.error("文档处理失败: id={}, error={}", id, e.getMessage(), e);
            updateProgress(id, 3, "failed", 0);
        }
    }

    /**
     * Wave 2 · B7：按「路径 → 业务表记录」解析 docId，未登记过则先登记。
     *
     * 匹配顺序（务实地兼容历史数据两种存法）：
     *   1. 按 fileUrl 精确匹配 —— 后台上传存的原始路径；
     *   2. 按 fileName 匹配 —— 同一份资料以不同路径/不同机器出现时；
     *   3. 都没命中 → 新建记录（vectorStatus=0），返回其自增主键。
     */
    @Override
    public Long resolveDocIdByPath(Path file, String title) {
        String absolutePath = file.toAbsolutePath().normalize().toString();
        String fileName = file.getFileName().toString();

        KnowledgeDoc existing = knowledgeDocMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDoc>()
                        .eq(KnowledgeDoc::getStatus, 1)
                        .and(q -> q.eq(KnowledgeDoc::getFileUrl, absolutePath)
                                .or().eq(KnowledgeDoc::getFileUrl, file.toString()))
                        .last("LIMIT 1"));
        if (existing == null) {
            existing = knowledgeDocMapper.selectOne(
                    new LambdaQueryWrapper<KnowledgeDoc>()
                            .eq(KnowledgeDoc::getStatus, 1)
                            .eq(KnowledgeDoc::getFileName, fileName)
                            .last("LIMIT 1"));
        }
        if (existing != null) {
            log.info("[DOC-REGISTRY] 路径 {} 已登记为 docId={}", fileName, existing.getId());
            return existing.getId();
        }

        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setTitle(title != null && !title.isBlank() ? title : fileName);
        doc.setFileName(fileName);
        doc.setFileUrl(absolutePath);
        doc.setFileType(getFileExtension(fileName));
        doc.setCategory("未分类");
        doc.setScenicArea("");
        doc.setVectorStatus(0);
        doc.setProcessProgress(0);
        doc.setStatus(1);
        doc.setCreatedAt(LocalDateTime.now());
        doc.setUpdatedAt(LocalDateTime.now());
        knowledgeDocMapper.insert(doc);
        log.info("[DOC-REGISTRY] 路径 {} 为新文档，已登记 docId={}", fileName, doc.getId());
        return doc.getId();
    }

    @Override
    public List<KnowledgeDoc> listAll() {
        return knowledgeDocMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDoc>()
                        .eq(KnowledgeDoc::getStatus, 1)
                        .orderByDesc(KnowledgeDoc::getCreatedAt)
        );
    }

    @Override
    public List<KnowledgeDoc> listFiltered(String category, String scenicArea) {
        LambdaQueryWrapper<KnowledgeDoc> wrapper = new LambdaQueryWrapper<KnowledgeDoc>()
                .eq(KnowledgeDoc::getStatus, 1);
        if (category != null && !category.isEmpty()) {
            wrapper.eq(KnowledgeDoc::getCategory, category);
        }
        if (scenicArea != null && !scenicArea.isEmpty()) {
            wrapper.like(KnowledgeDoc::getScenicArea, scenicArea);
        }
        wrapper.orderByDesc(KnowledgeDoc::getCreatedAt);
        return knowledgeDocMapper.selectList(wrapper);
    }

    @Override
    public KnowledgeDoc update(Long id, KnowledgeDoc updateData) {
        KnowledgeDoc doc = knowledgeDocMapper.selectById(id);
        if (doc == null) {
            throw new RuntimeException("文档不存在: " + id);
        }

        boolean contentChanged = false;
        if (updateData.getTitle() != null) {
            doc.setTitle(updateData.getTitle());
        }
        if (updateData.getContent() != null && !updateData.getContent().equals(doc.getContent())) {
            doc.setContent(updateData.getContent());
            contentChanged = true;
        }
        if (updateData.getCategory() != null) {
            doc.setCategory(updateData.getCategory());
        }
        if (updateData.getScenicArea() != null) {
            doc.setScenicArea(updateData.getScenicArea());
        }
        if (updateData.getLastModifiedBy() != null) {
            doc.setLastModifiedBy(updateData.getLastModifiedBy());
        }
        doc.setUpdatedAt(LocalDateTime.now());
        knowledgeDocMapper.updateById(doc);

        // 标题/分类变更也要重算向量元数据，内容变更更要重切
        if (contentChanged || updateData.getTitle() != null) {
            process(id);
        }

        return knowledgeDocMapper.selectById(id);
    }

    @Override
    public void delete(Long id) {
        KnowledgeDoc doc = knowledgeDocMapper.selectById(id);
        if (doc == null) {
            throw new RuntimeException("文档不存在: " + id);
        }

        doc.setStatus(0);
        doc.setUpdatedAt(LocalDateTime.now());
        knowledgeDocMapper.updateById(doc);

        // 同步删除向量与关键词索引（原先是调 Python，现已迁入 JVM）
        ingestionService.deleteDocument(id);
    }

    @Override
    public void reprocess(Long id) {
        KnowledgeDoc doc = knowledgeDocMapper.selectById(id);
        if (doc == null) {
            throw new RuntimeException("文档不存在: " + id);
        }

        updateProgress(id, 1, "parsing", 0);
        process(id);
    }

    /** 统一的状态更新入口，避免各处重复拼字段 */
    private void updateProgress(Long id, int vectorStatus, String stage, int progress) {
        KnowledgeDoc doc = knowledgeDocMapper.selectById(id);
        if (doc == null) {
            return;
        }
        doc.setVectorStatus(vectorStatus);
        doc.setProcessStage(stage);
        doc.setProcessProgress(progress);
        doc.setUpdatedAt(LocalDateTime.now());
        knowledgeDocMapper.updateById(doc);
    }

    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "unknown";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }

    private String fixFilenameEncoding(String filename) {
        if (filename == null) return null;
        try {
            byte[] bytes = filename.getBytes("ISO-8859-1");
            String decoded = new String(bytes, "UTF-8");
            if (decoded.matches(".*[\\u4e00-\\u9fa5].*")) {
                return decoded;
            }
        } catch (Exception ignored) {}
        return filename;
    }
}
