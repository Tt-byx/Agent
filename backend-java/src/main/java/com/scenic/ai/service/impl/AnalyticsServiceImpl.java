package com.scenic.ai.service.impl;

import com.scenic.ai.exception.RedisShortCircuitException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.scenic.ai.entity.TourismData;
import com.scenic.ai.mapper.TourismDataMapper;
import com.scenic.ai.service.AnalyticsService;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Service
@Slf4j
public class AnalyticsServiceImpl implements AnalyticsService {

    @Autowired
    private TourismDataMapper tourismDataMapper;

    /**
     * 2026-11-03 修复：这里原来写的是 {@code @Value("${upload.dir:D:/scenic_uploads}")}，
     * 但 application.yml 里定义的 key 是 **scenic.upload.dir** —— 名字对不上，
     * 于是永远取默认值 `D:/scenic_uploads`。
     *
     * <p>后果很隐蔽：在宿主机用 IDE 跑时，`D:/scenic_uploads` 真的存在，导入正常；
     * 一旦进容器（挂载点是 `/data/scenic_uploads`），这个路径根本不存在，
     * 导入就报「找不到 xlsx 文件」，而错误信息里显示的偏偏是那个宿主机路径，
     * 看着像「文件没放对」，实际是配置项名字写错了。
     *
     * <p>默认值保留，方便宿主机不走配置也能跑。
     */
    @Value("${scenic.upload.dir:D:/scenic_uploads}")
    private String uploadDir;

    @Override
    @Transactional   // 2026-11-03：整个导入一个事务（见 batchInsert 的说明）
    public Map<String, Object> importXlsx(String filePath) throws Exception {
        // 查找文件：优先指定路径，然后项目根目录，最后上传目录
        File file = null;
        if (filePath != null && !filePath.isEmpty()) {
            file = new File(filePath);
        }
        if (file == null || !file.exists()) {
            file = new File("景点景区旅游数据行为分析数据.xlsx");
        }
        if (!file.exists()) {
            file = new File(uploadDir, "景点景区旅游数据行为分析数据.xlsx");
        }
        if (!file.exists()) {
            throw new FileNotFoundException("找不到 xlsx 文件，请将文件放在项目根目录或上传目录: " + uploadDir);
        }

        log.info("开始导入 xlsx: {}", file.getAbsolutePath());

        // 清空旧数据
        tourismDataMapper.delete(null);

        int totalRows = 0;
        int batch_size = 500;
        List<TourismData> batch = new ArrayList<>();

        try (Workbook workbook = new XSSFWorkbook(new FileInputStream(file))) {
            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rowIterator = sheet.iterator();

            // 跳过表头
            if (rowIterator.hasNext()) {
                rowIterator.next();
            }

            while (rowIterator.hasNext()) {
                Row row = rowIterator.next();
                TourismData data = parseRow(row);
                if (data != null) {
                    batch.add(data);
                    if (batch.size() >= batch_size) {
                        batchInsert(batch);
                        totalRows += batch.size();
                        batch.clear();
                    }
                }
            }

            // 插入剩余
            if (!batch.isEmpty()) {
                batchInsert(batch);
                totalRows += batch.size();
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("totalRows", totalRows);
        result.put("message", "导入成功");
        log.info("xlsx 导入完成: {} 条记录", totalRows);
        return result;
    }

    private TourismData parseRow(Row row) {
        try {
            TourismData data = new TourismData();
            data.setTouristId(getCellString(row, 0));
            data.setNickname(getCellString(row, 1));
            data.setAge(getCellInt(row, 2));
            data.setGender(getCellString(row, 3));
            data.setAttractionName(getCellString(row, 4));
            // col 5 = attraction_content (跳过，太长)
            data.setAttractionType(getCellString(row, 6));
            data.setVisitDate(getCellDate(row, 7));
            data.setStayDuration(getCellDecimal(row, 8));
            data.setTicketCost(getCellDecimal(row, 9));
            data.setFoodCost(getCellDecimal(row, 10));
            data.setShoppingCost(getCellDecimal(row, 11));
            data.setTransportCost(getCellDecimal(row, 12));
            data.setEntertainmentCost(getCellDecimal(row, 13));
            data.setTotalCost(getCellDecimal(row, 14));
            data.setGroupSize(getCellInt(row, 15));
            data.setSatisfaction(getCellDecimal(row, 16));
            data.setCreatedAt(LocalDateTime.now());
            return data;
        } catch (Exception e) {
            RedisShortCircuitException.rethrowIf(e);   // 短路必须冒泡，不能被降级逻辑吞掉
            log.debug("跳过无效行 {}: {}", row.getRowNum(), e.getMessage());
            return null;
        }
    }

    /**
     * 分批写入。
     *
     * <p>2026-11-03 记录：这个方法名叫 batchInsert，但内部是**逐条** {@code insert}，
     * 而且每一条都在自己的事务里 —— MySQL 默认 {@code innodb_flush_log_at_trx_commit=1}，
     * 每次 commit 都要 fsync。实测导入 14 万行只有 **87 行/秒**，全量要 25 分钟。
     *
     * <p>当前缓解办法：在 {@link #importXlsx} 上加了 {@code @Transactional}，
     * 让这 500 条一批的 insert 合并成一次提交，commit 次数从 14 万降到 1 次。
     *
     * <p>彻底的做法是用真正的 JDBC 批量（{@code JdbcTemplate.batchUpdate} +
     * 连接串 {@code rewriteBatchedStatements=true}），但那要手写 INSERT 语句、
     * 或者给 Mapper 加 XML，改动面大。当前数据量下 {@code @Transactional} 已经够用。
     */
    private void batchInsert(List<TourismData> batch) {
        for (TourismData data : batch) {
            tourismDataMapper.insert(data);
        }
    }

    /**
     * 2026-11-04（D22）修：数值与日期的解析。
     *
     * <p>原实现 {@code String.valueOf((long) cell.getNumericCellValue())} 有两个问题：
     * <ol>
     *   <li>日期单元格会被读成 Excel 序列号（如 45217），不是 2024-01-01</li>
     *   <li>大数强制转 long 会丢小数，且 cell.toString() 对大数会输出科学计数法（1.23E10）</li>
     * </ol>
     *
     * <p>修法：
     * <ul>
     *   <li>日期分支用 DateUtil.isCellDateFormatted 判断，格式化成 yyyy-MM-dd</li>
     *   <li>数值用 BigDecimal.toPlainString 避免科学计数法，stripTrailingZeros 去掉多余小数</li>
     * </ul>
     */
    private String getCellString(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return "";
        if (cell.getCellType() == CellType.NUMERIC) {
            // 日期：Excel 内部存的是序列号，要按日期格式化
            if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
                return new java.text.SimpleDateFormat("yyyy-MM-dd")
                        .format(cell.getDateCellValue());
            }
            // 数值：toPlainString 避免 1.23E10 的科学计数法
            return java.math.BigDecimal.valueOf(cell.getNumericCellValue())
                    .stripTrailingZeros()
                    .toPlainString();
        }
        return cell.toString().trim();
    }

    private Integer getCellInt(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC) {
            return (int) cell.getNumericCellValue();
        }
        try {
            return Integer.parseInt(cell.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal getCellDecimal(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC) {
            return BigDecimal.valueOf(cell.getNumericCellValue());
        }
        try {
            return new BigDecimal(cell.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalDate getCellDate(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            java.util.Date date = cell.getDateCellValue();
            return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        }
        try {
            return LocalDate.parse(cell.toString().trim().substring(0, 10));
        } catch (Exception e) {
            RedisShortCircuitException.rethrowIf(e);   // 短路必须冒泡，不能被降级逻辑吞掉
            return null;
        }
    }

    // ============ 查询统计方法 ============

    @Override
    public Map<String, Object> getConsumptionTrend() {
        List<Map<String, Object>> rows = tourismDataMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TourismData>()
                        .select("DATE_FORMAT(visit_date, '%Y-%m') as month",
                                "AVG(ticket_cost) as avg_ticket",
                                "AVG(food_cost) as avg_food",
                                "AVG(shopping_cost) as avg_shopping",
                                "AVG(transport_cost) as avg_transport",
                                "AVG(entertainment_cost) as avg_entertainment",
                                "AVG(total_cost) as avg_total",
                                "COUNT(*) as count")
                        .groupBy("DATE_FORMAT(visit_date, '%Y-%m')")
                        .orderByAsc("month"));

        Map<String, Object> result = new HashMap<>();
        result.put("data", rows);
        return result;
    }

    @Override
    public Map<String, Object> getVisitorProfile() {
        // 年龄分布
        List<Map<String, Object>> ageDistribution = tourismDataMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TourismData>()
                        .select("CASE " +
                                "  WHEN age < 18 THEN '18岁以下' " +
                                "  WHEN age BETWEEN 18 AND 25 THEN '18-25岁' " +
                                "  WHEN age BETWEEN 26 AND 35 THEN '26-35岁' " +
                                "  WHEN age BETWEEN 36 AND 45 THEN '36-45岁' " +
                                "  WHEN age BETWEEN 46 AND 55 THEN '46-55岁' " +
                                "  ELSE '55岁以上' END as age_group",
                                "COUNT(*) as count")
                        .groupBy("age_group"));

        // 性别分布
        List<Map<String, Object>> genderDistribution = tourismDataMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TourismData>()
                        .select("gender", "COUNT(*) as count")
                        .groupBy("gender"));

        // 团队规模分布
        List<Map<String, Object>> groupDistribution = tourismDataMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TourismData>()
                        .select("group_size", "COUNT(*) as count")
                        .groupBy("group_size")
                        .orderByAsc("group_size"));

        Map<String, Object> result = new HashMap<>();
        result.put("ageDistribution", ageDistribution);
        result.put("genderDistribution", genderDistribution);
        result.put("groupDistribution", groupDistribution);
        return result;
    }

    @Override
    public Map<String, Object> getSatisfactionDistribution() {
        List<Map<String, Object>> rows = tourismDataMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TourismData>()
                        .select("FLOOR(satisfaction) as score", "COUNT(*) as count")
                        .groupBy("FLOOR(satisfaction)")
                        .orderByAsc("score"));

        Map<String, Object> result = new HashMap<>();
        result.put("data", rows);
        return result;
    }

    @Override
    public Map<String, Object> getPeakPeriods() {
        List<Map<String, Object>> rows = tourismDataMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TourismData>()
                        .select("DATE_FORMAT(visit_date, '%Y-%m') as month",
                                "COUNT(*) as visitor_count",
                                "COUNT(DISTINCT tourist_id) as unique_visitors")
                        .groupBy("DATE_FORMAT(visit_date, '%Y-%m')")
                        .orderByAsc("month"));

        Map<String, Object> result = new HashMap<>();
        result.put("data", rows);
        return result;
    }

    @Override
    public Map<String, Object> getTopAttractions() {
        List<Map<String, Object>> rows = tourismDataMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TourismData>()
                        .select("attraction_name", "attraction_type",
                                "COUNT(*) as visit_count",
                                "AVG(satisfaction) as avg_satisfaction",
                                "AVG(total_cost) as avg_cost")
                        .groupBy("attraction_name", "attraction_type")
                        .orderByDesc("visit_count")
                        .last("LIMIT 10"));

        Map<String, Object> result = new HashMap<>();
        result.put("data", rows);
        return result;
    }

    @Override
    public Map<String, Object> getFilteredData(String attraction, String startDate, String endDate,
                                                String ageGroup, String gender, int page, int size) {
        LambdaQueryWrapper<TourismData> wrapper = new LambdaQueryWrapper<>();
        if (attraction != null && !attraction.isEmpty()) {
            wrapper.eq(TourismData::getAttractionName, attraction);
        }
        if (startDate != null && !startDate.isEmpty()) {
            wrapper.ge(TourismData::getVisitDate, LocalDate.parse(startDate));
        }
        if (endDate != null && !endDate.isEmpty()) {
            wrapper.le(TourismData::getVisitDate, LocalDate.parse(endDate));
        }
        if (gender != null && !gender.isEmpty()) {
            wrapper.eq(TourismData::getGender, gender);
        }
        // ageGroup: "18-25", "26-35", "36-45", "46-55", "55+"
        if (ageGroup != null && !ageGroup.isEmpty()) {
            switch (ageGroup) {
                case "<18" -> wrapper.lt(TourismData::getAge, 18);
                case "18-25" -> wrapper.ge(TourismData::getAge, 18).le(TourismData::getAge, 25);
                case "26-35" -> wrapper.ge(TourismData::getAge, 26).le(TourismData::getAge, 35);
                case "36-45" -> wrapper.ge(TourismData::getAge, 36).le(TourismData::getAge, 45);
                case "46-55" -> wrapper.ge(TourismData::getAge, 46).le(TourismData::getAge, 55);
                case "55+" -> wrapper.ge(TourismData::getAge, 55);
            }
        }
        wrapper.orderByDesc(TourismData::getCreatedAt);

        // 分页
        wrapper.last("LIMIT " + size + " OFFSET " + (page - 1) * size);
        List<TourismData> records = tourismDataMapper.selectList(wrapper);

        // 总数（用同样条件但去掉 LIMIT）
        LambdaQueryWrapper<TourismData> countWrapper = new LambdaQueryWrapper<>();
        if (attraction != null && !attraction.isEmpty()) {
            countWrapper.eq(TourismData::getAttractionName, attraction);
        }
        if (startDate != null && !startDate.isEmpty()) {
            countWrapper.ge(TourismData::getVisitDate, LocalDate.parse(startDate));
        }
        if (endDate != null && !endDate.isEmpty()) {
            countWrapper.le(TourismData::getVisitDate, LocalDate.parse(endDate));
        }
        if (gender != null && !gender.isEmpty()) {
            countWrapper.eq(TourismData::getGender, gender);
        }
        if (ageGroup != null && !ageGroup.isEmpty()) {
            switch (ageGroup) {
                case "<18" -> countWrapper.lt(TourismData::getAge, 18);
                case "18-25" -> countWrapper.ge(TourismData::getAge, 18).le(TourismData::getAge, 25);
                case "26-35" -> countWrapper.ge(TourismData::getAge, 26).le(TourismData::getAge, 35);
                case "36-45" -> countWrapper.ge(TourismData::getAge, 36).le(TourismData::getAge, 45);
                case "46-55" -> countWrapper.ge(TourismData::getAge, 46).le(TourismData::getAge, 55);
                case "55+" -> countWrapper.ge(TourismData::getAge, 55);
            }
        }
        Long total = tourismDataMapper.selectCount(countWrapper);

        Map<String, Object> result = new HashMap<>();
        result.put("records", records);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    @Override
    public Map<String, Object> getReportData() {
        Map<String, Object> report = new HashMap<>();

        // 1. 高价值画像：消费TOP10
        List<Map<String, Object>> topSpenders = tourismDataMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TourismData>()
                        .select("tourist_id", "nickname",
                                "SUM(total_cost) as total_spent",
                                "COUNT(*) as visit_count",
                                "AVG(stay_duration) as avg_stay")
                        .groupBy("tourist_id", "nickname")
                        .orderByDesc("total_spent")
                        .last("LIMIT 10"));
        report.put("topSpenders", topSpenders);

        // 2. 频次TOP10
        List<Map<String, Object>> frequentVisitors = tourismDataMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TourismData>()
                        .select("tourist_id", "nickname",
                                "COUNT(*) as visit_count",
                                "SUM(total_cost) as total_spent")
                        .groupBy("tourist_id", "nickname")
                        .orderByDesc("visit_count")
                        .last("LIMIT 10"));
        report.put("frequentVisitors", frequentVisitors);

        // 3. 消费偏好：按年龄段
        List<Map<String, Object>> consumptionByAge = tourismDataMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TourismData>()
                        .select("CASE " +
                                "  WHEN age < 18 THEN '18岁以下' " +
                                "  WHEN age BETWEEN 18 AND 25 THEN '18-25岁' " +
                                "  WHEN age BETWEEN 26 AND 35 THEN '26-35岁' " +
                                "  WHEN age BETWEEN 36 AND 45 THEN '36-45岁' " +
                                "  WHEN age BETWEEN 46 AND 55 THEN '46-55岁' " +
                                "  ELSE '55岁以上' END as age_group",
                                "AVG(ticket_cost) as avg_ticket",
                                "AVG(food_cost) as avg_food",
                                "AVG(shopping_cost) as avg_shopping",
                                "AVG(transport_cost) as avg_transport",
                                "AVG(entertainment_cost) as avg_entertainment")
                        .groupBy("age_group"));
        report.put("consumptionByAge", consumptionByAge);

        // 4. 消费偏好：按性别
        List<Map<String, Object>> consumptionByGender = tourismDataMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TourismData>()
                        .select("gender",
                                "AVG(ticket_cost) as avg_ticket",
                                "AVG(food_cost) as avg_food",
                                "AVG(shopping_cost) as avg_shopping",
                                "AVG(transport_cost) as avg_transport",
                                "AVG(entertainment_cost) as avg_entertainment")
                        .groupBy("gender"));
        report.put("consumptionByGender", consumptionByGender);

        // 5. 逗留时长与消费关联
        List<Map<String, Object>> dwellConsumption = tourismDataMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TourismData>()
                        .select("CASE " +
                                "  WHEN stay_duration < 2 THEN '2小时以内' " +
                                "  WHEN stay_duration BETWEEN 2 AND 4 THEN '2-4小时' " +
                                "  WHEN stay_duration BETWEEN 4 AND 6 THEN '4-6小时' " +
                                "  WHEN stay_duration BETWEEN 6 AND 8 THEN '6-8小时' " +
                                "  ELSE '8小时以上' END as stay_group",
                                "AVG(total_cost) as avg_cost",
                                "AVG(satisfaction) as avg_satisfaction",
                                "COUNT(*) as count")
                        .groupBy("stay_group"));
        report.put("dwellConsumption", dwellConsumption);

        // 6. 淡旺季趋势
        List<Map<String, Object>> seasonalTrend = tourismDataMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TourismData>()
                        .select("DATE_FORMAT(visit_date, '%Y-%m') as month",
                                "COUNT(*) as visitor_count",
                                "SUM(total_cost) as total_revenue",
                                "AVG(satisfaction) as avg_satisfaction")
                        .groupBy("DATE_FORMAT(visit_date, '%Y-%m')")
                        .orderByAsc("month"));
        report.put("seasonalTrend", seasonalTrend);

        // 7. 数据摘要文本（供AI生成建议）
        Long totalRecords = tourismDataMapper.selectCount(null);
        report.put("totalRecords", totalRecords);

        return report;
    }
}
