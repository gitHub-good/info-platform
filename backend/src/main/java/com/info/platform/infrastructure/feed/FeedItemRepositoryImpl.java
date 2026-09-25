package com.info.platform.infrastructure.feed;

import com.info.platform.domain.feed.FeedItem;
import com.info.platform.domain.feed.FeedItemRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * {@link FeedItemRepository} 端口的 SQLite 实现（M13 T104，ADR-0039）。
 *
 * <p>批量落库走 {@link JdbcTemplate} {@code INSERT OR IGNORE}（MyBatis-Plus 无该语义，SubjectRepository 同惯例）——
 * idx_news_fp（全局指纹）与 idx_news_src_ext（源内 external_id，NULL 豁免）双索引同时兜底， 调度重入/补抓重拉/并发同稿均收敛，实插行数即返回值
 * （「应插 − 实插 = dup」对账口径）。 读路径默认 join info_source 排除软删源（历史行保留、默认流不可见）。 id DESC newest-first：id
 * 自增顺序即入库顺序，游标分页无需 OFFSET（LIMIT n）。
 */
@Repository
public class FeedItemRepositoryImpl implements FeedItemRepository {

    private static final String INSERT_IGNORE_SQL =
            """
            INSERT OR IGNORE INTO news_item
              (source_id, external_id, title, summary, url, author, published_at, fetched_at,
               fingerprint, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final RowMapper<FeedItem> ITEM_ROW = FeedItemRepositoryImpl::toItem;

    private final JdbcTemplate jdbcTemplate;

    public FeedItemRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int insertIgnoreBatch(List<FeedItem> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        int[] results =
                jdbcTemplate.batchUpdate(
                        INSERT_IGNORE_SQL,
                        new BatchPreparedStatementSetter() {
                            @Override
                            public void setValues(
                                    java.sql.PreparedStatement ps, int i) throws SQLException {
                                FeedItem item = items.get(i);
                                ps.setLong(1, item.sourceId());
                                ps.setString(2, item.externalId());
                                ps.setString(3, item.title());
                                ps.setString(4, item.summary());
                                ps.setString(5, item.url());
                                ps.setString(6, item.author());
                                ps.setString(7, item.publishedAt().toString());
                                ps.setString(8, item.fetchedAt().toString());
                                ps.setString(9, item.fingerprint());
                                ps.setInt(10, item.status());
                                ps.setString(11, now.toString());
                                ps.setString(12, now.toString());
                            }

                            @Override
                            public int getBatchSize() {
                                return items.size();
                            }
                        });
        int inserted = 0;
        for (int result : results) {
            // SQLite batch 返回每语句变更行数（IGNORE 为 0）；Statement.SUCCESS_NO_INFO 防御性按 1 计
            inserted += result == java.sql.Statement.SUCCESS_NO_INFO ? 1 : Math.max(0, result);
        }
        return inserted;
    }

    @Override
    public List<FeedItem> findLatest(Long sourceId, Long beforeId, int limit) {
        Query query = baseQuery();
        applySourceFilter(query, sourceId);
        if (beforeId != null && beforeId > 0) {
            query.append(" AND ni.id < ?", beforeId);
        }
        query.appendRaw(" ORDER BY ni.id DESC LIMIT " + limit);
        return jdbcTemplate.query(query.sql(), ITEM_ROW, query.args());
    }

    @Override
    public List<FeedItem> findPage(Long sourceId, int page, int size) {
        Query query = baseQuery();
        applySourceFilter(query, sourceId);
        // LIMIT/OFFSET 从简（ADR-0035：页码上限下实测毫秒级）
        query.appendRaw(" ORDER BY ni.id DESC LIMIT " + size + " OFFSET " + (page - 1) * size);
        return jdbcTemplate.query(query.sql(), ITEM_ROW, query.args());
    }

    @Override
    public long countByFilter(Long sourceId) {
        Query query =
                new Query(
                        """
                        SELECT COUNT(*)
                          FROM news_item ni
                          JOIN info_source s ON s.id = ni.source_id AND s.deleted = 0
                         WHERE 1=1
                        """);
        applySourceFilter(query, sourceId);
        Long count = jdbcTemplate.queryForObject(query.sql(), Long.class, query.args());
        return count == null ? 0L : count;
    }

    /** 列表查询基座（软删源 join + WHERE 1=1 起步，过滤片段顺序追加）。 */
    private static Query baseQuery() {
        return new Query(
                """
                SELECT ni.id, ni.source_id, ni.external_id, ni.title, ni.summary, ni.url, ni.author,
                       ni.published_at, ni.fetched_at, ni.fingerprint, ni.status,
                       ni.created_at, ni.updated_at
                  FROM news_item ni
                  JOIN info_source s ON s.id = ni.source_id AND s.deleted = 0
                 WHERE 1=1
                """);
    }

    private static void applySourceFilter(Query query, Long sourceId) {
        if (sourceId != null) {
            query.append(" AND ni.source_id = ?", sourceId);
        }
    }

    /** 参数化查询组装小件（SQL 片段 + 顺序参数；appendRaw 仅用于本类受控 int 字面量）。 */
    private static final class Query {
        private final StringBuilder sql;
        private final List<Object> args = new java.util.ArrayList<>();

        Query(String baseSql) {
            this.sql = new StringBuilder(baseSql);
        }

        void append(String fragment, Object arg) {
            sql.append(fragment);
            args.add(arg);
        }

        void appendRaw(String fragment) {
            sql.append(fragment);
        }

        String sql() {
            return sql.toString();
        }

        Object[] args() {
            return args.toArray();
        }
    }

    private static FeedItem toItem(ResultSet rs, int rowNum) throws SQLException {
        return new FeedItem(
                rs.getLong("id"),
                rs.getLong("source_id"),
                nullable(rs.getString("external_id")),
                rs.getString("title"),
                nullable(rs.getString("summary")),
                nullable(rs.getString("url")),
                nullable(rs.getString("author")),
                Instant.parse(rs.getString("published_at")),
                Instant.parse(rs.getString("fetched_at")),
                rs.getString("fingerprint"),
                rs.getInt("status"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }

    private static String nullable(String value) {
        return value == null ? null : value;
    }
}
