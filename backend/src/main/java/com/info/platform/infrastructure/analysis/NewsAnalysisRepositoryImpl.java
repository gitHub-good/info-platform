package com.info.platform.infrastructure.analysis;

import com.info.platform.domain.analysis.L2Status;
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
                            nullable(rs.getString("external_id")),
                            rs.getString("title"),
                            nullable(rs.getString("summary")),
                            nullable(rs.getString("source_category")),
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
            String createdBeforeIso, List<Long> excludeSourceIds, int limit) {
        // LEFT JOIN 取「入库已过缓冲期仍无 analysis 行」条目；published_at,id 升序 = 近重复主条判定的时间序前提；
        // aiExclusion=ALL 源不建行（T125，REQ 拍板五-1）；external_id/source_category = T130 序列豁免与
        // T131 express 预筛分输入
        StringBuilder sql =
                new StringBuilder(
                        """
                        SELECT ni.id AS news_id, ni.source_id, ni.external_id, ni.title, ni.summary,
                               s.category AS source_category, ni.published_at, ni.created_at
                          FROM news_item ni
                          JOIN info_source s ON s.id = ni.source_id AND s.deleted = 0
                          LEFT JOIN news_analysis na ON na.news_id = ni.id
                         WHERE na.id IS NULL AND ni.created_at <= ?
                        """);
        List<Object> args = new java.util.ArrayList<>();
        args.add(createdBeforeIso);
        appendSourceExclusion(sql, args, excludeSourceIds, "ni.source_id");
        sql.append(" ORDER BY ni.published_at ASC, ni.id ASC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), NEWS_CANDIDATE_ROW, args.toArray());
    }

    @Override
    public List<NewsAnalysisRepository.NewsCandidate> findPassPoolSince(
            String publishedSinceIso, int limit) {
        String sql =
                """
                SELECT ni.id AS news_id, ni.source_id, ni.external_id, ni.title, ni.summary,
                       s.category AS source_category, ni.published_at, ni.created_at
                  FROM news_analysis na
                  JOIN news_item ni ON ni.id = na.news_id
                  JOIN info_source s ON s.id = ni.source_id
                 WHERE na.l0_result = 'PASS' AND ni.published_at >= ?
                 ORDER BY ni.published_at ASC, ni.id ASC
                 LIMIT ?
                """;
        return jdbcTemplate.query(sql, NEWS_CANDIDATE_ROW, publishedSinceIso, limit);
    }

    @Override
    public List<NewsAnalysisRepository.ClassificationCandidate> findPendingForL1(
            String createdSinceIso, int maxAttempts, List<Long> excludeSourceIds, int limit) {
        // 24h 补跑窗口（裁决 5）+ attempts 守卫 + PASS 过滤（NOISE/NEAR_DUP 不进 L1，方案 §6 红线断言面）；
        // aiExclusion=ALL 源条目不归类（T125——防御性兜底，正常情况 L0 已不建行）
        StringBuilder sql =
                new StringBuilder(
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
                        """);
        List<Object> args = new java.util.ArrayList<>();
        args.add(maxAttempts);
        args.add(createdSinceIso);
        appendSourceExclusion(sql, args, excludeSourceIds, "ni.source_id");
        sql.append(" ORDER BY na.news_id ASC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), CLASSIFY_ROW, args.toArray());
    }

    /** 源排除谓词拼接（id 清单绑定参数化，无注入面；空表不加谓词）。 */
    private static void appendSourceExclusion(
            StringBuilder sql, List<Object> args, List<Long> excludeSourceIds, String column) {
        if (excludeSourceIds == null || excludeSourceIds.isEmpty()) {
            return;
        }
        sql.append(" AND ")
                .append(column)
                .append(" NOT IN (")
                .append(
                        String.join(
                                ",", java.util.Collections.nCopies(excludeSourceIds.size(), "?")))
                .append(")");
        args.addAll(excludeSourceIds);
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

    // —— L2（M15 T122）——

    private static final String FIND_L2_CANDIDATES_SQL =
            """
            SELECT na.news_id, ni.title, ni.summary, s.name AS source_name, s.category AS source_category,
                   na.main_category, ni.published_at, na.created_at, na.l2_status, na.matched_subjects
              FROM news_analysis na
              JOIN news_item ni ON ni.id = na.news_id
              JOIN info_source s ON s.id = ni.source_id
             WHERE na.l0_result = 'PASS'
               AND na.l1_status = 'DONE'
               AND (
                     (na.l2_status IN ('SKIP', 'SELECTED', 'FAILED') AND na.created_at >= ?
                      AND (na.l2_status != 'FAILED' OR na.l2_attempts < ?))
                     OR (na.l2_status = 'DEFERRED' AND na.created_at >= ?)
                   )
            """;

    private static final String UPDATE_IMPORTANCE_SQL =
            "UPDATE news_analysis SET importance_score = ?, updated_at = ? WHERE news_id = ?";

    private static final String MARK_L2_SELECTED_SQL =
            """
            UPDATE news_analysis SET l2_status = 'SELECTED', updated_at = ?
             WHERE news_id = ? AND l2_status IN ('SKIP', 'SELECTED', 'DEFERRED', 'FAILED')
            """;

    private static final String MARK_L2_DEFERRED_SQL =
            """
            UPDATE news_analysis SET l2_status = 'DEFERRED', updated_at = ?
             WHERE news_id = ? AND l2_status IN ('SKIP', 'SELECTED', 'FAILED')
            """;

    private static final String APPLY_L2_SQL =
            """
            UPDATE news_analysis SET l2_status = ?, updated_at = ?
             WHERE news_id = ? AND l2_status IN ('SKIP', 'SELECTED', 'DEFERRED', 'FAILED')
            """;

    private static final String MARK_L2_FAILED_SQL =
            """
            UPDATE news_analysis SET l2_status = 'FAILED', l2_attempts = l2_attempts + 1, updated_at = ?
             WHERE news_id = ?
            """;

    private static final RowMapper<NewsAnalysisRepository.L2Candidate> L2_CANDIDATE_ROW =
            (rs, rowNum) ->
                    new NewsAnalysisRepository.L2Candidate(
                            rs.getLong("news_id"),
                            rs.getString("title"),
                            nullable(rs.getString("summary")),
                            rs.getString("source_name"),
                            rs.getString("source_category"),
                            rs.getString("main_category"),
                            Instant.parse(rs.getString("published_at")),
                            Instant.parse(rs.getString("created_at")),
                            L2Status.fromName(rs.getString("l2_status")),
                            nullable(rs.getString("matched_subjects")));

    @Override
    public List<NewsAnalysisRepository.L2Candidate> findL2Candidates(
            String todayStartIso,
            String backfillSinceIso,
            int maxAttempts,
            List<Long> excludeSourceIds,
            int limit) {
        // 当日新账（SKIP/SELECTED/FAILED[attempts 未满]）+ 24h 窗口 DEFERRED 旧账；排除 aiExclusion=L2 源（T125 承载）
        StringBuilder sql = new StringBuilder(FIND_L2_CANDIDATES_SQL);
        List<Object> args = new java.util.ArrayList<>();
        args.add(todayStartIso);
        args.add(maxAttempts);
        args.add(backfillSinceIso);
        if (excludeSourceIds != null && !excludeSourceIds.isEmpty()) {
            sql.append(" AND ni.source_id NOT IN (")
                    .append(
                            String.join(
                                    ",",
                                    java.util.Collections.nCopies(excludeSourceIds.size(), "?")))
                    .append(")");
            args.addAll(excludeSourceIds);
        }
        sql.append(" ORDER BY na.news_id ASC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), L2_CANDIDATE_ROW, args.toArray());
    }

    @Override
    public int updateImportanceScores(Map<Long, Double> scoresByNewsId) {
        if (scoresByNewsId == null || scoresByNewsId.isEmpty()) {
            return 0;
        }
        String now = Instant.now().toString();
        List<Object[]> batch =
                scoresByNewsId.entrySet().stream()
                        .map(entry -> new Object[] {entry.getValue(), now, entry.getKey()})
                        .toList();
        int[] results = jdbcTemplate.batchUpdate(UPDATE_IMPORTANCE_SQL, batch);
        int updated = 0;
        for (int result : results) {
            updated += result == java.sql.Statement.SUCCESS_NO_INFO ? 1 : Math.max(0, result);
        }
        return updated;
    }

    @Override
    public int markL2Selected(List<Long> newsIds) {
        return updateByNewsIds(MARK_L2_SELECTED_SQL, newsIds);
    }

    @Override
    public int markL2Deferred(List<Long> newsIds) {
        return updateByNewsIds(MARK_L2_DEFERRED_SQL, newsIds);
    }

    @Override
    public int applyL2Result(NewsAnalysisRepository.L2Write write) {
        return jdbcTemplate.update(
                APPLY_L2_SQL, write.status().name(), Instant.now().toString(), write.newsId());
    }

    @Override
    public int markL2Failed(List<Long> newsIds) {
        return updateByNewsIds(MARK_L2_FAILED_SQL, newsIds);
    }

    private int updateByNewsIds(String sql, List<Long> newsIds) {
        if (newsIds == null || newsIds.isEmpty()) {
            return 0;
        }
        String now = Instant.now().toString();
        int updated = 0;
        for (Long newsId : newsIds) {
            updated += jdbcTemplate.update(sql, now, newsId);
        }
        return updated;
    }

    @Override
    public long countL1DoneSince(String createdSinceIso) {
        Long count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM news_analysis WHERE l1_status = 'DONE' AND created_at >= ?",
                        Long.class,
                        createdSinceIso);
        return count == null ? 0 : count;
    }

    @Override
    public long countL2ProcessedSince(String sinceIso) {
        // 配额消耗口径：终态行（updated_at 当日推进）；DEFERRED 未消耗（债务仍在）
        Long count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM news_analysis WHERE l2_status IN ('EXTRACTED', 'NO_EVENT',"
                                + " 'FAILED') AND updated_at >= ?",
                        Long.class,
                        sinceIso);
        return count == null ? 0 : count;
    }

    @Override
    public Map<String, Long> countL2ByStatusSince(String createdSinceIso) {
        return countByColumnValue("l2_status", createdSinceIso);
    }

    @Override
    public Map<String, Long> countL0ByResultSince(String createdSinceIso) {
        return countByColumnValue("l0_result", createdSinceIso);
    }

    @Override
    public Map<String, Long> countL1ByStatusSince(String createdSinceIso) {
        return countByColumnValue("l1_status", createdSinceIso);
    }

    @Override
    public NewsAnalysisRepository.L1SlaStats countL1SlaSince(String createdSinceIso) {
        // T+30min 口径（方案 §4.10）：DONE 行为分母，classified_at − news_item.fetched_at ≤ 30min 为分子；
        // SQLite julianday 折算分钟差（ISO-8601 文本字典序/时间序一致，双端同源）
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) AS done,
                       SUM(CASE WHEN (julianday(na.classified_at) - julianday(ni.fetched_at)) * 1440 <= 30
                                 THEN 1 ELSE 0 END) AS within30
                  FROM news_analysis na
                  JOIN news_item ni ON ni.id = na.news_id
                 WHERE na.l1_status = 'DONE' AND na.created_at >= ?
                """,
                (rs, rowNum) ->
                        new NewsAnalysisRepository.L1SlaStats(
                                rs.getLong("done"), rs.getLong("within30")), // SUM 空集 NULL → 0
                createdSinceIso);
    }

    @Override
    public long countNewsItemsCreatedSince(String createdSinceIso) {
        Long count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM news_item WHERE created_at >= ?",
                        Long.class,
                        createdSinceIso);
        return count == null ? 0 : count;
    }

    private static final String FAIL_ORPHAN_EXTRACTED_SQL =
            """
            UPDATE news_analysis
               SET l2_status = 'FAILED', updated_at = ?
             WHERE l2_status = 'EXTRACTED'
               AND news_id NOT IN (SELECT news_id FROM event_item)
            """;

    @Override
    public int failOrphanExtractedRows() {
        // OBS-04（M16 T130 方案 §4.2）：孤儿 EXTRACTED 行回置 FAILED（attempts 留痕不动；
        // 未满上限的行重进 L2 候选重扫窗口自然补提取）
        return jdbcTemplate.update(FAIL_ORPHAN_EXTRACTED_SQL, Instant.now().toString());
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
