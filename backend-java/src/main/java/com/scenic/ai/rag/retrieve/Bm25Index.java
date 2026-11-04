package com.scenic.ai.rag.retrieve;

import com.scenic.ai.exception.RedisShortCircuitException;
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
 *
 * <h3>2026-10-29（D11）· 索引改为「不可变快照 + 单次发布」</h3>
 *
 * <p>原实现把 {@code chunks / postings / docLengths / avgDocLength / loaded} 五个字段分别标
 * {@code volatile}，{@link #reload()} 里连写五次。volatile 保证的是**单个字段**的可见性，
 * 不是字段之间的**原子性** —— 并发 {@code search()} 完全可能读到「新的 postings + 旧的 docLengths」，
 * 于是 {@code docLengths[docIdx]} 越界抛 {@code ArrayIndexOutOfBoundsException}，
 * 或者用错误的平均长度算出错误的 BM25 分数。
 *
 * <p>现在五个字段收进一个不可变 {@link Snapshot}，{@code reload()} 只在最后**写一次**
 * {@code volatile snapshot}。读侧先取一次引用、全程只用这一个快照：
 * 要么全是旧的、要么全是新的，不存在中间态。
 *
 * <p>顺带删掉了 {@code chunkAt(int index)}：它返回的是「某次快照里的下标」，调用方拿着它
 * 回调仓库时快照可能已经换了 —— 那是一个**天生会失效的句柄**。现在 {@link #search}
 * 直接把 {@link Chunk} 装进 {@link Hit} 返回，调用方拿到的就是内容本身，没有第二次查找。
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

    /** 索引快照：唯一的 volatile 状态，读写都只碰它 */
    private volatile Snapshot snapshot = Snapshot.EMPTY;

    public record Chunk(String id, String text, String docTitle) {}

    /** 一条命中：直接带内容，调用方不需要再回查索引 */
    public record Hit(Chunk chunk, double score) {}

    /**
     * 不可变索引快照。所有字段在构造后不再变化，因此可以安全地跨线程发布与读取。
     */
    private record Snapshot(List<Chunk> chunks,
                            Map<String, List<int[]>> postings,
                            int[] docLengths,
                            double avgDocLength,
                            boolean loaded) {
        static final Snapshot EMPTY = new Snapshot(List.of(), Map.of(), new int[0], 1.0, false);
    }

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
            RedisShortCircuitException.rethrowIf(e);   // 短路必须冒泡，不能被降级逻辑吞掉
            log.error("BM25 索引加载失败（关键词检索将不可用）: {}", e.getMessage());
            // 失败也走单次发布：清空快照，而不是逐个字段清零（那又会造出中间态）
            this.snapshot = Snapshot.EMPTY;
            return;
        }

        int[] docLengths = lengths.stream().mapToInt(Integer::intValue).toArray();
        double avgDocLength = docLengths.length == 0 ? 1.0
                : (double) lengths.stream().mapToInt(Integer::intValue).sum() / docLengths.length;

        // 唯一的发布点：构造完整快照后一次赋值
        this.snapshot = new Snapshot(
                List.copyOf(loadedChunks),
                Map.copyOf(newPostings),
                docLengths,
                avgDocLength,
                true);

        log.info("BM25 索引就绪：{} 条切片，{} 个词项，平均长度 {} 字",
                loadedChunks.size(), newPostings.size(), String.format("%.1f", avgDocLength));
    }

    public boolean isLoaded() {
        return snapshot.loaded();
    }

    public int size() {
        return snapshot.chunks().size();
    }

    /**
     * 按默认条数（{@code rag.top-k}）检索。
     *
     * <p>混合检索请用 {@link #search(String, int)} 并传入与向量路相同的候选池大小 ——
     * 见该方法的注释（D24）。
     */
    public List<Hit> search(String query) {
        return search(query, topK);
    }

    /**
     * 指定召回条数的检索，返回按分数降序的命中（含切片内容本身）。
     *
     * <p>全程只读一个快照引用：并发 reload 时本方法要么看旧索引、要么看新索引，
     * 不会出现「分数用新表算、长度用旧表查」的错配。
     *
     * <p>2026-10-29（D24）：为什么需要 {@code limit}。
     * 原实现把条数写死成 {@code rag.top-k}（默认 5），而 {@code HybridRetriever} 的向量路
     * 取的是 {@code rag.pool-size}（默认 20）。RRF 融合时两路候选数差 4 倍，
     * **关键词路的长尾命中永远进不了候选池** —— 一个只在 BM25 排第 12 位的切片，
     * 能力上被永久排除。而混合检索的价值恰恰在于「向量排不进去、关键词能排进去」的那些切片。
     */
    public List<Hit> search(String query, int limit) {
        Snapshot s = snapshot;
        if (!s.loaded()) {
            reload();
            s = snapshot;
        }
        if (s.chunks().isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        int effectiveLimit = Math.max(1, limit);

        Map<String, Integer> queryTf = new HashMap<>();
        for (String token : tokenize(query)) {
            queryTf.merge(token, 1, Integer::sum);
        }

        int totalDocs = s.chunks().size();
        int[] docLengths = s.docLengths();
        double avgDocLength = s.avgDocLength();
        List<Chunk> chunks = s.chunks();
        Map<Integer, Double> scores = new HashMap<>();

        for (String term : queryTf.keySet()) {
            List<int[]> posting = s.postings().get(term);
            if (posting == null) {
                continue;
            }
            int df = posting.size();
            double idf = Math.log(1 + (totalDocs - df + 0.5) / (df + 0.5));

            for (int[] entry : posting) {
                int docIdx = entry[0];
                int tf = entry[1];
                if (docIdx < 0 || docIdx >= docLengths.length) {
                    // 快照内部自洽，理论上不可达；留一道防线，避免脏数据把整个检索打挂
                    continue;
                }
                double dl = docLengths[docIdx];
                double denom = tf + K1 * (1 - B + B * dl / avgDocLength);
                double contribution = idf * (tf * (K1 + 1)) / denom;
                scores.merge(docIdx, contribution, Double::sum);
            }
        }

        return scores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(effectiveLimit)
                .map(e -> new Hit(chunks.get(e.getKey()), e.getValue()))
                .toList();
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
