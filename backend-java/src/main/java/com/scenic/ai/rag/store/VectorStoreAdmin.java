package com.scenic.ai.rag.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.PreDestroy;

/**
 * Wave 2 · 向量库运维通道（直连 PostgreSQL）。
 *
 * 为什么需要它：LangChain4j 的 {@code EmbeddingStore} 只暴露「写入 / 按过滤条件删除 / 相似度检索」，
 * 拿不到「某个文档当前有哪些向量 ID」、「某文档实际存了多少条」这类**对账与快照**信息。
 * 而 Wave 2 的两件事都要用到：
 *
 *   1. **先把新向量写进去、再按旧 ID 精确删除**（替代原先「先删后写」的不可逆流程，见 B2）；
 *   2. **对账**：比对业务表记录的 chunk 数与向量库实际条数，发现「删了没写」这类残缺状态。
 *
 * 连接采用独立的 Hikari 小池，且 **Hikari 是懒连接的** —— pgvector 没启动时不影响应用启动，
 * 与该模块其它组件（向量库 bean 标 @Lazy）的容错原则保持一致。
 */
@Component
@Slf4j
public class VectorStoreAdmin {

    private final JdbcTemplate jdbcTemplate;
    private final HikariDataSource dataSource;
    private final String table;

    public VectorStoreAdmin(@Value("${pgvector.host}") String host,
                            @Value("${pgvector.port:5432}") int port,
                            @Value("${pgvector.database}") String database,
                            @Value("${pgvector.user}") String user,
                            @Value("${pgvector.password}") String password,
                            @Value("${pgvector.table:scenic_embeddings}") String table) {
        this.table = table;

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/" + database);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(3000);
        config.setPoolName("pgvector-admin");
        this.dataSource = new HikariDataSource(config);
        this.jdbcTemplate = new JdbcTemplate(dataSource);

        log.info("[VECTOR-ADMIN] 向量库运维通道就绪：{}:{}/{}（table={}）", host, port, database, table);
    }

    /**
     * 某文档当前的向量 ID 快照。
     * 用于「先写新后删旧」：写入前先记下旧 ID，写入成功后按这些 ID 精确删除，
     * 这样新旧数据不会互相误删。
     */
    public List<String> findEmbeddingIds(long docId) {
        try {
            List<String> ids = jdbcTemplate.queryForList(
                    "SELECT embedding_id::text FROM " + table + " WHERE metadata->>'doc_id' = ?",
                    String.class, String.valueOf(docId));
            return new ArrayList<>(ids);
        } catch (Exception e) {
            log.warn("[VECTOR-ADMIN] 查询文档 {} 的向量 ID 失败（按「无旧数据」处理）: {}", docId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 按 doc_id 统计向量条数 —— 对账用 */
    public Map<Long, Long> countByDocId() {
        Map<Long, Long> result = new HashMap<>();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT metadata->>'doc_id' AS doc_id, count(*) AS cnt FROM " + table
                            + " WHERE metadata->>'doc_id' IS NOT NULL GROUP BY 1");
            for (Map<String, Object> row : rows) {
                try {
                    result.put(Long.valueOf(String.valueOf(row.get("doc_id"))),
                            ((Number) row.get("cnt")).longValue());
                } catch (NumberFormatException ignored) {
                    // doc_id 非数字（历史脏数据）跳过
                }
            }
        } catch (Exception e) {
            log.warn("[VECTOR-ADMIN] 按文档统计向量条数失败: {}", e.getMessage());
            throw new IllegalStateException("向量库不可用：" + e.getMessage(), e);
        }
        return result;
    }

    /** 全表条数 */
    public long countAll() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }

    /** 向量库是否可达（对账/健康检查用） */
    public boolean isReachable() {
        try {
            countAll();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @PreDestroy
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
