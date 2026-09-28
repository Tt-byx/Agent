package com.scenic.ai.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * 景区智能导游的声明式 AiService。
 *
 * 由 {{@link com.scenic.ai.config.LangChain4jConfig}} 通过 AiServices 生成实现。
 * 系统提示词外置在 classpath:prompts/scenic-system.txt，改提示词不需要改代码。
 */
public interface ScenicAssistant {

    /** 阻塞式问答，供 REST 接口使用 */
    @SystemMessage(fromResource = "prompts/scenic-system.txt")
    String chat(@UserMessage String message);

    /** 流式问答，供 WebSocket 前端逐 token 推送 */
    @SystemMessage(fromResource = "prompts/scenic-system.txt")
    TokenStream chatStream(@UserMessage String message);
}
