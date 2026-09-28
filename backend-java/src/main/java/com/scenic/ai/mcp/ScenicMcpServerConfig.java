package com.scenic.ai.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scenic.ai.agent.tools.ScenicTools;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * P6 · 景区导览 MCP Server（Streamable HTTP）。
 *
 * 设计要点：
 *   1. **复用同一份工具实现** —— 这里暴露的 3 个 MCP 工具直接调用 {@link ScenicTools} 的方法，
 *      与 P5 注册给 LangChain4j 的 {@code @Tool} 是**同一段业务逻辑**。
 *      这正是 FC 与 MCP 关系的实证：FC 是「模型调用函数的能力」，MCP 是「工具接入的标准协议」。
 *   2. **不引入 Spring AI** —— 用 mcp-core 自带的 Jakarta Servlet 传输，
 *      直接挂到 Spring Boot 的嵌入式 Tomcat 上，避免与 LangChain4j 主线冲突。
 *   3. 端点固定为 {@code /mcp}，外部客户端（Cherry Studio / Claude Desktop）填这个 URL 即可。
 */
@Configuration
@Slf4j
public class ScenicMcpServerConfig {

    @Value("${mcp.endpoint:/mcp}")
    private String mcpEndpoint;

    @Value("${mcp.server-name:scenic-mcp-server}")
    private String serverName;

    @Value("${mcp.server-version:1.0.0}")
    private String serverVersion;

    /** Streamable HTTP 传输提供者（本质是个 HttpServlet） */
    @Bean
    public HttpServletStreamableServerTransportProvider mcpTransportProvider(ObjectMapper objectMapper) {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .mcpEndpoint(mcpEndpoint)
                .build();
    }

    /** 把传输提供者注册成 Servlet，交给嵌入式 Tomcat 托管 */
    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
            HttpServletStreamableServerTransportProvider transportProvider) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(transportProvider, mcpEndpoint, mcpEndpoint + "/*");
        registration.setName("scenicMcpServlet");
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean
    public McpSyncServer scenicMcpServer(HttpServletStreamableServerTransportProvider transportProvider,
                                         ScenicTools tools) {
        McpSyncServer server = McpServer.sync(transportProvider)
                .serverInfo(serverName, serverVersion)
                .instructions("灵山胜境景区导览 MCP 服务：提供实时天气查询、门票价格计算、演出时刻查询三项能力。")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(
                        weatherTool(tools),
                        ticketPriceTool(tools),
                        nextShowTimeTool(tools)
                )
                .build();

        log.info("MCP Server 就绪：端点 {}{}，已暴露 3 个工具（query_weather / calculate_ticket_price / get_next_show_time）",
                mcpEndpoint, "");
        return server;
    }

    // ============================================================
    // 工具 1：实时天气
    // ============================================================

    private McpServerFeatures.SyncToolSpecification weatherTool(ScenicTools tools) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "city", Map.of("type", "string", "description", "城市名称，例如：无锡、上海、苏州")
                ),
                "required", List.of("city")
        );

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder("query_weather", schema)
                        .description("查询指定城市的实时天气，返回温度、天气状况、风力与出行建议。"
                                + "当需要了解天气、是否要带伞、穿衣建议时使用。")
                        .build())
                .callHandler((exchange, request) -> {
                    String city = asString(request.arguments().get("city"));
                    String result = tools.queryWeather(city);
                    log.info("[MCP] query_weather(city={}) -> {}", city, result);
                    return McpSchema.CallToolResult.builder().addTextContent(result).build();
                })
                .build();
    }

    // ============================================================
    // 工具 2：门票价格计算
    // ============================================================

    private McpServerFeatures.SyncToolSpecification ticketPriceTool(ScenicTools tools) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "visitorType", Map.of("type", "string",
                                "description", "游客类型，可取：成人、学生、老人、儿童、军人或残疾人"),
                        "count", Map.of("type", "integer", "description", "人数，正整数")
                ),
                "required", List.of("visitorType", "count")
        );

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder("calculate_ticket_price", schema)
                        .description("计算景区门票总价，包含优惠规则（成人210元 / 半价105元 / 免票）。"
                                + "当需要算门票多少钱、几个人一共多少时使用。")
                        .build())
                .callHandler((exchange, request) -> {
                    String visitorType = asString(request.arguments().get("visitorType"));
                    int count = asInt(request.arguments().get("count"));
                    String result = tools.calculateTicketPrice(visitorType, count);
                    log.info("[MCP] calculate_ticket_price(type={}, count={}) -> {}", visitorType, count, result);
                    return McpSchema.CallToolResult.builder().addTextContent(result).build();
                })
                .build();
    }

    // ============================================================
    // 工具 3：下一场演出时间
    // ============================================================

    private McpServerFeatures.SyncToolSpecification nextShowTimeTool(ScenicTools tools) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "showName", Map.of("type", "string", "description", "演出名称，可取：九龙灌浴 或 吉祥颂")
                ),
                "required", List.of("showName")
        );

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder("get_next_show_time", schema)
                        .description("查询景区演出的下一场开始时间与剩余等待时长（基于当前时间计算）。"
                                + "当需要知道表演几点开始、现在还赶不赶得上时使用。")
                        .build())
                .callHandler((exchange, request) -> {
                    String showName = asString(request.arguments().get("showName"));
                    String result = tools.getNextShowTime(showName);
                    log.info("[MCP] get_next_show_time(name={}) -> {}", showName, result);
                    return McpSchema.CallToolResult.builder().addTextContent(result).build();
                })
                .build();
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
