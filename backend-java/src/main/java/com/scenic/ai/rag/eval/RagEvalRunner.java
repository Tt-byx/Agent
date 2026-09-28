package com.scenic.ai.rag.eval;

import com.scenic.ai.rag.retrieve.HybridRetriever;
import com.scenic.ai.rag.retrieve.RagRetriever;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索质量评测（P4）。
 *
 * 评测口径：对每条问题，看**关键事实字符串是否出现在 top-5 召回切片中**。
 * 这是 Recall@5 的可自动化代理指标 —— 事实命中即说明该条证据被召回了，
 * 与人工判断"资料够不够支撑回答"高度一致，且完全可复现。
 */
@Service
@Slf4j
public class RagEvalRunner {

    private static final String DATASET = "rag/eval-dataset.tsv";
    private static final int K = 5;

    private final HybridRetriever hybridRetriever;

    public RagEvalRunner(HybridRetriever hybridRetriever) {
        this.hybridRetriever = hybridRetriever;
    }

    public record Item(int no, String question, String expectedFact) {}

    /** 单条问题的评测结果：三种模式各自的首命中位次（-1 表示 top-5 内未命中） */
    public record Detail(int no, String question, String expectedFact, Map<String, Integer> firstHitRank) {}

    public record Report(int total, int k, Map<String, Double> recallAtK, List<Detail> details) {}

    public List<Item> loadDataset() {
        List<Item> items = new ArrayList<>();
        ClassPathResource resource = new ClassPathResource(DATASET);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            int no = 0;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] parts = trimmed.split("\t");
                if (parts.length < 2) {
                    continue;
                }
                items.add(new Item(++no, parts[0].strip(), parts[1].strip()));
            }
        } catch (Exception e) {
            log.error("评测集加载失败: {}", e.getMessage());
        }
        return items;
    }

    public Report run() {
        List<Item> items = loadDataset();
        Map<String, Integer> hitCounts = new LinkedHashMap<>();
        List<Detail> details = new ArrayList<>();

        Map<String, HybridRetriever.Mode> modes = new LinkedHashMap<>();
        modes.put(HybridRetriever.VECTOR_KEY, HybridRetriever.Mode.VECTOR);
        modes.put(HybridRetriever.HYBRID_KEY, HybridRetriever.Mode.HYBRID);
        modes.put(HybridRetriever.HYBRID_RERANK_KEY, HybridRetriever.Mode.HYBRID_RERANK);
        modes.values().forEach(m -> hitCounts.put(keyOf(m), 0));

        for (Item item : items) {
            Map<String, Integer> firstHitRank = new LinkedHashMap<>();
            for (Map.Entry<String, HybridRetriever.Mode> entry : modes.entrySet()) {
                int rank = -1;
                try {
                    List<RagRetriever.RetrievedChunk> chunks = hybridRetriever.retrieve(item.question(), entry.getValue());
                    for (int i = 0; i < chunks.size() && i < K; i++) {
                        if (chunks.get(i).text().contains(item.expectedFact())) {
                            rank = i + 1;
                            break;
                        }
                    }
                } catch (Exception e) {
                    log.warn("评测第 {} 条（{}）失败: {}", item.no(), entry.getKey(), e.getMessage());
                }
                firstHitRank.put(entry.getKey(), rank);
                if (rank > 0) {
                    hitCounts.merge(entry.getKey(), 1, Integer::sum);
                }
            }
            details.add(new Detail(item.no(), item.question(), item.expectedFact(), firstHitRank));
            log.info("评测 [{}/{}] {} -> {}", item.no(), items.size(), item.question(), firstHitRank);
        }

        Map<String, Double> recall = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : hitCounts.entrySet()) {
            double value = items.isEmpty() ? 0.0 : (double) entry.getValue() / items.size();
            recall.put(entry.getKey(), Math.round(value * 1000) / 1000.0);
        }

        log.info("评测完成：{} 条问题，Recall@{} = {}", items.size(), K, recall);
        return new Report(items.size(), K, recall, details);
    }

    private String keyOf(HybridRetriever.Mode mode) {
        return switch (mode) {
            case VECTOR -> HybridRetriever.VECTOR_KEY;
            case HYBRID -> HybridRetriever.HYBRID_KEY;
            case HYBRID_RERANK -> HybridRetriever.HYBRID_RERANK_KEY;
        };
    }
}
