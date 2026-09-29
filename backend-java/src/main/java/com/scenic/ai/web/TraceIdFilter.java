package com.scenic.ai.web;

import com.scenic.ai.gateway.AgentMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Wave 3 · 全链路追踪 + 请求级延迟。
 *
 * 解决的问题：改造前日志里没有任何请求标识，一次对话跨
 * Controller → ChatService → AgentLoop → Toolbox → RoutedChatModel 好几层，
 * 并发时根本无法把某一层的报错和某个用户的那次请求对应起来。
 *
 * 做法（轻量版，不引入 OTel / SkyWalking）：
 *   1. 为每个请求生成 traceId（或**透传上游的 X-Request-Id**），写入 MDC；
 *      logging.pattern 里加 %X{traceId} 之后，该请求的所有日志自动带上它；
 *   2. 响应头回写 X-Request-Id —— 前端或调用方报错时能直接给出 traceId，便于反查；
 *   3. 顺带记录请求级延迟直方图（method + 路由模板 + status）。
 *
 * 关于 uri 标签：用 Spring 的 BEST_MATCHING_PATTERN（如 /api/conversations/{id}）
 * 而不是原始路径，否则每个带 ID 的 URL 都会形成一个时间序列，**标签基数会爆炸**。
 * 这是监控落地时最容易被忽略、上线后最先出问题的一环。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_HEADER = "X-Request-Id";
    public static final String MDC_KEY = "traceId";

    private final AgentMetrics metrics;

    public TraceIdFilter(AgentMetrics metrics) {
        this.metrics = metrics;
    }

    /** 供业务代码（如日志、返回给用户的错误信息）取当前 traceId */
    public static String currentTraceId() {
        String traceId = MDC.get(MDC_KEY);
        return traceId == null ? "-" : traceId;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolveTraceId(request);
        MDC.put(MDC_KEY, traceId);
        response.setHeader(TRACE_HEADER, traceId);

        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            try {
                metrics.httpRequest(request.getMethod(), uriTemplate(request),
                        response.getStatus(), System.currentTimeMillis() - start);
            } catch (Exception e) {
                log.debug("记录请求指标失败（不影响响应）: {}", e.getMessage());
            } finally {
                // 必须清理，否则线程复用时会串到下一个请求
                MDC.remove(MDC_KEY);
            }
        }
    }

    /** 优先透传上游 traceId（便于跨服务串联），否则新生成一个 */
    private String resolveTraceId(HttpServletRequest request) {
        String incoming = request.getHeader(TRACE_HEADER);
        if (incoming != null && !incoming.isBlank() && incoming.length() <= 64) {
            return incoming;
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private String uriTemplate(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern != null ? pattern.toString() : "UNKNOWN";
    }
}
