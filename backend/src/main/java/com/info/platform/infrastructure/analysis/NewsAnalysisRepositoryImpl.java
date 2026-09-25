package com.info.platform.infrastructure.analysis;

import com.info.platform.domain.analysis.NewsAnalysis;
import com.info.platform.domain.analysis.NewsAnalysisRepository;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * {@link NewsAnalysisRepository} 端口的 SQLite 实现（M15 T120，ADR-0046 裁决 1）。
 *
 * <p>建行批量 {@code INSERT OR IGNORE}（UNIQUE(news_id) 幂等，批窗口重入收敛）；L1 结果条件 UPDATE（{@code WHERE
 * l1_status IN ('PENDING','FAILED')}——已 DONE 行不重复归类，幂等红线）。候选/池查询 join {@code news_item}（独立表代价 = 一次
 * join， 日窗行数量级毫秒级）并排除软删源。
 */
@Repository
public class NewsAnalysisRepositoryImpl implements NewsAnalysisRepository {

    private static final String INSERT_IGNORE_SQL =
            """
            INSERT OR IGNORE INTO news_analysis
              (news_id, l0_result, near_dup_of, l0_detail, l1_status, l2_status, created_at, updated_at)
            VALUES (?, ?, ?, ?, 'PENDING', 'SKIP', ?, ?)
            """;

    private static final String APPLY_L1_SQL =
            """
            UPDATE news_analysis
               SET main_category = ?, raw_main = ?, sub_industry = ?, confidence = ?, low_confidence = ?,
                   matched_subjects = ?, l1_prompt_version = ?, classified_at = ?,
                   l1_status = 'DONE', updated_at = ?
             WHERE news_id = ? AND l1_status IN ('PENDING', 'FAILED')
            """;

    private static final String MARK_FAILED_SQL =
            """
            UPDATE news_analysis
               SET l1_status = 'FAILED', l1_attempts = l1_attempts + 1, updated_at = ?
             WHERE news_id = ?
            """;

    private static final RowMapper<NewsAnalysisRepository.NewsCandidate> NEWS_CANDIDATE_ROW =
            (rs, rowNum) ->
                    new NewsAnalysisRepository.NewsCandidate(
                            rs.getLong("news_id"),
                            rs.getLong("source_id"),
                            rs.getString("title"),
                            nullable(rs.getString("summary")),
                            Instant.parse(rs.getString("published_at")),
                            Instant.parse(rs.getString("created_at")));

    private static final RowMapper<NewsAnalysisRepository.ClassificationCandidate> CLASSIFY_ROW =
            (rs, rowNum) ->
                    new NewsAnalysisRepository.ClassificationCandidate(
                            rs.getLong("news_id"),
                            rs.getString("title"),
                            nullable(rs.getString("summary")),
                            rs.getString("source_name"),
                            Instant.parse(rs.getString("published_at")),
                            Instant.parse(rs.getString("fetched_at")));

    private final JdbcTemplate jdbcTemplate;

    public NewsAnalysisRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int insertIgnoreBatch(List<NewsAnalysis> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        int[] results =
                jdbcTemplate.batchUpdate(
                        INSERT_IGNORE_SQL,
                        new BatchPreparedStatementSetter() {
                            @Override
                            public void setValues(PreparedStatement ps, int i) throws SQLException {
                                NewsAnalysis row = rows.get(i);
                                ps.setLong(1, row.getNewsId());
                                ps.setString(2, row.getL0Result().name());
                                if (row.getNearDupOf() == null) {
                                    ps.setNull(3, java.sql.Types.INTEGER);
                                } else {
                                    ps.setLong(3, row.getNearDupOf());
                                }
                                ps.setString(4, row.getL0Detail());
                                ps.setString(5, now.toString());
                                ps.setString(6, now.toString());
                            }

                            @Override
                            public int getBatchSize() {
                                return rows.size();
                            }
                        });
        int inserted = 0;
        for (int result : results) {
            // SQLite batch 返回每语句变更行数（IGNORE 为 0）；SUCCESS_NO_INFO 防御性按 1 计（FeedItemRepositoryImpl
            // 同款）
            inserted += result == java.sql.Statement.SUCCESS_NO_INFO ? 1 : Math.max(0, result);
        }
        return inserted;
    }

    @Override
    public List<NewsAnalysisRepository.NewsCandidate> findUnanalyzed(
            String createdBeforeIso, int limit) {
        // LEFT JOIN 取「入库已过缓冲期仍无 analysis 行」条目；published_at,id 升序 = 近重复主条判定的时间序前提
        String sql =
                """
                SELECT ni.id AS news_id, ni.source_id, ni.title, ni.summary, ni.published_at, ni.created_at
                  FROM news_item ni
                  JOIN info_source s ON s.id = ni.source_id AND s.deleted = 0
                  LEFT JOIN news_analysis na ON na.news_id = ni.id
                 WHERE na.id IS NULL AND ni.created_at <= ?
                 ORDER BY ni.published_at ASC, ni.id ASC
                 LIMIT ?
                """;
        return jdbcTemplate.query(sql, NEWS_CANDIDATE_ROW, createdBeforeIso, limit);
    }

    @Override
    public List<NewsAnalysisRepository.NewsCandidate> findPassPoolSince(
            String publishedSinceIso, int limit) {
        String sql =
                """
                SELECT ni.id AS news_id, ni.source_id, ni.title, ni.summary, ni.published_at, ni.created_at
                  FROM news_analysis na
                  JOIN news_item ni ON ni.id = na.news_id
                 WHERE na.l0_result = 'PASS' AND ni.published_at >= ?
                 ORDER BY ni.published_at ASC, ni.id ASC
                 LIMIT ?
                """;
        return jdbcTemplate.query(sql, NEWS_CANDIDATE_ROW, publishedSinceIso, limit);
    }

    @Override
    public List<NewsAnalysisRepository.ClassificationCandidate> findPendingForL1(
            String createdSinceIso, int maxAttempts, int limit) {
        // 24h 补跑窗口（裁决 5）+ attempts 守卫 + PASS 过滤（NOISE/NEAR_DUP 不进 L1，方案 §6 红线断言面）
        String sql =
                """
                SELECT na.news_id, ni.title, ni.summary, s.name AS source_name,
                       ni.published_at, ni.fetched_at
                  FROM news_analysis na
                  JOIN news_item ni ON ni.id = na.news_id
                  JOIN info_source s ON s.id = ni.source_id
                 WHERE na.l0_result = 'PASS'
                   AND na.l1_status IN ('PENDING', 'FAILED')
                   AND na.l1_attempts < ?
                   AND na.created_at >= ?
                 ORDER BY na.news_id ASC
                 LIMIT ?
                """;
        return jdbcTemplate.query(sql, CLASSIFY_ROW, maxAttempts, createdSinceIso, limit);
    }

    @Override
    public int applyL1Result(L1Write write) {
        Instant now = Instant.now();
        return jdbcTemplate.update(
                APPLY_L1_SQL,
                write.mainCategory(),
                write.rawMain(),
                write.subIndustry(),
                write.confidence(),
                write.lowConfidence() ? 1 : 0,
                write.matchedSubjects(),
                write.promptVersion(),
                write.classifiedAt().toString(),
                now.toString(),
                write.newsId());
    }

    @Override
    public int markL1Failed(List<Long> newsIds) {
        if (newsIds == null || newsIds.isEmpty()) {
            return 0;
        }
        String now = Instant.now().toString();
        int updated = 0;
        for (Long newsId : newsIds) {
            updated += jdbcTemplate.update(MARK_FAILED_SQL, now, newsId);
        }
        return updated;
    }

    @Override
    public Map<String, Long> countL0ByResultSince(String createdSinceIso) {
        return countByColumnValue("l0_result", createdSinceIso);
    }

    @Override
    public Map<String, Long> countL1ByStatusSince(String createdSinceIso) {
        return countByColumnValue("l1_status", createdSinceIso);
    }

    private Map<String, Long> countByColumnValue(String column, String createdSinceIso) {
        return jdbcTemplate.query(
                "SELECT "
                        + column
                        + " AS state, COUNT(*) AS total FROM news_analysis WHERE created_at >= ?"
                        + " GROUP BY "
                        + column,
                (rs) -> {
                    Map<String, Long> counts = new HashMap<>();
                    while (rs.next()) {
                        counts.put(rs.getString("state"), rs.getLong("total"));
                    }
                    return counts;
                },
                createdSinceIso);
    }

    private static String nullable(String value) {
        return value == null ? null : value;
    }
}
