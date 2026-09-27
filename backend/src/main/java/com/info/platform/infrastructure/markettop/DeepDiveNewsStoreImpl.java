package com.info.platform.infrastructure.markettop;

import com.info.platform.application.markettop.DeepDiveNewsStore;
import com.info.platform.domain.markettop.DeepDiveInput.NewsFact;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * {@link DeepDiveNewsStore} 端口的 SQLite 实现（M21 T182）：matched_subjects 为 JSON
 * 数组（[{"code","name"...}]）—— JSON 引号定界 LIKE（HeatSnapshotRepositoryImpl 同款惯例：代码值域 [A-Z0-9]
 * 无引号/百分号，无转义面）。
 */
@Repository
public class DeepDiveNewsStoreImpl implements DeepDiveNewsStore {

    private static final String RELATED_NEWS_SQL =
            """
            SELECT ni.id, ni.title, ni.published_at, src.name AS source_name
              FROM news_analysis na
              JOIN news_item ni ON ni.id = na.news_id
              LEFT JOIN info_source src ON src.id = ni.source_id
             WHERE na.l1_status = 'DONE'
               AND na.matched_subjects LIKE '%"' || ? || '"%'
               AND ni.published_at >= ?
             ORDER BY ni.published_at DESC, ni.id DESC
             LIMIT ?
            """;

    private static final String INDUSTRY_NEWS_SQL =
            """
            SELECT ni.id, ni.title, ni.published_at, src.name AS source_name
              FROM news_analysis na
              JOIN news_item ni ON ni.id = na.news_id
              LEFT JOIN info_source src ON src.id = ni.source_id
             WHERE na.l1_status = 'DONE'
               AND na.main_category = ?
               AND ni.published_at >= ?
             ORDER BY ni.published_at DESC, ni.id DESC
             LIMIT ?
            """;

    private static final RowMapper<NewsFact> NEWS_ROW =
            new RowMapper<>() {
                @Override
                public NewsFact mapRow(ResultSet rs, int rowNum) throws SQLException {
                    return new NewsFact(
                            rs.getLong("id"),
                            rs.getString("title"),
                            rs.getString("published_at"),
                            rs.getString("source_name"));
                }
            };

    private final JdbcTemplate jdbcTemplate;

    public DeepDiveNewsStoreImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<NewsFact> findRelatedNews(String subjectCode, String fromIso, int cap) {
        return jdbcTemplate.query(
                RELATED_NEWS_SQL,
                NEWS_ROW,
                subjectCode,
                fromIso == null ? Instant.EPOCH.toString() : fromIso,
                cap);
    }

    @Override
    public List<NewsFact> findIndustryNews(String swIndustry, String fromIso, int cap) {
        return jdbcTemplate.query(
                INDUSTRY_NEWS_SQL,
                NEWS_ROW,
                swIndustry,
                fromIso == null ? Instant.EPOCH.toString() : fromIso,
                cap);
    }
}
