package com.scenic.ai.rag.retrieve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 重排（P4 第三段链路）。走硅基流动的 /v1/rerank，默认模型 BAAI/bge-reranker-v2-m3。
 *
 * 设计要点：**失败不阻断检索** —— 未配置 key、超时、报错都返回空列表，
 * 由调用方回退到融合结果，保证「重排坏掉也不影响可用性」。
 */
@Service
@Slf4j
public class Reranker {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final boolean enabled;
    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final long timeoutSeconds;

    public Reranker(@Value("${rerank.enabled:true}") boolean enabled,
                    @Value("${rerank.base-url:https://api.siliconflow.cn/v1}") String baseUrl,
                    @Value("${rerank.api-key:}") String apiKey,
                    @Value("${rerank.model-name:BAAI/bge-reranker-v2-m3}") String modelName,
                    @Value("${rerank.timeout-seconds:30}") long timeoutSeconds) {
        this.enabled = enabled;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.timeoutSeconds = timeoutSeconds;
    }

    public boolean isAvailable() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    public record Ranked(int index, double relevanceScore) {}

    /**
     * @param documents 待重排的文本（下标与返回值 index 对应）
     * @return 按相关度降序的结果；失败时返回空列表
     */
    public List<Ranked> rerank(String query, List<String> documents) {
        if (!isAvailable() || documents == null || documents.isEmpty()) {
            return List.of();
        }

        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", modelName,
                    "query", query,
                    "documents", documents,
                    "top_n", documents.size()
            ));

            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/rerank"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                log.warn("Rerank 返回非 200（{}），本次跳过重排: {}",
                        response.statusCode(), abbreviate(response.body()));
                return List.of();
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode results = root.path("results");
            List<Ranked> ranked = new ArrayList<>();
            for (JsonNode item : results) {
                ranked.add(new Ranked(item.path("index").asInt(-1),
                        item.path("relevance_score").asDouble(0.0)));
            }
            ranked.sort((a, b) -> Double.compare(b.relevanceScore(), a.relevanceScore()));
            log.info("Rerank 完成：{} 条候选，模型 {}", ranked.size(), modelName);
            return ranked;

        } catch (Exception e) {
            log.warn("Rerank 调用失败，回退到融合结果: {}", e.getMessage());
            return List.of();
        }
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
