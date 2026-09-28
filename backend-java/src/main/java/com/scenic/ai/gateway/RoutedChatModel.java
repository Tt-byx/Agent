package com.scenic.ai.gateway;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * P8 · 多模型路由（主/备）。
 *
 * 实现的是 LangChain4j 的 {@link ChatModel} 接口，所以对上层（AgentLoop）**完全透明**：
 * 上层只知道「我调了个模型」，不知道背后有几个候选、也不需要知道降级发生过。
 *
 * 路由策略：按优先级顺序尝试；主模型异常（超时 / 429 / 5xx / 鉴权失败）→ 自动切到备模型。
 * 主备**可以跨厂商** —— 只要对方兼容 OpenAI 协议，换 base-url + key 即可。
 * 默认配置：主 = DeepSeek 官方 `deepseek-flash`，备 = 硅基流动 `deepseek-ai/DeepSeek-V4-Flash`
 * （同款模型、不同厂商，天然的「厂商故障降级」路径）。
 *
 * 每次路由的成功/失败/耗时都打点，这是判断「备模型该不该转正」的数据依据。
 */
@Slf4j
public class RoutedChatModel implements ChatModel {

    public record Route(String name, ChatModel model) {}

    private final List<Route> routes;
    private final AgentMetrics metrics;

    public RoutedChatModel(List<Route> routes, AgentMetrics metrics) {
        if (routes == null || routes.isEmpty()) {
            throw new IllegalArgumentException("至少要配置一条模型路由");
        }
        this.routes = List.copyOf(routes);
        this.metrics = metrics;
        log.info("[ROUTE] 模型路由就绪：{} 条（{}）", routes.size(),
                routes.stream().map(Route::name).toList());
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        Exception last = null;
        for (Route route : routes) {
            long start = System.currentTimeMillis();
            try {
                ChatResponse response = route.model().chat(request);
                metrics.routeSucceeded(route.name(), System.currentTimeMillis() - start);
                return response;
            } catch (Exception e) {
                log.warn("[ROUTE] 模型 [{}] 调用失败，切换下一个候选: {}", route.name(), e.getMessage());
                metrics.routeFailed(route.name());
                last = e;
            }
        }
        throw (last instanceof RuntimeException runtimeException)
                ? runtimeException
                : new IllegalStateException("所有模型候选均调用失败", last);
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        return chat(request);
    }
}
