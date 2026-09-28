package com.scenic.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.scenic.ai.entity.KnowledgeDoc;
import com.scenic.ai.mapper.KnowledgeDocMapper;
import com.scenic.ai.service.KnowledgeDocService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class KnowledgeDocServiceImpl implements KnowledgeDocService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocServiceImpl.class);

    @Autowired
    private KnowledgeDocMapper knowledgeDocMapper;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${scenic.upload.dir}")
    private String uploadDir;

    @Value("${python.backend.url}")
    private String pythonBackendUrl;

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

        return doc;
    }

    @Override
    @Async
    public void process(Long id) {
        KnowledgeDoc doc = knowledgeDocMapper.selectById(id);
        if (doc == null) {
            throw new RuntimeException("文档不存在: " + id);
        }

        // 更新状态为处理中
        doc.setVectorStatus(1);
        doc.setProcessStage("parsing");
        doc.setProcessProgress(0);
        doc.setUpdatedAt(LocalDateTime.now());
        knowledgeDocMapper.updateById(doc);

        log.info("开始处理文档: id={}, file={}", id, doc.getFileUrl());

        try {
            // 调用 Python 处理接口（SSE 流式返回进度）
            URL url = new URL(pythonBackendUrl + "/api/knowledge/process");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(300000); // 5分钟
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

            // 发送 JSON 请求体
            Map<String, Object> request = new HashMap<>();
            request.put("doc_id", doc.getId());
            request.put("file_path", doc.getFileUrl());
            request.put("file_type", doc.getFileType());
            request.put("doc_title", doc.getTitle());

            try (OutputStream os = conn.getOutputStream()) {
                os.write(objectMapper.writeValueAsBytes(request));
                os.flush();
            }

            // 读取 SSE 流式响应
            int chunkCount = 0;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String currentEvent = "";
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event: ")) {
                        currentEvent = line.substring(7).trim();
                    } else if (line.startsWith("data: ")) {
                        String data = line.substring(6).trim();
                        try {
                            Map<String, Object> eventData = objectMapper.readValue(data, Map.class);

                            if ("progress".equals(currentEvent)) {
                                // 更新进度
                                String stage = (String) eventData.get("stage");
                                Number progress = (Number) eventData.get("progress");
                                doc = knowledgeDocMapper.selectById(id);
                                if (doc != null) {
                                    doc.setProcessStage(stage);
                                    doc.setProcessProgress(progress.intValue());
                                    doc.setUpdatedAt(LocalDateTime.now());
                                    knowledgeDocMapper.updateById(doc);
                                }
                                log.debug("文档处理进度: id={}, stage={}, progress={}%", id, stage, progress);
                            } else if ("done".equals(currentEvent)) {
                                Number cc = (Number) eventData.get("chunk_count");
                                chunkCount = cc != null ? cc.intValue() : 0;
                            } else if ("error".equals(currentEvent)) {
                                throw new RuntimeException((String) eventData.get("message"));
                            }
                        } catch (Exception e) {
                            if ("SSE解析".equals(e.getMessage())) continue;
                            throw e;
                        }
                    }
                }
            }

            // 处理完成
            doc = knowledgeDocMapper.selectById(id);
            if (doc != null) {
                doc.setVectorStatus(2); // 完成
                doc.setProcessStage("completed");
                doc.setProcessProgress(100);
                doc.setChunkCount(chunkCount);
                doc.setUpdatedAt(LocalDateTime.now());
                knowledgeDocMapper.updateById(doc);
            }
            log.info("文档处理完成: id={}, chunkCount={}", id, chunkCount);

        } catch (Exception e) {
            log.error("文档处理失败: id={}, error={}", id, e.getMessage());
            doc = knowledgeDocMapper.selectById(id);
            if (doc != null) {
                doc.setVectorStatus(3); // 失败
                doc.setProcessStage("failed");
                doc.setUpdatedAt(LocalDateTime.now());
                knowledgeDocMapper.updateById(doc);
            }
        }
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

        // 如果内容变更，自动触发重向量化
        if (contentChanged) {
            reprocess(id);
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

        try {
            restTemplate.delete(pythonBackendUrl + "/api/knowledge/" + id);
        } catch (Exception e) {
            log.warn("删除向量失败（不影响软删除）: {}", e.getMessage());
        }
    }

    @Override
    public void reprocess(Long id) {
        KnowledgeDoc doc = knowledgeDocMapper.selectById(id);
        if (doc == null) {
            throw new RuntimeException("文档不存在: " + id);
        }

        doc.setVectorStatus(1);
        doc.setProcessStage("parsing");
        doc.setProcessProgress(0);
        doc.setUpdatedAt(LocalDateTime.now());
        knowledgeDocMapper.updateById(doc);

        process(id);
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
