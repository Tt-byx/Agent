package com.scenic.ai.config;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * Wave 6 · 让 @Async 任务继承「触发它的那个请求」的 traceId。
 *
 * 问题：Wave 3 引入的 traceId 存在 MDC 里，而 MDC 是 **ThreadLocal** ——
 * 异步任务跑在另一个线程上，拿不到触发者的上下文。结果是
 * {@code /api/knowledge/reprocess/{id}} 这类异步流程的日志与触发请求对不上号，
 * 排查时只能靠时间戳猜。
 *
 * 做法：提交任务时把当前 MDC 快照复制下来，在异步线程执行前装回、执行后清理
 * （必须清理，否则线程复用时会串到下一个任务）。
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            if (context != null) {
                MDC.setContextMap(context);
            }
            try {
                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    }
}
