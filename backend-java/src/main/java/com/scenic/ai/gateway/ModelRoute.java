package com.scenic.ai.gateway;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * 2026-10-29（D15）· 一条模型候选（同步 / 流式共用）。
 *
 * <p>改造前有两个各自独立的 {@code Route} record：
 * {@code RoutedChatModel.Route(name, ChatModel, CircuitBreaker)} 与
 * {@code RoutedStreamingChatModel.Route(name, StreamingChatModel, CircuitBreaker)}。
 * 它们描述的是同一件事，却因为「模型类型不同」被写了两遍 —— 于是所有围绕候选的逻辑
 * （熔断跳过、分级重试、复杂度排序、不可回退点）也跟着写了两遍，然后各自演化出差异。
 *
 * <p>现在统一成泛型 {@code ModelRoute<M>}：{@code M} 是 {@code ChatModel} 或
 * {@code StreamingChatModel}，其余字段完全一致。
 *
 * <p>{@link Tier} 从 {@code RoutedChatModel} 搬到这里，因为它是「候选的属性」，
 * 不属于某一条链路。
 */
public record ModelRoute<M>(String name, Tier tier, M model, CircuitBreaker breaker) {

    /**
     * 模型档位。**由装配方显式声明，不从候选名猜**。
     *
     * <p>原实现用「route.name() 含 'pro'」判断强弱模型，依赖命名约定。实测默认配置下
     * 主模型是 {@code deepseek-v4-pro}、备模型也是 {@code deepseek-v4-pro} —— 两条都含 "pro"，
     * 排序结果与配置顺序完全一致，语义路由**从未真正生效**（D02）。
     * 现在改为显式声明，并让 {@code LangChain4jConfig} 在装配期校验「档位是否真的分得开」。
     */
    public enum Tier {
        /** 快而省：疑难问题之外默认走它 */
        FAST,
        /** 强而贵：复杂问题优先，主模型故障时兜底 */
        STRONG
    }

    public boolean isStrong() {
        return tier == Tier.STRONG;
    }
}
