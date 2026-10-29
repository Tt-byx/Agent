package com.scenic.ai.agent.tools;

import com.scenic.ai.rag.retrieve.HybridRetriever;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 之前每一轮对话都是无条件的查知识库，浪费资源，。现在把查知识库变成一个可调用的工具，需要调用的时候才调用
 */
@Component
@Slf4j
public class KnowledgeTools {

    private final HybridRetriever hybridRetriever;

    public KnowledgeTools(HybridRetriever hybridRetriever) {
        this.hybridRetriever = hybridRetriever;
    }

    @Tool("检索灵山胜境景区的知识库，返回与问题最相关的资料片段。"
            + "当游客询问景区的历史、文化、景点介绍、景点参数、门票规则、游览路线、开放信息等**事实性问题**时，必须调用本工具获取依据，不要凭记忆回答。"
            + "闲聊、打招呼、以及问天气/票价计算/演出时刻这类有专用工具的问题，不要调用本工具。")
    public String searchKnowledgeBase(@P("检索用的查询语句，建议用游客原话或提炼后的关键词，例如：灵山大佛通高多少米") String query) {
        String context = hybridRetriever.retrieveContext(query);
        if (context == null || context.isBlank()) {
            String empty = "知识库中没有检索到与「" + query + "」相关的内容。";
            log.info("[TOOL] search_knowledge_base(query={}) -> {}", query, empty);
            return empty;
        }
        log.info("[TOOL] search_knowledge_base(query={}) -> 命中 {} 字上下文", query, context.length());
        return context;
    }
}
