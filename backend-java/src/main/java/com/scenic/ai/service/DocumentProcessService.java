package com.scenic.ai.service;

import com.scenic.ai.exception.RedisShortCircuitException;
import com.scenic.ai.entity.KnowledgeDoc;
import com.scenic.ai.mapper.KnowledgeDocMapper;
import com.scenic.ai.rag.ingest.DocumentIngestionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * 2026-10-29（D08）· 文档向量化的**后台任务**入口。
 *
 * <p>为什么单独抽一个类：{@code @Async} 靠 Spring 的 AOP 代理生效，
 * 而 {@code KnowledgeDocServiceImpl} 内部 {@code this.process(id)} 用的是**原始对象**，
 * 注解被完全跳过。于是同一个方法，从 Controller 调是异步的，从 {@code update()} /
 * {@code reprocess()} 里调是同步的 —— 表现就是「改个标题卡 30 秒」。
 *
 * <p>抽成独立 Bean 之后，调用方一律经过代理，「异步」这件事不再取决于从哪进来。
 * 这个类的命名也说明了它的职责边界：**这里的东西都是后台任务，不要在请求线程上直接调**。
 *
 * <p>代价说明：异步化后调用方拿不到处理结果。所以状态的唯一出口是
 * {@code knowledge_doc.vector_status / process_stage / process_progress} 三个字段，
 * 前端靠轮询或列表刷新观察进度 —— 这也正是原有设计（上传 → 显式 process → 轮询）的前提。
 */
@Service
@Slf4j
public class DocumentProcessService {

    /** 状态码：与前端既有约定保持一致（0 待处理 / 1 处理中 / 2 完成 / 3 失败） */
    private static final int STATUS_PROCESSING = 1;
    private static final int STATUS_DONE = 2;
    private static final int STATUS_FAILED = 3;

    private final KnowledgeDocMapper knowledgeDocMapper;
    private final DocumentIngestionService ingestionService;

    public DocumentProcessService(KnowledgeDocMapper knowledgeDocMapper,
                                  DocumentIngestionService ingestionService) {
        this.knowledgeDocMapper = knowledgeDocMapper;
        this.ingestionService = ingestionService;
    }

    /**
     * 异步处理文档：解析 → 切分 → 向量化 → 入库，并把进度写回业务表。
     *
     * <p>注意：这里**不吞**「文档不存在」这类前置异常 —— 它会走
     * {@code AsyncConfig#getAsyncUncaughtExceptionHandler} 进日志。
     * 而向量化本身的失败是要吞的：它必须先把状态写成「失败」再结束，
     * 否则文档会永远停在「处理中」，前端只能看到一个永远转圈的进度条。
     */
    @Async
    public void processAsync(Long id) {
        KnowledgeDoc doc = knowledgeDocMapper.selectById(id);
        if (doc == null) {
            // 交给 AsyncUncaughtExceptionHandler 记录；调用方已经拿到 202/200 了，无从返回
            throw new IllegalStateException("文档不存在: " + id);
        }

        updateProgress(id, STATUS_PROCESSING, "parsing", 0);
        log.info("[DOC-PROCESS] 开始处理文档: id={}, file={}", id, doc.getFileUrl());

        try {
            DocumentIngestionService.IngestResult result = ingestionService.ingest(
                    doc.getId(),
                    doc.getTitle(),
                    Path.of(doc.getFileUrl()),
                    doc.getFileType(),
                    (stage, percent) -> updateProgress(id, STATUS_PROCESSING, stage, percent));

            KnowledgeDoc latest = knowledgeDocMapper.selectById(id);
            if (latest != null) {
                latest.setVectorStatus(STATUS_DONE);
                latest.setProcessStage("completed");
                latest.setProcessProgress(100);
                latest.setChunkCount(result.chunkCount());
                latest.setUpdatedAt(LocalDateTime.now());
                knowledgeDocMapper.updateById(latest);
            }
            log.info("[DOC-PROCESS] 文档处理完成: id={}, chunkCount={}", id, result.chunkCount());

        } catch (Exception e) {
            RedisShortCircuitException.rethrowIf(e);   // 短路必须冒泡，不能被降级逻辑吞掉
            log.error("[DOC-PROCESS] 文档处理失败: id={}, error={}", id, e.getMessage(), e);
            updateProgress(id, STATUS_FAILED, "failed", 0);
        }
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
}
