package com.scenic.ai.gateway;

import com.scenic.ai.exception.RedisShortCircuitException;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wave 5 建立 → 2026-10-29（D15）重构 · 流式模型路由。
 *
 * <p>为什么不能直接用裸的 {@code StreamingChatModel}：那等于**为了体验把熔断/分级重试/
 * 主备降级全丢了** —— 主模型故障时流式接口会直接报错，而同步接口还在正常降级。
 * 现在两条链路共用 {@link RouteExecutor} 的同一份骨架，行为不可能再分叉。
 *
 * <p>⭐ 流式特有的可靠性边界（与同步路径最大的不同）：
 * <ul>
 *   <li><b>首包之前</b>失败 → 可以重试、可以静默切换候选（用户什么都还没看到）；</li>
 *   <li><b>首包之后</b>失败 → <b>不能</b>重试也不能切换（否则用户会看到重复或拼接错乱的内容），
 *       只能如实把错误抛给上层，由它收尾（已显示的部分保留）。</li>
 * </ul>
 * 这个「不可回退点」用 {@link RouteExecutor.IrreversibleFailure} 表达，由模板统一放弃。
 *
 * <p>实现上把异步回调收敛成同步等待（{@link CountDownLatch}），因为上游的 AgentLoop 是
 * 同步循环；增量通过 handler 实时透传、不做缓冲，保证首字延迟真正前移。
 *
 * <p>D15 修掉的两个隐患：
 * <ol start="3">
 *   <li>原实现把 {@code handler.onCompleteResponse(response)} 写在 {@code try} 块内，
 *       下游一抛异常就被自己的 catch 接住 → 走「不可回退」分支 → 对已经 complete 的
 *       emitter 再调一次 onError，二次异常。现在成功路径的通知在 try 之外。</li>
 *   <li>原实现超时后不撤销底层调用：模型 SDK 线程还在跑、还持有同一个 handler 引用，
 *       继续往已废弃的尝试里推增量。现在有 {@code accepting} 闸门，超时即关闭。</li>
 * </ol>
 */
@Slf4j
public class RoutedStreamingChatModel implements StreamingChatModel {

    private final List<ModelRoute<StreamingChatModel>> routes;
    private final AgentMetrics metrics;
    private final RetryPolicy retryPolicy;
    private final long waitTimeoutSeconds;

    public RoutedStreamingChatModel(List<ModelRoute<StreamingChatModel>> routes,
                                    AgentMetrics metrics,
                                    RetryPolicy retryPolicy,
                                    long waitTimeoutSeconds) {
        if (routes == null || routes.isEmpty()) {
            throw new IllegalArgumentException("至少要配置一条流式模型路由");
        }
        this.routes = List.copyOf(routes);
        this.metrics = metrics;
        this.retryPolicy = retryPolicy;
        this.waitTimeoutSeconds = waitTimeoutSeconds;
        log.info("[ROUTE-STREAM] 流式路由就绪：{} 条（{}），单候选最多 {} 次尝试",
                routes.size(), routes.stream().map(ModelRoute::name).toList(),
                retryPolicy.maxAttempts());
    }

    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        ChatResponse response;
        try {
            response = RouteExecutor.execute(
                    // D15①：流式路径补上语义路由，与同步路径同构
                    SemanticRouter.order(routes, request, metrics),
                    retryPolicy,
                    metrics,
                    "[ROUTE-STREAM]",
                    route -> invokeOnce(route, request, handler));

        } catch (RouteExecutor.IrreversibleFailure e) {
            // 已经推过内容：不再重试/切换，把真实原因原样交给上层收尾
            handler.onError(e.getCause() == null ? e : e.getCause());
            return;
        } catch (Exception e) {
            RedisShortCircuitException.rethrowIf(e);   // 短路必须冒泡，不能被降级逻辑吞掉
            handler.onError(e);
            return;
        }

        // D15③：成功通知放在 try 之外。即使下游（SseEmitter）此时抛异常，
        // 也不会被上面的 catch 捕获，从而对已经 complete 的 emitter 再调一次 onError。
        handler.onCompleteResponse(response);
    }

    /**
     * 单次尝试：发起一次流式调用，把增量实时透传给下游，同步等它结束。
     *
     * @throws RouteExecutor.IrreversibleFailure 已经推过增量之后失败（不可重试、不可切换）
     */
    private ChatResponse invokeOnce(ModelRoute<StreamingChatModel> route,
                                    ChatRequest request,
                                    StreamingChatResponseHandler handler) throws Exception {

        // 是否已经把增量推给客户端 —— 一旦推过，就跨过了「不可回退点」
        AtomicBoolean streamedToClient = new AtomicBoolean(false);
        // D15④：本次尝试是否仍然「接收」增量。超时/失败后置 false，
        // 让模型线程的迟到回调直接丢弃，而不是继续往下游推。
        AtomicBoolean accepting = new AtomicBoolean(true);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<ChatResponse> completed = new AtomicReference<>();
        AtomicReference<Throwable> failed = new AtomicReference<>();

        route.model().chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                if (!accepting.get()) {
                    return;
                }
                streamedToClient.set(true);
                handler.onPartialResponse(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                completed.set(response);
                done.countDown();
            }

            @Override
            public void onError(Throwable error) {
                failed.set(error);
                done.countDown();
            }
        });

        if (!done.await(waitTimeoutSeconds, TimeUnit.SECONDS)) {
            accepting.set(false);
            TimeoutException timeout = new TimeoutException(
                    "流式响应超时（" + waitTimeoutSeconds + "s 未完成）");
            throw afterFailure(streamedToClient.get(), timeout);
        }
        if (failed.get() != null) {
            accepting.set(false);
            throw afterFailure(streamedToClient.get(), failed.get());
        }

        ChatResponse response = completed.get();
        if (response == null) {
            accepting.set(false);
            throw new IllegalStateException("流式调用结束但没有返回 ChatResponse");
        }

        recordTokenUsage(route.name(), request, response);
        return response;
    }

    /**
     * 2026-10-29（D33）· 流式 token 计数。
     *
     * <p>OpenAI 兼容协议的流式响应默认**不返回** usage —— 需要请求里带
     * {@code stream_options: {include_usage: true}}，而且厂商不一定支持。
     * LangChain4j 1.20.1 的 {@code OpenAiStreamingChatModel} 没有暴露这个开关，
     * 于是 {@code response.tokenUsage()} 很可能恒为 null，流式路径的 token 指标**一条都不记**。
     * 如果流式是主入口（对话场景通常都是），成本看板会严重低估。
     *
     * <p>策略：有 usage 就记真实值（与同步路径同口径）；没有就按字符数估算，
     * 但记到**独立的指标名** {@code scenic.llm.tokens.estimated.*} 上 ——
     * 「缺口可见」比「数字好看」重要，混合口径的成本看板会误导决策。
     */
    private void recordTokenUsage(String routeName, ChatRequest request, ChatResponse response) {
        if (response.tokenUsage() != null) {
            metrics.tokens(response.tokenUsage().inputTokenCount(),
                    response.tokenUsage().outputTokenCount(), routeName);
            return;
        }
        int inputChars = 0;
        if (request != null && request.messages() != null) {
            for (dev.langchain4j.data.message.ChatMessage message : request.messages()) {
                inputChars += charCountOf(message);
            }
        }
        String text = response.aiMessage() == null ? null : response.aiMessage().text();
        int outputChars = text == null ? 0 : text.length();
        metrics.tokensEstimated(estimateTokens(inputChars), estimateTokens(outputChars), routeName);
    }

    /** 粗略估算：中英混排按 1.5 字符/token 折算，只用于兜底，不追求精确 */
    private static int estimateTokens(int chars) {
        return chars <= 0 ? 0 : (int) Math.ceil(chars / 1.5);
    }

    private static int charCountOf(dev.langchain4j.data.message.ChatMessage message) {
        if (message instanceof dev.langchain4j.data.message.UserMessage user) {
            return user.hasSingleText() ? user.singleText().length() : 0;
        }
        if (message instanceof dev.langchain4j.data.message.AiMessage ai) {
            return ai.text() == null ? 0 : ai.text().length();
        }
        if (message instanceof dev.langchain4j.data.message.SystemMessage system) {
            return system.text() == null ? 0 : system.text().length();
        }
        if (message instanceof dev.langchain4j.data.message.ToolExecutionResultMessage tool) {
            return tool.text() == null ? 0 : tool.text().length();
        }
        return 0;
    }

    /**
     * 统一处理「这次尝试失败了」：已推流则包成不可回退异常（模板会立即放弃），
     * 否则原样抛出（模板可以重试 / 切换候选）。
     */
    private Exception afterFailure(boolean streamed, Throwable cause) {
        if (streamed) {
            metrics.degrade("stream_mid_failure");
            return new RouteExecutor.IrreversibleFailure(cause);
        }
        return (cause instanceof Exception exception) ? exception : new RuntimeException(cause);
    }

    @Override
    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        chat(request, handler);
    }
}
