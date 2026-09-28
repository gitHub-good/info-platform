package com.info.platform.infrastructure.mainline;

import com.info.platform.domain.mainline.MainlineRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link MainlineRepository} 端口的 SQLite 实现（M27 T243，V35 表②③）：追加式版本化写（batch + ranks 同一事务）；读取按「日期 +
 * 最大 version」（idx_iml_date_ver 命中）。输入投影：日报 heat_top 近窗行 / 事件密度 json_each 加权 SQL（对账可复算）。
 */
@Repository
public class MainlineRepositoryImpl implements MainlineRepository {

    private static final String INSERT_RANK_SQL =
            """
            INSERT INTO industry_mainline
              (rank_date, version, rank_no, industry, main_score, dim_detail, persistent_days,
               heat_rank, divergence, leaders, basis, computed_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_BATCH_SQL =
            """
            INSERT INTO industry_mainline_batch
              (rank_date, version, trigger_source, snapshot_date, funnel_stats, degraded,
               degraded_reason, basis, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String RANK_COLUMNS =
            """
            rank_date, version, rank_no, industry, main_score, dim_detail, persistent_days,
            heat_rank, divergence, leaders, basis, computed_at
            """;

    private static final RowMapper<MainlineRankRow> RANK_ROW =
            (rs, rowNum) ->
                    new MainlineRankRow(
                            rs.getString("rank_date"),
                            rs.getInt("version"),
                            rs.getInt("rank_no"),
                            rs.getString("industry"),
                            rs.getDouble("main_score"),
                            rs.getString("dim_detail"),
                            rs.getInt("persistent_days"),
                            (Integer) rs.getObject("heat_rank"),
                            rs.getString("divergence"),
                            rs.getString("leaders"),
                            rs.getString("basis"),
                            rs.getString("computed_at"));

    private static final RowMapper<MainlineBatchRow> BATCH_ROW =
            (rs, rowNum) ->
                    new MainlineBatchRow(
                            rs.getString("rank_date"),
                            rs.getInt("version"),
                            rs.getString("trigger_source"),
                            rs.getString("snapshot_date"),
                            rs.getString("funnel_stats"),
                            rs.getInt("degraded") == 1,
                            rs.getString("degraded_reason"),
                            rs.getString("basis"),
                            rs.getString("created_at"));

    private static final RowMapper<HeatTopDay> HEAT_TOP_ROW =
            (rs, rowNum) -> new HeatTopDay(rs.getString("report_date"), rs.getString("heat_top"));

    private static final RowMapper<EventWeightRow> EVENT_WEIGHT_ROW =
            (rs, rowNum) -> new EventWeightRow(rs.getString("industry"), rs.getDouble("weighted"));

    private final JdbcTemplate jdbcTemplate;

    private final TransactionTemplate transactionTemplate;

    public MainlineRepositoryImpl(
            JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public int maxVersion(String rankDate) {
        Integer max =
                jdbcTemplate.queryForObject(
                        "SELECT MAX(version) FROM industry_mainline WHERE rank_date = ?",
                        Integer.class,
                        rankDate);
        return max == null ? 0 : max;
    }

    @Override
    public int insertVersion(MainlineBatchRow batch, List<MainlineRankRow> ranks) {
        Integer inserted =
                transactionTemplate.execute(
                        status -> {
                            jdbcTemplate.update(
                                    con -> {
                                        var ps = con.prepareStatement(INSERT_BATCH_SQL);
                                        ps.setString(1, batch.rankDate());
                                        ps.setInt(2, batch.version());
                                        ps.setString(3, batch.triggerSource());
                                        ps.setString(4, batch.snapshotDate());
                                        ps.setString(5, batch.funnelStatsJson());
                                        ps.setInt(6, batch.degraded() ? 1 : 0);
                                        ps.setString(7, batch.degradedReason());
                                        ps.setString(8, batch.basis());
                                        ps.setString(9, batch.createdAt());
                                        ps.setString(10, batch.createdAt());
                                        return ps;
                                    });
                            for (MainlineRankRow rank : ranks) {
                                jdbcTemplate.update(
                                        con -> {
                                            var ps = con.prepareStatement(INSERT_RANK_SQL);
                                            ps.setString(1, rank.rankDate());
                                            ps.setInt(2, rank.version());
                                            ps.setInt(3, rank.rankNo());
                                            ps.setString(4, rank.industry());
                                            ps.setDouble(5, rank.mainScore());
                                            ps.setString(6, rank.dimDetailJson());
                                            ps.setInt(7, rank.persistentDays());
                                            if (rank.heatRank() == null) {
                                                ps.setNull(8, java.sql.Types.INTEGER);
                                            } else {
                                                ps.setInt(8, rank.heatRank());
                                            }
                                            ps.setString(9, rank.divergence());
                                            ps.setString(10, rank.leadersJson());
                                            ps.setString(11, rank.basis());
                                            ps.setString(12, rank.computedAt());
                                            ps.setString(13, rank.computedAt());
                                            ps.setString(14, rank.computedAt());
                                            return ps;
                                        });
                            }
                            return ranks.size();
                        });
        return inserted == null ? 0 : inserted;
    }

    @Override
    public Optional<MainlineVersion> find(String rankDate, int version) {
        List<MainlineBatchRow> batches =
                jdbcTemplate.query(
                        "SELECT rank_date, version, trigger_source, snapshot_date, funnel_stats,"
                                + " degraded, degraded_reason, basis, created_at FROM"
                                + " industry_mainline_batch WHERE rank_date = ? AND version = ?",
                        BATCH_ROW,
                        rankDate,
                        version);
        if (batches.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new MainlineVersion(batches.get(0), rankRows(rankDate, version)));
    }

    @Override
    public Optional<MainlineVersion> findLatest(String rankDate) {
        Integer max = maxVersion(rankDate);
        return max <= 0 ? Optional.empty() : find(rankDate, max);
    }

    @Override
    public Optional<MainlineVersion> findLatestAnyDate() {
        List<String> dates = listRankDates(1);
        if (dates.isEmpty()) {
            return Optional.empty();
        }
        return findLatest(dates.get(0));
    }

    @Override
    public List<String> listRankDates(int limit) {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT rank_date FROM industry_mainline ORDER BY rank_date DESC LIMIT ?",
                String.class,
                limit);
    }

    @Override
    public List<HeatTopDay> findRecentHeatTop(int days) {
        return jdbcTemplate.query(
                "SELECT report_date, heat_top FROM industry_daily_report"
                        + " WHERE report_date >= date('now', ?) ORDER BY report_date DESC",
                HEAT_TOP_ROW,
                "-" + days + " day");
    }

    @Override
    public List<EventWeightRow> sumEventWeightByIndustry(String fromDate, String toDate) {
        return jdbcTemplate.query(
                """
                SELECT json_each.value AS industry, SUM(CASE e.importance
                       WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 1 ELSE 0 END) AS weighted
                FROM event_item e, json_each(e.affected_industries)
                WHERE e.event_date >= ? AND e.event_date <= ?
                GROUP BY json_each.value
                ORDER BY industry
                """,
                EVENT_WEIGHT_ROW,
                fromDate,
                toDate);
    }

    @Override
    public List<MemberRow> findActiveMembers() {
        return jdbcTemplate.query(
                "SELECT id, subject_code, name, industry FROM subject_master"
                        + " WHERE market = 'A_SHARE' AND status = 1 AND industry IS NOT NULL"
                        + " ORDER BY subject_code",
                (rs, rowNum) ->
                        new MemberRow(
                                rs.getLong("id"),
                                rs.getString("subject_code"),
                                rs.getString("name"),
                                rs.getString("industry")));
    }

    @Override
    public List<MentionCountRow> countMentionsByIndustry(
            String industry, String fromIso, String toIso) {
        // §4.4.2 对账 SQL 的批量化形态：json_each(matched_subjects) 展开按 code 计数（与单标的 EXISTS 口径等值）
        return jdbcTemplate.query(
                """
                SELECT json_extract(m.value, '$.code') AS code, COUNT(*) AS mentions
                FROM news_analysis na
                JOIN news_item ni ON ni.id = na.news_id, json_each(na.matched_subjects) m
                WHERE na.l1_status = 'DONE' AND na.main_category = ?
                  AND ni.created_at >= ? AND ni.created_at < ?
                  AND json_extract(m.value, '$.code') IS NOT NULL
                GROUP BY json_extract(m.value, '$.code')
                ORDER BY code
                """,
                (rs, rowNum) -> new MentionCountRow(rs.getString("code"), rs.getInt("mentions")),
                industry,
                fromIso,
                toIso);
    }

    @Override
    public List<SubjectEventLinkRow> findSubjectEventLinks(
            String industry, String fromDate, String toDate) {
        return jdbcTemplate.query(
                """
                SELECT json_extract(s.value, '$.code') AS code, e.id AS event_id,
                       e.importance, e.direction
                FROM event_item e, json_each(e.subjects) s, json_each(e.affected_industries) i
                WHERE i.value = ? AND e.event_date >= ? AND e.event_date <= ?
                  AND json_extract(s.value, '$.code') IS NOT NULL
                ORDER BY e.id
                """,
                (rs, rowNum) ->
                        new SubjectEventLinkRow(
                                rs.getString("code"),
                                rs.getLong("event_id"),
                                rs.getString("importance"),
                                rs.getString("direction")),
                industry,
                fromDate,
                toDate);
    }

    @Override
    public Optional<String> latestFactorSnapshotDate() {
        return Optional.ofNullable(
                jdbcTemplate.queryForObject(
                        "SELECT MAX(snapshot_date) FROM subject_factor_snapshot", String.class));
    }

    @Override
    public List<FactorScoreRow> findFactorScores(String snapshotDate) {
        return jdbcTemplate.query(
                "SELECT subject_id, total_score, data_flags FROM subject_factor_snapshot"
                        + " WHERE snapshot_date = ? ORDER BY subject_id",
                (rs, rowNum) ->
                        new FactorScoreRow(
                                rs.getLong("subject_id"),
                                rs.getDouble("total_score"),
                                rs.getString("data_flags")),
                snapshotDate);
    }

    @Override
    public List<MarketQuoteRow> findMarketPctChangeForDates(List<String> snapshotDates) {
        if (snapshotDates == null || snapshotDates.isEmpty()) {
            return List.of();
        }
        String placeholders =
                String.join(",", java.util.Collections.nCopies(snapshotDates.size(), "?"));
        Object[] args = snapshotDates.toArray();
        return jdbcTemplate.query(
                "SELECT sm.subject_code, mds.snapshot_date, mds.pct_change"
                        + " FROM market_daily_snapshot mds JOIN subject_master sm ON sm.id = mds.subject_id"
                        + " WHERE mds.snapshot_date IN ("
                        + placeholders
                        + ") ORDER BY sm.subject_code, mds.snapshot_date",
                (rs, rowNum) ->
                        new MarketQuoteRow(
                                rs.getString("subject_code"),
                                rs.getString("snapshot_date"),
                                (Double) rs.getObject("pct_change")),
                args);
    }

    @Override
    public List<String> recentMarketQuoteDates(int limit) {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT snapshot_date FROM market_daily_snapshot"
                        + " ORDER BY snapshot_date DESC LIMIT ?",
                String.class,
                limit);
    }

    private List<MainlineRankRow> rankRows(String rankDate, int version) {
        return jdbcTemplate.query(
                "SELECT "
                        + RANK_COLUMNS
                        + " FROM industry_mainline"
                        + " WHERE rank_date = ? AND version = ? ORDER BY rank_no",
                RANK_ROW,
                rankDate,
                version);
    }
}
