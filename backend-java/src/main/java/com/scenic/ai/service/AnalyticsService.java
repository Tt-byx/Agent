package com.scenic.ai.service;

import java.util.Map;

public interface AnalyticsService {
    Map<String, Object> importXlsx(String filePath) throws Exception;
    Map<String, Object> getConsumptionTrend();
    Map<String, Object> getVisitorProfile();
    Map<String, Object> getSatisfactionDistribution();
    Map<String, Object> getPeakPeriods();
    Map<String, Object> getTopAttractions();
    Map<String, Object> getFilteredData(String attraction, String startDate, String endDate,
                                         String ageGroup, String gender, int page, int size);
    Map<String, Object> getReportData();
}
