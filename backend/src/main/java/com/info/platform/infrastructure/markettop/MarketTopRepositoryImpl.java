package com.info.platform.infrastructure.markettop;

import com.info.platform.domain.markettop.MarketTopRepository;
import com.info.platform.domain.markettop.RankDiffer.PrevSubject;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link MarketTopRepository} 端口的 SQLite 实现（M21 T183，V31 两表）：追加式版本化写（batch + ranks 同一事务）；读取按 「日期 +
 * 最大 version」（idx_mtr_date_ver 命中）；昨日榜单 = rank_date &lt; ? 且 ≥ ?−7 天的最近有榜日（跨假日回看，方案 §4.5.2）。
 */
@Repository
public class MarketTopRepositoryImpl implements MarketTopRepository {

    /** 昨日榜单回看窗（自然日；跨假日取最近有榜单日 ≤7 天）。 */
    static final int PREVIOUS_LOOKBACK_DAYS = 7;

    private static final String INSERT_RANK_SQL =
            """
            INSERT INTO market_top_rank
              (rank_date, version, rank_no, subject_id, subject_code, subject_name, total_score,
               final_score, percentile, breakthrough, generation, dive_method, dive_summary,
               dive_detail, evidence_count, last_event_date, prev_rank, change_type, basis,
               computed_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_BATCH_SQL =
            """
            INSERT INTO market_top_batch
              (rank_date, version, trigger_source, snapshot_date, funnel_stats, degraded,
               degraded_reason, dropped_subjects, dive_cost_micros, dive_llm_calls,
               prompt_version, basis, trigger_events, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String RANK_COLUMNS =
            """
            rank_date, version, rank_no, subject_id, subject_code, subject_name, total_score,
            final_score, percentile, breakthrough, generation, dive_method, dive_summary,
            dive_detail, evidence_count, last_event_date, prev_rank, change_type, basis,
            computed_at
            """;

    private static final RowMapper<MarketTopRankRow> RANK_ROW =
            (rs, rowNum) ->
                    new MarketTopRankRow(
                            rs.getString("rank_date"),
                            rs.getInt("version"),
                            rs.getInt("rank_no"),
                            rs.getLong("subject_id"),
                            rs.getString("subject_code"),
                            rs.getString("subject_name"),
                            rs.getDouble("total_score"),
                            rs.getDouble("final_score"),
                            (Double) rs.getObject("percentile"),
                            rs.getInt("breakthrough") == 1,
                            rs.getString("generation"),
                            rs.getString("dive_method"),
                            rs.getString("dive_summary"),
                            rs.getString("dive_detail"),
                            rs.getInt("evidence_count"),
                            rs.getString("last_event_date"),
                            (Integer) rs.getObject("prev_rank"),
                            rs.getString("change_type"),
                            rs.getString("basis"),
                            rs.getString("computed_at"));

    private static final RowMapper<MarketTopBatchRow> BATCH_ROW =
            (rs, rowNum) ->
                    new MarketTopBatchRow(
                            rs.getString("rank_date"),
                            rs.getInt("version"),
                            rs.getString("trigger_source"),
                            rs.getString("snapshot_date"),
                            rs.getString("funnel_stats"),
                            rs.getInt("degraded") == 1,
                            rs.getString("degraded_reason"),
                            rs.getString("dropped_subjects"),
                            rs.getLong("dive_cost_micros"),
                            rs.getInt("dive_llm_calls"),
                            rs.getString("prompt_version"),
                            rs.getString("basis"),
                            rs.getString("trigger_events"),
                            rs.getString("created_at"));

    private static final RowMapper<PrevSubject> PREV_ROW =
            (rs, rowNum) ->
                    new PrevSubject(
                            rs.getLong("subject_id"),
                            rs.getString("subject_code"),
                            rs.getString("subject_name"),
                            rs.getInt("rank_no"));

    private static final RowMapper<VersionSummary> SUMMARY_ROW =
            (rs, rowNum) ->
                    new VersionSummary(
                            rs.getString("rank_date"),
                            rs.getInt("version"),
                            rs.getString("trigger_source"),
                            rs.getInt("degraded") == 1,
                            rs.getString("degraded_reason"),
                            rs.getString("snapshot_date"),
                            rs.getInt("top_size"),
                            rs.getString("computed_at"));

    private final JdbcTemplate jdbcTemplate;

    public MarketTopRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int maxVersion(String rankDate) {
        Integer max =
                jdbcTemplate.queryForObject(
                        "SELECT MAX(version) FROM market_top_batch WHERE rank_date = ?",
                        Integer.class,
                        rankDate);
        return max == null ? 0 : max;
    }

    @Override
    @Transactional
    public int insertVersion(MarketTopBatchRow batch, List<MarketTopRankRow> ranks) {
        Timestamp now = Timestamp.valueOf(java.time.LocalDateTime.now(java.time.Clock.systemUTC()));
        jdbcTemplate.update(
                INSERT_BATCH_SQL,
                batch.rankDate(),
                batch.version(),
                batch.triggerSource(),
                batch.snapshotDate(),
                batch.funnelStatsJson(),
                batch.degraded() ? 1 : 0,
                batch.degradedReason(),
                batch.droppedSubjectsJson(),
                batch.diveCostMicros(),
                batch.diveLlmCalls(),
                batch.promptVersion(),
                batch.basis(),
                batch.triggerEventsJson(),
                now,
                now);
        jdbcTemplate.batchUpdate(
                INSERT_RANK_SQL,
                ranks,
                ranks.size(),
                (PreparedStatement ps, MarketTopRankRow rank) -> {
                    ps.setString(1, rank.rankDate());
                    ps.setInt(2, rank.version());
                    ps.setInt(3, rank.rankNo());
                    ps.setLong(4, rank.subjectId());
                    ps.setString(5, rank.subjectCode());
                    ps.setString(6, rank.subjectName());
                    ps.setDouble(7, rank.totalScore());
                    ps.setDouble(8, rank.finalScore());
                    ps.setObject(9, rank.percentile());
                    ps.setInt(10, rank.breakthrough() ? 1 : 0);
                    ps.setString(11, rank.generation());
                    ps.setString(12, rank.diveMethod());
                    ps.setString(13, rank.diveSummary());
                    ps.setString(14, rank.diveDetailJson());
                    ps.setInt(15, rank.evidenceCount());
                    ps.setString(16, rank.lastEventDate());
                    ps.setObject(17, rank.prevRank());
                    ps.setString(18, rank.changeType());
                    ps.setString(19, rank.basis());
                    ps.setString(20, rank.computedAt());
                    ps.setTimestamp(21, now);
                    ps.setTimestamp(22, now);
                });
        return ranks.size();
    }

    @Override
    public Optional<MarketTopVersion> find(String rankDate, int version) {
        List<MarketTopBatchRow> batches =
                jdbcTemplate.query(
                        "SELECT * FROM market_top_batch WHERE rank_date = ? AND version = ?",
                        BATCH_ROW,
                        rankDate,
                        version);
        if (batches.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                new MarketTopVersion(
                        batches.get(0),
                        jdbcTemplate.query(
                                "SELECT "
                                        + RANK_COLUMNS
                                        + " FROM market_top_rank"
                                        + " WHERE rank_date = ? AND version = ? ORDER BY rank_no ASC",
                                RANK_ROW,
                                rankDate,
                                version)));
    }

    @Override
    public Optional<MarketTopVersion> findLatest(String rankDate) {
        return find(rankDate, maxVersion(rankDate));
    }

    @Override
    public Optional<MarketTopVersion> findLatestAnyDate() {
        String latestDate =
                DataAccessUtils.singleResult(
                        jdbcTemplate.queryForList(
                                "SELECT MAX(rank_date) FROM market_top_batch", String.class));
        return latestDate == null ? Optional.empty() : findLatest(latestDate);
    }

    @Override
    public List<PrevSubject> findPreviousTop(String rankDate) {
        // 最近有榜单日 = rank_date 严格早于指定日且在 7 天回看窗内的最大日（跨假日回看，§4.5.2）
        String prevDate =
                DataAccessUtils.singleResult(
                        jdbcTemplate.queryForList(
                                "SELECT MAX(rank_date) FROM market_top_batch"
                                        + " WHERE rank_date < ? AND rank_date >= date(?, ?)",
                                String.class,
                                rankDate,
                                rankDate,
                                "-" + PREVIOUS_LOOKBACK_DAYS + " days"));
        if (prevDate == null) {
            return List.of();
        }
        return jdbcTemplate.query(
                "SELECT subject_id, subject_code, subject_name, rank_no FROM market_top_rank"
                        + " WHERE rank_date = ? AND version = (SELECT MAX(version) FROM"
                        + " market_top_rank WHERE rank_date = ?) ORDER BY rank_no ASC",
                PREV_ROW,
                prevDate,
                prevDate);
    }

    @Override
    public List<VersionSummary> listVersions(String rankDate, int limit) {
        String sql =
                "SELECT b.rank_date, b.version, b.trigger_source, b.degraded, b.degraded_reason,"
                        + " b.snapshot_date, b.created_at AS computed_at,"
                        + " (SELECT COUNT(*) FROM market_top_rank r"
                        + "   WHERE r.rank_date = b.rank_date AND r.version = b.version) AS top_size"
                        + " FROM market_top_batch b"
                        + (rankDate == null ? "" : " WHERE b.rank_date = ?")
                        + " ORDER BY b.rank_date DESC, b.version DESC LIMIT ?";
        List<Object> args = new ArrayList<>();
        if (rankDate != null) {
            args.add(rankDate);
        }
        args.add(limit);
        return jdbcTemplate.query(sql, SUMMARY_ROW, args.toArray());
    }
}
