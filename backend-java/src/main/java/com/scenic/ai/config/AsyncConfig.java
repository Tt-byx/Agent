package com.scenic.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Wave 6 · 异步执行器（@Async 用）。
 *
 * 为什么要显式配置：
 *   1. **traceId 透传** —— 装配 {@link MdcTaskDecorator}，否则异步任务的日志无法与触发它的请求关联；
 *   2. 默认的 SimpleAsyncTaskExecutor 每次新建线程、不受控 —— 换成有界线程池，
 *      并支持优雅停机（等在跑的任务结束，避免向量化写一半被掐断）。
 *
 * 当前使用方：知识库文档处理（{@code KnowledgeDocumentService#process}）。
 */
@Configuration
@Slf4j
public class AsyncConfig implements AsyncConfigurer {

    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("scenic-async-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("[ASYNC] 异步执行器就绪：核心 4 / 上限 8 / 队列 100，已装配 MDC(traceId) 透传");
        return executor;
    }

    /**
     * 2026-10-29（D13）· 会话摘要专用执行器。
     *
     * <p>为什么单独一个池、而且**只有 1 个线程**：
     * 同一会话的两次摘要必须串行 —— 摘要的写法是「读旧摘要 + 拼新内容 + 覆盖写回」，
     * 并发跑就会后写覆盖先写，直接丢掉一批对话。而摘要本身是低频操作
     * （滑窗每溢出一次才触发一次），全局串行完全够用。
     *
     * <p>为什么不复用上面的文档处理池：文档处理是 IO 密集型（要调 embedding API），
     * 4~8 个线程并发是有意义的；摘要混进去既会被它拖慢，也会反过来挤占它的线程。
     * 两个池的负载特征不同，不该共用一个。
     *
     * <p>队列满时用默认的 AbortPolicy 抛 {@code RejectedExecutionException}，
     * 由 {@code AgentLoop#submitSummary} 捕获后降级为同步执行 —— 宁可那一轮慢一点，
     * 也不能把已经 drain 出滑窗的消息丢掉（drain 已经把消息从 Redis 移除了，摘要是唯一的去处）。
     */
    @Bean(name = "summaryExecutor")
    public ThreadPoolTaskExecutor summaryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("scenic-summary-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        log.info("[ASYNC] 摘要执行器就绪：单线程串行 / 队列 200（保证同会话摘要不互相覆盖）");
        return executor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (exception, method, params) ->
                log.error("[ASYNC] {} 执行失败: {}", method.getName(), exception.getMessage(), exception);
    }
}
