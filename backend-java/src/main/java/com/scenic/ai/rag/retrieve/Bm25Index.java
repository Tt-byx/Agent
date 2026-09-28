package com.scenic.ai.rag.retrieve;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用层 BM25 关键词检索（P4 混合检索的第二路）。
 *
 * 为什么不用 SQL 全文检索：
 *   - PostgreSQL 内置 tsvector 的分词器**不切中文**（无词边界），整句会变成一个 token；
 *   - 该镜像里没有 zhparser / pg_jieba 中文分词扩展；
 *   - pg_trgm 三元组相似度在这种"短查询 vs 长切片"场景下区分度极差（实测最高仅 0.10）。
 *
 * 所以这里用**中文字符二元组（bigram）+ 标准 BM25** 自己实现关键词检索，
 * 与 Lucene 处理 CJK 的思路一致。索引常驻内存，文档变更后需 {@link #reload()}。
 *
 * BM25 公式：score = Σ IDF(qi) * (tf * (k1+1)) / (tf + k1 * (1 - b + b * dl/avgdl))
 */
@Component
@Slf4j
public class Bm25Index {

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private final String jdbcUrl;
    private final String user;
    private final String password;
    private final String table;
    private final int topK;

    /** 内存索引 */
    private volatile List<Chunk> chunks = List.of();
    private volatile Map<String, List<int[]>> postings = Map.of();
    private volatile int[] docLengths = new int[0];
    private volatile double avgDocLength = 1.0;
    private volatile boolean loaded = false;

    public record Chunk(String id, String text, String docTitle) {}

    public record Scored(int index, double score) {}

    public Bm25Index(@Value("${pgvector.host}") String host,
                     @Value("${pgvector.port:5432}") Integer port,
                     @Value("${pgvector.database}") String database,
                     @Value("${pgvector.user}") String user,
                     @Value("${pgvector.password}") String password,
                     @Value("${pgvector.table:scenic_embeddings}") String table,
                     @Value("${rag.top-k:5}") int topK) {
        this.jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + database;
        this.user = user;
        this.password = password;
        this.table = table;
        this.topK = topK;
    }

    public synchronized void reload() {
        List<Chunk> loadedChunks = new ArrayList<>();
        Map<String, List<int[]>> newPostings = new HashMap<>();
        List<Integer> lengths = new ArrayList<>();

        String sql = "SELECT embedding_id, text, metadata->>'doc_title' FROM " + table;
        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password);
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            int docIdx = 0;
            while (rs.next()) {
                String text = rs.getString(2);
                if (text == null || text.isBlank()) {
                    continue;
                }
                String docTitle = rs.getString(3);
                loadedChunks.add(new Chunk(rs.getString(1), text, docTitle == null ? "" : docTitle));

                List<String> tokens = tokenize(text);
                lengths.add(tokens.size());

                Map<String, Integer> tf = new HashMap<>();
                for (String token : tokens) {
                    tf.merge(token, 1, Integer::sum);
                }
                for (Map.Entry<String, Integer> e : tf.entrySet()) {
                    newPostings.computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                            .add(new int[]{docIdx, e.getValue()});
                }
                docIdx++;
            }
        } catch (Exception e) {
            log.error("BM25 索引加载失败（关键词检索将不可用）: {}", e.getMessage());
            this.chunks = List.of();
            this.postings = Map.of();
            this.loaded = false;
            return;
        }

        this.chunks = loadedChunks;
        this.postings = newPostings;
        this.docLengths = lengths.stream().mapToInt(Integer::intValue).toArray();
        this.avgDocLength = docLengths.length == 0 ? 1.0
                : (double) lengths.stream().mapToInt(Integer::intValue).sum() / docLengths.length;
        this.loaded = true;
        log.info("BM25 索引就绪：{} 条切片，{} 个词项，平均长度 {} 字",
                chunks.size(), postings.size(), String.format("%.1f", avgDocLength));
    }

    public boolean isLoaded() {
        return loaded;
    }

    public int size() {
        return chunks.size();
    }

    /** BM25 检索，返回按分数降序的 (切片下标, 分数) */
    public List<Scored> search(String query) {
        if (!loaded) {
            reload();
        }
        if (chunks.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }

        Map<String, Integer> queryTf = new HashMap<>();
        for (String token : tokenize(query)) {
            queryTf.merge(token, 1, Integer::sum);
        }

        int totalDocs = chunks.size();
        Map<Integer, Double> scores = new HashMap<>();

        for (String term : queryTf.keySet()) {
            List<int[]> posting = postings.get(term);
            if (posting == null) {
                continue;
            }
            int df = posting.size();
            double idf = Math.log(1 + (totalDocs - df + 0.5) / (df + 0.5));

            for (int[] entry : posting) {
                int docIdx = entry[0];
                int tf = entry[1];
                double dl = docLengths[docIdx];
                double denom = tf + K1 * (1 - B + B * dl / avgDocLength);
                double contribution = idf * (tf * (K1 + 1)) / denom;
                scores.merge(docIdx, contribution, Double::sum);
            }
        }

        return scores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(topK)
                .map(e -> new Scored(e.getKey(), e.getValue()))
                .toList();
    }

    public Chunk chunkAt(int index) {
        return chunks.get(index);
    }

    /**
     * 中文分词：连续 CJK 字符按**二元组**切分，ASCII 字母数字按单词切分。
     * 例如「灵山大佛通高」→ 灵山 / 山大 / 大佛 / 佛通 / 通高
     */
    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null) {
            return tokens;
        }

        StringBuilder ascii = new StringBuilder();
        char prevCjk = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                if (ascii.length() > 0) {
                    tokens.add(ascii.toString().toLowerCase());
                    ascii.setLength(0);
                }
                if (prevCjk != 0) {
                    tokens.add(new String(new char[]{prevCjk, c}));
                }
                prevCjk = c;
            } else if (Character.isLetterOrDigit(c)) {
                prevCjk = 0;
                ascii.append(c);
            } else {
                if (ascii.length() > 0) {
                    tokens.add(ascii.toString().toLowerCase());
                    ascii.setLength(0);
                }
                prevCjk = 0;
            }
        }
        if (ascii.length() > 0) {
            tokens.add(ascii.toString().toLowerCase());
        }
        return tokens;
    }

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }
}
