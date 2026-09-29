package com.scenic.ai.rag.eval;

import com.scenic.ai.rag.retrieve.HybridRetriever;
import com.scenic.ai.rag.retrieve.RagRetriever;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
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
 * 检索质量评测（P4 建立，Wave 6 补全）。
 *
 * 评测口径：对每条问题，看**关键事实字符串是否出现在 top-K 召回切片中** ——
 * 这是可自动化、可复现的代理指标，与人工判断「资料够不够支撑回答」高度一致。
 *
 * Wave 6 修 B6：原先代码只算 Recall@K，**Hit@1 / MRR 是文档里手工算出来的**，
 * 导致指标无法自动回归（改了检索策略只能人肉核算）。现在三项指标全部在代码内计算：
 *   - Recall@K  —— 证据有没有被召回（宽松）
 *   - Hit@1     —— 排序质量（第一条就是答案）
 *   - MRR       —— 排序质量的平滑版本（答案排第 2 名仍有部分得分）
 * 同时新增**对抗样本检验**：语义相近但答案不同的问题对，用来验证语义缓存的
 * 相似度阈值是否安全（阈值太低会把「九龙灌浴多高」错命中成「阿育王柱多高」）。
 */
@Service
@Slf4j
public class RagEvalRunner {

    private static final String DATASET = "rag/eval-dataset.tsv";
    private static final int K = 5;

    /**
     * Wave 6 · 对抗样本：语义高度相近、但**正确答案完全不同**的问题对。
     * 它们不是为了考检索，而是为了验证「语义缓存阈值」不会把两者混为一谈。
     */
    private static final List<String[]> ADVERSARIAL_PAIRS = List.of(
            new String[]{"九龙灌浴的总高是多少米", "阿育王柱通高多少米"},
            new String[]{"灵山大佛通高多少米", "灵山大佛用了多少吨铜"},
            new String[]{"五智门的高度是多少", "灵山大照壁有多长"},
            new String[]{"佛足坛的一只足印有多长", "菩提大道长约多少米"},
            new String[]{"灵山梵宫的建筑面积是多少", "灵山梵宫的造价是多少"},
            new String[]{"灵山胜境成人门票多少钱", "灵山胜境半价票多少钱"},
            new String[]{"江南第一钟重多少吨", "九龙灌浴的太子佛像重多少吨"},
            new String[]{"五明桥是用什么材质建造的", "五智门的六根柱子代表什么"});

    private final HybridRetriever hybridRetriever;
    private final EmbeddingModel embeddingModel;

    public RagEvalRunner(HybridRetriever hybridRetriever, EmbeddingModel embeddingModel) {
        this.hybridRetriever = hybridRetriever;
        this.embeddingModel = embeddingModel;
    }

    public record Item(int no, String question, String expectedFact) {}

    /** 单条问题的评测结果：三种模式各自的首命中位次（-1 表示 top-K 内未命中） */
    public record Detail(int no, String question, String expectedFact, Map<String, Integer> firstHitRank) {}

    public record Report(int total,
                         int k,
                         Map<String, Double> recallAtK,
                         Map<String, Double> hitAt1,
                         Map<String, Double> mrr,
                         List<Detail> details) {}

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

        Map<String, HybridRetriever.Mode> modes = new LinkedHashMap<>();
        modes.put(HybridRetriever.VECTOR_KEY, HybridRetriever.Mode.VECTOR);
        modes.put(HybridRetriever.HYBRID_KEY, HybridRetriever.Mode.HYBRID);
        modes.put(HybridRetriever.HYBRID_RERANK_KEY, HybridRetriever.Mode.HYBRID_RERANK);

        // 每个模式收集全部问题的首命中位次（未命中记 0，便于直接算 MRR）
        Map<String, List<Integer>> ranksByMode = new LinkedHashMap<>();
        modes.keySet().forEach(key -> ranksByMode.put(key, new ArrayList<>()));

        List<Detail> details = new ArrayList<>();
        for (Item item : items) {
            Map<String, Integer> firstHitRank = new LinkedHashMap<>();
            for (Map.Entry<String, HybridRetriever.Mode> entry : modes.entrySet()) {
                int rank = -1;
                try {
                    List<RagRetriever.RetrievedChunk> chunks =
                            hybridRetriever.retrieve(item.question(), entry.getValue());
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
                ranksByMode.get(entry.getKey()).add(Math.max(rank, 0));
            }
            details.add(new Detail(item.no(), item.question(), item.expectedFact(), firstHitRank));
            log.info("评测 [{}/{}] {} -> {}", item.no(), items.size(), item.question(), firstHitRank);
        }

        Map<String, Double> recallAtK = new LinkedHashMap<>();
        Map<String, Double> hitAt1 = new LinkedHashMap<>();
        Map<String, Double> mrr = new LinkedHashMap<>();
        for (Map.Entry<String, List<Integer>> entry : ranksByMode.entrySet()) {
            List<Integer> ranks = entry.getValue();
            recallAtK.put(entry.getKey(), round(ratio(ranks, r -> r > 0)));
            hitAt1.put(entry.getKey(), round(ratio(ranks, r -> r == 1)));
            mrr.put(entry.getKey(), round(ranks.stream()
                    .mapToDouble(r -> r > 0 ? 1.0 / r : 0.0).average().orElse(0)));
        }

        log.info("评测完成：{} 条问题 | Recall@{}={} | Hit@1={} | MRR={}",
                items.size(), K, recallAtK, hitAt1, mrr);
        return new Report(items.size(), K, recallAtK, hitAt1, mrr, details);
    }

    /**
     * Wave 6 · 对抗样本检验。
     *
     * 对每一对「语义相近但答案不同」的问题，计算它们的向量余弦相似度：
     *   - 若相似度 ≥ 缓存阈值 → **会被误命中**，说明阈值过低，缓存会把 A 的答案给 B；
     *   - 若相似度 < 阈值     → 安全。
     *
     * 这个检查把「语义缓存的阈值该设多少」从拍脑袋变成了可验证的问题。
     */
    public Map<String, Object> adversarialReport(double cacheThreshold) {
        List<Map<String, Object>> pairs = new ArrayList<>();
        int risky = 0;
        double maxSimilarity = 0;

        for (String[] pair : ADVERSARIAL_PAIRS) {
            double similarity = 0;
            try {
                Embedding a = embeddingModel.embed(pair[0]).content();
                Embedding b = embeddingModel.embed(pair[1]).content();
                similarity = cosine(a.vector(), b.vector());
            } catch (Exception e) {
                log.warn("对抗样本相似度计算失败: {}", e.getMessage());
            }
            boolean wouldMisHit = similarity >= cacheThreshold;
            if (wouldMisHit) {
                risky++;
            }
            maxSimilarity = Math.max(maxSimilarity, similarity);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("a", pair[0]);
            item.put("b", pair[1]);
            item.put("similarity", round(similarity));
            item.put("wouldMisHit", wouldMisHit);
            pairs.add(item);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("cacheThreshold", cacheThreshold);
        report.put("pairs", ADVERSARIAL_PAIRS.size());
        report.put("riskyPairs", risky);
        report.put("maxSimilarity", round(maxSimilarity));
        report.put("conclusion", risky == 0
                ? "当前阈值安全：相似但答案不同的问题不会被误命中"
                : "存在误命中风险，建议提高 cache.semantic.min-score");
        report.put("details", pairs);
        log.info("[ADVERSARIAL] 对抗样本检验：{} 对，风险 {} 对，最大相似度 {}",
                ADVERSARIAL_PAIRS.size(), risky, round(maxSimilarity));
        return report;
    }

    // ───────────────────────── 工具方法 ─────────────────────────

    private double ratio(List<Integer> ranks, java.util.function.IntPredicate predicate) {
        if (ranks.isEmpty()) {
            return 0;
        }
        return (double) ranks.stream().filter(r -> predicate.test(r)).count() / ranks.size();
    }

    private double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }
}
