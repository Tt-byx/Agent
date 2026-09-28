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
}
