package com.scenic.ai.gateway;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 2026-10-29（D15）· 语义路由的**唯一实现**：判断请求复杂度，据此重排候选顺序。
 *
 * <p>改造前只有同步路径有这个能力，流式路径直接 `for (Route route : routes)` ——
 * 同一个问题走 {@code /api/chat} 是「复杂 → 强模型优先」，走 {@code /api/chat/stream}
 * 却永远是配置顺序，等于**同一问题两个入口用不同模型**。而
 * {@code scenic.route.decision} 指标只统计同步路径，看完面板会误以为分流在工作。
 *
 * <p>现在两条链路都调 {@link #order}，排序逻辑只有一份。
 */
@Slf4j
public final class SemanticRouter {

    /**
     * 复杂意图标记：出现这类词说明需要综合多步信息（对比、规划、分析），
     * 值得优先用更强的模型。
     */
    private static final List<String> COMPLEX_MARKERS = List.of(
            "分析", "对比", "规划", "安排", "为什么", "总结", "推荐路线",
            "怎么选", "区别", "分别", "综合", "方案", "预算", "几天", "行程");

    private SemanticRouter() {
    }

    /**
     * 按复杂度决定候选顺序：复杂请求把强模型提到最前，其余保持配置顺序（快模型优先）。
     *
     * @return 新的顺序；简单请求直接返回原列表（不复制）
     */
    public static <M> List<ModelRoute<M>> order(List<ModelRoute<M>> routes,
                                                ChatRequest request,
                                                AgentMetrics metrics) {
        if (routes.size() < 2) {
            return routes;
        }
        boolean complex = isComplex(request);
        metrics.routeDecision(complex);
        if (!complex) {
            return routes;
        }
        List<ModelRoute<M>> ordered = new ArrayList<>(routes);
        ordered.sort(Comparator.comparing((ModelRoute<M> route) -> route.isStrong()).reversed());
        log.info("[ROUTE] 请求判为复杂，候选顺序调整为：{}",
                ordered.stream().map(ModelRoute::name).toList());
        return ordered;
    }

    /**
     * 复杂度启发式（零额外成本，不引入分类模型）。三条判据，均已按**本项目实际的请求结构**校准。
     *
     * <p>2026-10-29（D02）修正了三处「判据语义与实际结构脱节」的问题：
     *
     * <ol>
     *   <li><b>轮内消息数 &gt; 8</b>：统计的是「本轮 ReAct 循环已经走过的步数」，
     *       <b>不是</b>历史对话轮数 —— {@code AgentLoop} 每轮重建消息列表，
     *       历史对话被拼进 SystemMessage 的文本里，不占 message 位置。
     *       所以这条只在「已经调了几轮工具」时成立，语义上等于「这轮任务确实不简单」。</li>
     *   <li><b>非系统消息总字数 &gt; 2000</b>：统计用户输入 + 模型文字 + <b>工具返回</b>。
     *       原实现只统计 {@code UserMessage} 与 {@code AiMessage}（其余类型一律返回空串），
     *       于是这个分支几乎不可达 —— 用户消息上限 2000 字，等于也过不了 {@code > 2000}。
     *       现在把工具观察计入：一次知识库检索返回 500~1500 字，两条就过阈值，
     *       这正是「上下文要炸了」的真实来源。
     *       SystemMessage 仍然排除 —— 它每轮固定注入人设与记忆（5000+ 字），
     *       计入会让所有请求都判为复杂，判据彻底失效。</li>
     *   <li><b>关键词命中</b>：「分析/对比/规划/安排」这类需要综合的意图。</li>
     * </ol>
     *
     * <p>取舍说明：真正的语义分类器需要**多一次模型调用**才能判断该用哪个模型，
     * 那次调用的成本与延迟往往超过分流带来的收益 —— 启发式在这里"够用就好"。
     */
    static boolean isComplex(ChatRequest request) {
        if (request == null || request.messages() == null) {
            return false;
        }
        List<ChatMessage> messages = request.messages();
        if (messages.size() > 8) {
            return true;
        }
        int nonSystemChars = 0;
        String lastUserText = "";
        for (ChatMessage message : messages) {
            // SystemMessage 刻意不计数（见 javadoc 第 2 条）
            if (message instanceof UserMessage user) {
                // hasSingleText 而不是直接 singleText：多模态输入下 singleText 会抛异常
                String text = user.hasSingleText() ? user.singleText() : "";
                lastUserText = text;
                nonSystemChars += text.length();
            } else if (message instanceof AiMessage ai) {
                nonSystemChars += ai.text() == null ? 0 : ai.text().length();
            } else if (message instanceof ToolExecutionResultMessage tool) {
                nonSystemChars += tool.text() == null ? 0 : tool.text().length();
            }
        }
        if (nonSystemChars > 2000) {
            return true;
        }
        for (String marker : COMPLEX_MARKERS) {
            if (lastUserText.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
