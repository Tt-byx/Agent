package com.scenic.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
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

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (exception, method, params) ->
                log.error("[ASYNC] {} 执行失败: {}", method.getName(), exception.getMessage(), exception);
    }
}
