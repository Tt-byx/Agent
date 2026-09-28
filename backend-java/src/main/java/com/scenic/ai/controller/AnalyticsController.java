package com.scenic.ai.controller;

import com.scenic.ai.dto.Result;
import com.scenic.ai.service.AnalyticsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    @Autowired
    private AnalyticsService analyticsService;

    /** 导入 xlsx 数据到数据库 */
    @PostMapping("/import")
    public Result<Map<String, Object>> importData(@RequestParam(defaultValue = "") String filePath) {
        try {
            Map<String, Object> result = analyticsService.importXlsx(filePath);
            return Result.success(result);
        } catch (Exception e) {
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
}
