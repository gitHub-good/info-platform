package com.info.platform.infrastructure.markettop;

import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.markettop.MarketTopRepository;
import com.info.platform.domain.markettop.RankDiffer.PrevSubject;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link MarketTopRepository} 端口的 SQLite 实现（M21 T183，V31 两表；M29 T256 分市场——V37 两表 market 维 +
 * UNIQUE(rank_date, version, market)）：追加式版本化写（batch + ranks 同一事务，market 内 version 递增）；读取按「日期 + 市场 +
 * 最大 version」 （idx_mtr_date_ver 命中）；昨日榜单 = 同市场 rank_date &lt; ? 且 ≥ ?−7 天的最近有榜日（跨假日回看，方案 §4.5.2）。
 */
@Repository
public class MarketTopRepositoryImpl implements MarketTopRepository {

    /** 昨日榜单回看窗（自然日；跨假日取最近有榜单日 ≤7 天）。 */
    static final int PREVIOUS_LOOKBACK_DAYS = 7;

    private static final Logger log = LoggerFactory.getLogger(MarketTopRepositoryImpl.class);

    private static final String INSERT_RANK_SQL =
            """
            INSERT INTO market_top_rank
              (market, rank_date, version, rank_no, subject_id, subject_code, subject_name,
               total_score, final_score, percentile, breakthrough, generation, dive_method,
               dive_summary, dive_detail, evidence_count, last_event_date, prev_rank, change_type,
               basis, computed_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_BATCH_SQL =
            """
            INSERT INTO market_top_batch
              (market, rank_date, version, trigger_source, snapshot_date, funnel_stats, degraded,
               degraded_reason, dropped_subjects, dive_cost_micros, dive_llm_calls,
               prompt_version, basis, trigger_events, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String RANK_COLUMNS =
            """
            market, rank_date, version, rank_no, subject_id, subject_code, subject_name,
            total_score, final_score, percentile, breakthrough, generation, dive_method,
            dive_summary, dive_detail, evidence_count, last_event_date, prev_rank, change_type,
            basis, computed_at
            """;

    private static final RowMapper<MarketTopRankRow> RANK_ROW =
            (rs, rowNum) ->
                    new MarketTopRankRow(
                            Market.fromName(rs.getString("market")),
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
                            Market.fromName(rs.getString("market")),
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
                            Market.fromName(rs.getString("market")),
                            rs.getString("rank_date"),
                            rs.getInt("version"),
                            rs.getString("trigger_source"),
                            rs.getInt("degraded") == 1,
                            rs.getString("degraded_reason"),
                            rs.getString("snapshot_date"),
                            rs.getInt("top_size"),
                            rs.getString("computed_at"));

    private final JdbcTemplate jdbcTemplate;

    private final TransactionTemplate transactionTemplate;

    public MarketTopRepositoryImpl(
            JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public int maxVersion(String rankDate, Market market) {
        Integer max =
                jdbcTemplate.queryForObject(
                        "SELECT MAX(version) FROM market_top_batch"
                                + " WHERE rank_date = ? AND market = ?",
                        Integer.class,
                        rankDate,
                        market.name());
        return max == null ? 0 : max;
    }

    /**
     * 追加一版本（batch + ranks 两表一事务，market 内 version 递增）。互斥层③（M22 T191，ADR-0061 裁决 3）：UNIQUE(rank_date,
     * version, market) 冲突（增量联动进行中 DAILY 起跑的反向缝隙——重试不适用 @Transactional 自调用代理失效，改走
     * TransactionTemplate 程序化事务）→ version 重取 maxVersion+1 重试一次；二次仍冲突或非冲突异常原样上抛。
     */
    @Override
    public int insertVersion(MarketTopBatchRow batch, List<MarketTopRankRow> ranks) {
        try {
            return insertOnce(batch, ranks);
        } catch (DataAccessException e) {
            if (!isUniqueConflict(e)) {
                throw e;
            }
            int retryVersion = maxVersion(batch.rankDate(), batch.market()) + 1;
            log.warn(
                    "榜单版本唯一冲突（并发联动），重取 version={} 重试一次: rankDate={} market={} 原version={}",
                    retryVersion,
                    batch.rankDate(),
                    batch.market(),
                    batch.version());
            return insertOnce(withVersion(batch, retryVersion), withVersion(ranks, retryVersion));
        }
    }

    private int insertOnce(MarketTopBatchRow batch, List<MarketTopRankRow> ranks) {
        Timestamp now = Timestamp.valueOf(java.time.LocalDateTime.now(java.time.Clock.systemUTC()));
        transactionTemplate.executeWithoutResult(
                status -> {
                    jdbcTemplate.update(
                            INSERT_BATCH_SQL,
                            batch.market().name(),
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
                                ps.setString(1, rank.market().name());
                                ps.setString(2, rank.rankDate());
                                ps.setInt(3, rank.version());
                                ps.setInt(4, rank.rankNo());
                                ps.setLong(5, rank.subjectId());
                                ps.setString(6, rank.subjectCode());
                                ps.setString(7, rank.subjectName());
                                ps.setDouble(8, rank.totalScore());
                                ps.setDouble(9, rank.finalScore());
                                ps.setObject(10, rank.percentile());
                                ps.setInt(11, rank.breakthrough() ? 1 : 0);
                                ps.setString(12, rank.generation());
                                ps.setString(13, rank.diveMethod());
                                ps.setString(14, rank.diveSummary());
                                ps.setString(15, rank.diveDetailJson());
                                ps.setInt(16, rank.evidenceCount());
                                ps.setString(17, rank.lastEventDate());
                                ps.setObject(18, rank.prevRank());
                                ps.setString(19, rank.changeType());
                                ps.setString(20, rank.basis());
                                ps.setString(21, rank.computedAt());
                                ps.setTimestamp(22, now);
                                ps.setTimestamp(23, now);
                            });
                });
        return ranks.size();
    }

    private static MarketTopBatchRow withVersion(MarketTopBatchRow batch, int version) {
        return new MarketTopBatchRow(
                batch.market(),
                batch.rankDate(),
                version,
                batch.triggerSource(),
                batch.snapshotDate(),
                batch.funnelStatsJson(),
                batch.degraded(),
                batch.degradedReason(),
                batch.droppedSubjectsJson(),
                batch.diveCostMicros(),
                batch.diveLlmCalls(),
                batch.promptVersion(),
                batch.basis(),
                batch.triggerEventsJson(),
                batch.createdAt());
    }

    private static List<MarketTopRankRow> withVersion(List<MarketTopRankRow> ranks, int version) {
        return ranks.stream()
                .map(
                        rank ->
                                new MarketTopRankRow(
                                        rank.market(),
                                        rank.rankDate(),
                                        version,
                                        rank.rankNo(),
                                        rank.subjectId(),
                                        rank.subjectCode(),
                                        rank.subjectName(),
                                        rank.totalScore(),
                                        rank.finalScore(),
                                        rank.percentile(),
                                        rank.breakthrough(),
                                        rank.generation(),
                                        rank.diveMethod(),
                                        rank.diveSummary(),
                                        rank.diveDetailJson(),
                                        rank.evidenceCount(),
                                        rank.lastEventDate(),
                                        rank.prevRank(),
                                        rank.changeType(),
                                        rank.basis(),
                                        rank.computedAt()))
                .toList();
    }

    /** SQLite 唯一冲突判定（xerial 未映射专用异常类型——按消息文本，V31 迁移测试同口径）。 */
    private static boolean isUniqueConflict(DataAccessException e) {
        String message =
                String.valueOf(
                        e.getMostSpecificCause() != null
                                ? e.getMostSpecificCause().getMessage()
                                : e.getMessage());
        return message.contains("UNIQUE constraint failed")
                && (message.contains("market_top_batch") || message.contains("market_top_rank"));
    }

    @Override
    public Optional<MarketTopVersion> find(String rankDate, int version, Market market) {
        List<MarketTopBatchRow> batches =
                jdbcTemplate.query(
                        "SELECT * FROM market_top_batch"
                                + " WHERE rank_date = ? AND version = ? AND market = ?",
                        BATCH_ROW,
                        rankDate,
                        version,
                        market.name());
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
                                        + " WHERE rank_date = ? AND version = ? AND market = ?"
                                        + " ORDER BY rank_no ASC",
                                RANK_ROW,
                                rankDate,
                                version,
                                market.name())));
    }

    @Override
    public Optional<MarketTopVersion> findLatest(String rankDate, Market market) {
        return find(rankDate, maxVersion(rankDate, market), market);
    }

    @Override
    public Optional<MarketTopVersion> findLatestAnyDate(Market market) {
        String latestDate =
                DataAccessUtils.singleResult(
                        jdbcTemplate.queryForList(
                                "SELECT MAX(rank_date) FROM market_top_batch WHERE market = ?",
                                String.class,
                                market.name()));
        return latestDate == null ? Optional.empty() : findLatest(latestDate, market);
    }

    @Override
    public List<PrevSubject> findPreviousTop(String rankDate, Market market) {
        // 最近有榜单日 = 同市场 rank_date 严格早于指定日且在 7 天回看窗内的最大日（跨假日回看，§4.5.2；三市场不混榜）
        String prevDate =
                DataAccessUtils.singleResult(
                        jdbcTemplate.queryForList(
                                "SELECT MAX(rank_date) FROM market_top_batch"
                                        + " WHERE market = ? AND rank_date < ?"
                                        + " AND rank_date >= date(?, ?)",
                                String.class,
                                market.name(),
                                rankDate,
                                rankDate,
                                "-" + PREVIOUS_LOOKBACK_DAYS + " days"));
        if (prevDate == null) {
            return List.of();
        }
        return jdbcTemplate.query(
                "SELECT subject_id, subject_code, subject_name, rank_no FROM market_top_rank"
                        + " WHERE rank_date = ? AND market = ?"
                        + " AND version = (SELECT MAX(version) FROM market_top_rank"
                        + " WHERE rank_date = ? AND market = ?) ORDER BY rank_no ASC",
                PREV_ROW,
                prevDate,
                market.name(),
                prevDate,
                market.name());
    }

    @Override
    public List<VersionSummary> listVersions(String rankDate, Market market, int limit) {
        String sql =
                "SELECT b.market, b.rank_date, b.version, b.trigger_source, b.degraded,"
                        + " b.degraded_reason, b.snapshot_date, b.created_at AS computed_at,"
                        + " (SELECT COUNT(*) FROM market_top_rank r"
                        + "   WHERE r.rank_date = b.rank_date AND r.version = b.version"
                        + "     AND r.market = b.market) AS top_size"
                        + " FROM market_top_batch b"
                        + " WHERE b.market = ?"
                        + (rankDate == null ? "" : " AND b.rank_date = ?")
                        + " ORDER BY b.rank_date DESC, b.version DESC LIMIT ?";
        List<Object> args = new ArrayList<>();
        args.add(market.name());
        if (rankDate != null) {
            args.add(rankDate);
        }
        args.add(limit);
        return jdbcTemplate.query(sql, SUMMARY_ROW, args.toArray());
    }

    /** M22 T192：当日该市场最新 EVENT 版本（version 降序首行——页头「最近增量重评」数据源；EVENT 联动恒 A 股）。 */
    @Override
    public Optional<EventVersion> findLatestEventVersion(String rankDate, Market market) {
        return jdbcTemplate
                .query(
                        "SELECT version, created_at, trigger_events FROM market_top_batch"
                                + " WHERE rank_date = ? AND market = ? AND trigger_source = 'EVENT'"
                                + " ORDER BY version DESC LIMIT 1",
                        (rs, rowNum) ->
                                new EventVersion(
                                        rs.getInt("version"),
                                        rs.getString("created_at"),
                                        rs.getString("trigger_events")),
                        rankDate,
                        market.name())
                .stream()
                .findFirst();
    }

    /** M22 T193：该市场各榜单日最大 version Top 行（batch 表 maxVersion 子查询收口日终语义——market 内不混榜）。 */
    @Override
    public List<RankedSubject> listTopByMaxVersion(Market market) {
        return jdbcTemplate.query(
                """
                SELECT r.rank_date, r.rank_no, r.subject_id
                  FROM market_top_rank r
                  JOIN (SELECT rank_date, MAX(version) AS max_version
                          FROM market_top_batch WHERE market = ? GROUP BY rank_date) b
                    ON b.rank_date = r.rank_date AND r.version = b.max_version
                 WHERE r.market = ?
                 ORDER BY r.rank_date DESC, r.rank_no ASC
                """,
                (rs, rowNum) ->
                        new RankedSubject(
                                rs.getString("rank_date"),
                                rs.getInt("rank_no"),
                                rs.getLong("subject_id")),
                market.name(),
                market.name());
    }
}
