package com.scenic.ai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 冒烟测试：验证 Spring 上下文能否完整装配（含 LangChain4j 装配与提示词资源加载）。
 *
 * 必须使用 RANDOM_PORT：{@code WebSocketConfig} 中的 ServletServerContainerFactoryBean
 * 依赖真实 Servlet 容器提供的 {@code jakarta.websocket.server.ServerContainer}，
 * 而默认的 MOCK 环境下该属性不存在，会导致容器启动失败（改造前即存在的遗留问题）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScenicAiApplicationTests {

    @Test
    void contextLoads() {
    }
}
