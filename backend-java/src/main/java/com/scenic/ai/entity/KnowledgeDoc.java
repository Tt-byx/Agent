package com.scenic.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_doc")
public class KnowledgeDoc {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String fileName;
    private String fileUrl;
    private String fileType;
    private String content;
    private Integer chunkCount;
    private Integer vectorStatus;
    private String processStage;    // parsing/chunking/embedding/storing/completed
    private Integer processProgress; // 0-100
    private String category;        // 分类标签
    private String scenicArea;      // 关联景区
    private String lastModifiedBy;  // 最后修改人
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
