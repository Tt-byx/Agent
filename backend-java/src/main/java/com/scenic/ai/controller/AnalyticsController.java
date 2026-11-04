package com.scenic.ai.controller;

import com.scenic.ai.exception.RedisShortCircuitException;
import com.scenic.ai.agent.core.AgentLoop;
import com.scenic.ai.dto.Result;
import com.scenic.ai.service.AnalyticsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsController.class);

    @Autowired
    private AnalyticsService analyticsService;

    /** 营销建议复用管理端 AI 的主循环（自带超时/降级/token 统计） */
    @Autowired
    private AgentLoop agentLoop;

    /** 导入 xlsx 数据到数据库 */
    @PostMapping("/import")
    public Result<Map<String, Object>> importData(@RequestParam(defaultValue = "") String filePath) {
        try {
            Map<String, Object> result = analyticsService.importXlsx(filePath);
            return Result.success(result);
        } catch (Exception e) {
            RedisShortCircuitException.rethrowIf(e);   // 短路必须冒泡，不能被降级逻辑吞掉
            return Result.error(500, "导入失败: " + e.getMessage());
        }
    }

    /** 按月消费趋势 */
    @GetMapping("/consumption")
    public Result<Map<String, Object>> consumption() {
        return Result.success(analyticsService.getConsumptionTrend());
    }

    /** 游客画像 */
    @GetMapping("/visitor-profile")
    public Result<Map<String, Object>> visitorProfile() {
        return Result.success(analyticsService.getVisitorProfile());
    }

    /** 满意度分布 */
    @GetMapping("/satisfaction")
    public Result<Map<String, Object>> satisfaction() {
        return Result.success(analyticsService.getSatisfactionDistribution());
    }

    /** 按月客流统计 */
    @GetMapping("/peak-periods")
    public Result<Map<String, Object>> peakPeriods() {
        return Result.success(analyticsService.getPeakPeriods());
    }

    /** 热门景点排行 */
    @GetMapping("/attractions")
    public Result<Map<String, Object>> attractions() {
        return Result.success(analyticsService.getTopAttractions());
    }

    /** 分页筛选行为数据 */
    @GetMapping("/data")
    public Result<Map<String, Object>> filteredData(
            @RequestParam(required = false) String attraction,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String ageGroup,
            @RequestParam(required = false) String gender,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(analyticsService.getFilteredData(attraction, startDate, endDate, ageGroup, gender, page, size));
    }

    /** 营销分析报告数据 */
    @GetMapping("/report")
    public Result<Map<String, Object>> report() {
        return Result.success(analyticsService.getReportData());
    }

    /**
     * 2026-11-03 新增：基于数据摘要生成营销建议。
     *
     * <p><b>为什么现在才补</b>：前端两个页面（消费分析、营销报告）早就在调
     * `POST /api/analytics/marketing`，期望返回 `{suggestions}`，但后端**从来没有这个映射**。
     * 结果是点「生成营销建议」必然 500 —— 而且因为 `GlobalExceptionHandler` 把
     * 「找不到处理器」也兜成了「服务内部错误」，排查时完全看不出是接口缺失。
     *
     * <p>实现上复用 {@code AgentLoop}（与 AdminAIController 的管理端问答同一条链路），
     * 好处是自动获得超时、降级、token 统计这些能力，不需要再写一套模型调用。
     *
     * <p>sessionId 固定为 {@code admin-marketing}：营销建议不需要跨次对话的上下文，
     * 但也没有必要每次都新建（Redis 里会堆一堆一次性 key）。固定 key 的副作用是
     * 建议会带着上一次的内容，所以在 prompt 里明确要求「只针对本次数据」。
     */
    @PostMapping("/marketing")
    public Result<Map<String, Object>> marketing(@RequestBody Map<String, String> body) {
        String summary = body.getOrDefault("stats_summary", "");
        if (summary == null || summary.isBlank()) {
            return Result.error(400, "缺少数据摘要");
        }
        try {
            String prompt = "你是景区营销策划专家。以下是景区当前的运营数据摘要：\n"
                    + summary
                    + "\n\n请只针对本次数据，给出 3-5 条可直接执行的营销建议。"
                    + "要求：每条一句话、以序号开头；用纯文本中文，不要 Markdown 标记；"
                    + "每条都要能对应到上面数据里的具体现象。";
            AgentLoop.Result r = agentLoop.run("admin-marketing", "admin-marketing", prompt);
            Map<String, Object> result = new HashMap<>();
            result.put("suggestions", r.answer());
            return Result.success(result);
        } catch (Exception e) {
            RedisShortCircuitException.rethrowIf(e);   // 短路必须冒泡，不能被降级逻辑吞掉
            log.error("生成营销建议失败: {}", e.getMessage());
            return Result.error(500, "营销建议生成失败，请稍后重试");
        }
    }
}
