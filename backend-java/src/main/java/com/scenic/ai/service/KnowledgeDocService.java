package com.scenic.ai.service;

import com.scenic.ai.entity.KnowledgeDoc;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface KnowledgeDocService {

    KnowledgeDoc upload(String title, MultipartFile file, String category, String scenicArea);

    void process(Long id);

    List<KnowledgeDoc> listAll();

    List<KnowledgeDoc> listFiltered(String category, String scenicArea);

    void delete(Long id);

    void reprocess(Long id);

    KnowledgeDoc update(Long id, KnowledgeDoc updateData);

    /**
     * Wave 2 · B7：把「服务端文件路径」解析为**业务表主键 docId**。
     *
     * 让运维导入（/api/rag/ingest、/api/rag/reindex）与后台上传（/api/knowledge/upload）
     * 使用同一套 ID 规则。原先前者用「路径 hash」、后者用自增主键，
     * 同一份文档经两条路径导入会落到不同 docId 上，产生**两套向量**。
     */
    Long resolveDocIdByPath(java.nio.file.Path file, String title);
}
