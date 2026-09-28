package com.scenic.ai.controller;

import com.scenic.ai.dto.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class SystemConfigController {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private RestTemplate restTemplate;

    @org.springframework.beans.factory.annotation.Value("${python.backend.url:http://localhost:8000}")
    private String pythonBackendUrl;

    /** 系统健康检查 */
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> status = new HashMap<>();

        // MySQL
        try (Connection conn = dataSource.getConnection()) {
            status.put("mysql", Map.of("status", "ok", "database", conn.getCatalog()));
        } catch (Exception e) {
            status.put("mysql", Map.of("status", "error", "message", e.getMessage()));
        }

        // Python 后端
        try {
            String resp = restTemplate.getForObject(pythonBackendUrl + "/health", String.class);
            status.put("python", Map.of("status", "ok"));
        } catch (Exception e) {
            status.put("python", Map.of("status", "error", "message", e.getMessage()));
        }

        return Result.success(status);
    }
}
