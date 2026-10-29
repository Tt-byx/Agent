package com.scenic.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库文档。
 *
 * <p>字段语义以 {@code resources/schema.sql} 为准。这里重点补两个**状态机字段**的取值，
 * 因为 D26 之前它们只有字段名、没有任何注释，读代码只能靠反推。
 *
 * <p>两个状态是**正交**的，不要混着看：
 * <ul>
 *   <li>{@link #status} 管「这篇文档还在不在」—— 软删除；</li>
 *   <li>{@link #vectorStatus} 管「这篇文档处理到哪一步」—— 处理进度。</li>
 * </ul>
 * 一篇 {@code status=1}（正常）的文档完全可能 {@code vectorStatus=3}（处理失败）：
 * 它在列表里看得见，但检索不到，用户可以点「重处理」修好。
 * 反过来 {@code status=0} 的文档无论 {@code vectorStatus} 是多少都不该被检索到 ——
 * 这正是 D09「先删向量再软删」要保证的顺序。
 */
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

    /** 切分出的切片数；{@code vectorStatus=2} 时它应当与向量库里该 doc_id 的条数一致（对账依据） */
    private Integer chunkCount;

    /**
     * 向量化状态。
     *
     * <ul>
     *   <li>0 = 未处理（刚上传，或运维导入时登记但还没跑）</li>
     *   <li>1 = 处理中（{@code DocumentProcessService} 进入时写入）</li>
     *   <li>2 = 已完成（入库成功，可被检索）</li>
     *   <li>3 = 失败（解析/向量化/写入任一步出错，状态与原因见 processStage）</li>
     * </ul>
     *
     * <p>注意它**不会**因为软删除而改变 —— 删除只动 {@link #status}。
     */
    private Integer vectorStatus;

    /** 处理阶段，供前端展示进度文案：parsing / chunking / embedding / storing / completed / failed */
    private String processStage;

    /** 处理进度 0-100 */
    private Integer processProgress;

    /** 分类标签 */
    private String category;

    /** 关联景区 */
    private String scenicArea;

    /** 最后修改人 */
    private String lastModifiedBy;

    /**
     * 1 = 正常，0 = 已删除（软删）。
     *
     * <p>所有列表查询都带 {@code status=1}。删除时**先删向量再置 0**（D09），
     * 否则会留下「不可见但仍可检索」的隐性脏数据。
     */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
