package com.info.platform.infrastructure.mainline;

import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.mainline.IndustryMarketSnapshotRepository;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link IndustryMarketSnapshotRepository} 端口的 SQLite 实现（M27 T242，V35 表①；V37 起唯一键含 market 维）：UPSERT
 * 走 {@code INSERT ... ON CONFLICT(row_type, dim_name, snapshot_date, market) DO
 * UPDATE}（盘中轮幂等覆盖，跨日自然新增；market/currency 随行参数化——M27 A 股通道经 {@code MarketSnapshotRow} 兼容构造恒
 * 'A_SHARE'/'CNY'，港美股聚合行由 T252 HKUS 快照轮写入）。
 *
 * <p><b>读面 A_SHARE 保护性过滤（M29 T252）</b>：既有读方法（findIndustryRows / findBoardRows* /
 * recentSnapshotDates / latestSnapshotDate / findIndustryPctDayForDates）SQL 恒加 {@code market =
 * 'A_SHARE'}——港美股行落库后 A 股热力图/主线/pct_d5 交易日窗零回归（方案「A 股 M27 双通道零改动」）；分市场读参数化属 T255 端点改造面。
 *
 * <p><b>港美股行业行替换写（M29 P2-01 修复）</b>：{@link #replaceIndustryRows} = 同事务「UPSERT 本次集 + DELETE 该
 * market 当日未命中旧行」——subject.industry 被 F10 回填改写后旧聚合行不再残留（只增不清的孤儿行根治，快照行数 = 实际行业数）；A 股通道沿
 * {@link #upsertAll} 不变。
 */
@Repository
public class IndustryMarketSnapshotRepositoryImpl implements IndustryMarketSnapshotRepository {

    private static final Logger log =
            LoggerFactory.getLogger(IndustryMarketSnapshotRepositoryImpl.class);

    private static final String UPSERT_SQL =
            """
            INSERT INTO industry_market_snapshot
              (market, row_type, dim_name, industry, snapshot_date, pct_day, pct_d5, up_count, down_count,
               main_net_flow, total_mv, currency, leader_stock, source, agg_method, quote_time,
               created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(row_type, dim_name, snapshot_date, market) DO UPDATE SET
              pct_day = excluded.pct_day, pct_d5 = excluded.pct_d5,
              up_count = excluded.up_count, down_count = excluded.down_count,
              main_net_flow = excluded.main_net_flow, total_mv = excluded.total_mv,
              currency = excluded.currency, leader_stock = excluded.leader_stock,
              source = excluded.source, agg_method = excluded.agg_method,
              quote_time = excluded.quote_time, updated_at = excluded.updated_at
            """;

    private static final String ROW_COLUMNS =
            """
            market, row_type, dim_name, industry, snapshot_date, pct_day, pct_d5, up_count, down_count,
            main_net_flow, total_mv, currency, leader_stock, source, agg_method, quote_time
            """;

    private static final RowMapper<MarketSnapshotRow> ROW =
            (rs, rowNum) ->
                    new MarketSnapshotRow(
                            rs.getString("market"),
                            rs.getString("row_type"),
                            rs.getString("dim_name"),
                            rs.getString("industry"),
                            rs.getString("snapshot_date"),
                            (Double) rs.getObject("pct_day"),
                            (Double) rs.getObject("pct_d5"),
                            (Integer) rs.getObject("up_count"),
                            (Integer) rs.getObject("down_count"),
                            (Double) rs.getObject("main_net_flow"),
                            (Double) rs.getObject("total_mv"),
                            rs.getString("currency"),
                            rs.getString("leader_stock"),
                            rs.getString("source"),
                            rs.getString("agg_method"),
                            rs.getString("quote_time"));

    private static final RowMapper<HistoryPctDay> HISTORY_ROW =
            (rs, rowNum) ->
                    new HistoryPctDay(
                            rs.getString("snapshot_date"),
                            rs.getString("industry"),
                            (Double) rs.getObject("pct_day"));

    private final JdbcTemplate jdbcTemplate;

    private final TransactionTemplate transactionTemplate;

    public IndustryMarketSnapshotRepositoryImpl(
            JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public int upsertAll(List<MarketSnapshotRow> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        Integer written = transactionTemplate.execute(status -> upsertRows(rows, nowText()));
        return written == null ? 0 : written;
    }

    @Override
    public int replaceIndustryRows(String market, String snapshotDate, List<MarketSnapshotRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0; // 空聚合轮 no-op 不清理（端口契约：保守不动旧快照）
        }
        Integer written =
                transactionTemplate.execute(
                        status -> {
                            int total = upsertRows(rows, nowText());
                            pruneStaleIndustryRows(market, snapshotDate, rows);
                            return total;
                        });
        return written == null ? 0 : written;
    }

    /** 事务内逐行 UPSERT（M27 既有写路径原样抽出复用）。 */
    private int upsertRows(List<MarketSnapshotRow> rows, String now) {
        int total = 0;
        for (MarketSnapshotRow row : rows) {
            total +=
                    jdbcTemplate.update(
                            connection -> {
                                PreparedStatement ps = connection.prepareStatement(UPSERT_SQL);
                                ps.setString(1, row.market());
                                ps.setString(2, row.rowType());
                                ps.setString(3, row.dimName());
                                ps.setString(4, row.industry());
                                ps.setString(5, row.snapshotDate());
                                setNullableDouble(ps, 6, row.pctDay());
                                setNullableDouble(ps, 7, row.pctD5());
                                setNullableInt(ps, 8, row.upCount());
                                setNullableInt(ps, 9, row.downCount());
                                setNullableDouble(ps, 10, row.mainNetFlow());
                                setNullableDouble(ps, 11, row.totalMv());
                                ps.setString(12, row.currency());
                                ps.setString(13, row.leaderStockJson());
                                ps.setString(14, row.source());
                                ps.setString(15, row.aggMethod());
                                ps.setString(16, row.quoteTime());
                                ps.setString(17, now);
                                ps.setString(18, now);
                                return ps;
                            });
        }
        return total;
    }

    @Override
    public List<MarketSnapshotRow> findIndustryRows(String snapshotDate) {
        return findIndustryRows(snapshotDate, Market.A_SHARE);
    }

    @Override
    public List<MarketSnapshotRow> findIndustryRows(String snapshotDate, Market market) {
        return jdbcTemplate.query(
                "SELECT "
                        + ROW_COLUMNS
                        + " FROM industry_market_snapshot"
                        + " WHERE snapshot_date = ? AND row_type = 'INDUSTRY' AND market = ?"
                        + " ORDER BY dim_name",
                ROW,
                snapshotDate,
                market.name());
    }

    @Override
    public List<MarketSnapshotRow> findBoardRows(String snapshotDate) {
        return jdbcTemplate.query(
                "SELECT "
                        + ROW_COLUMNS
                        + " FROM industry_market_snapshot"
                        + " WHERE snapshot_date = ? AND row_type = 'BOARD' AND market = 'A_SHARE'"
                        + " ORDER BY dim_name",
                ROW,
                snapshotDate);
    }

    @Override
    public List<MarketSnapshotRow> findBoardRowsOfIndustry(String snapshotDate, String industry) {
        return jdbcTemplate.query(
                "SELECT "
                        + ROW_COLUMNS
                        + " FROM industry_market_snapshot"
                        + " WHERE snapshot_date = ? AND row_type = 'BOARD' AND industry = ?"
                        + " AND market = 'A_SHARE'"
                        + " ORDER BY dim_name",
                ROW,
                snapshotDate,
                industry);
    }

    @Override
    public Optional<String> latestSnapshotDate() {
        return latestSnapshotDate(Market.A_SHARE);
    }

    @Override
    public Optional<String> latestSnapshotDate(Market market) {
        return Optional.ofNullable(
                jdbcTemplate.queryForObject(
                        "SELECT MAX(snapshot_date) FROM industry_market_snapshot WHERE market = ?",
                        String.class,
                        market.name()));
    }

    @Override
    public List<String> recentSnapshotDates(int limit) {
        return recentSnapshotDates(limit, Market.A_SHARE);
    }

    @Override
    public List<String> recentSnapshotDates(int limit, Market market) {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT snapshot_date FROM industry_market_snapshot"
                        + " WHERE market = ? ORDER BY snapshot_date DESC LIMIT ?",
                String.class,
                market.name(),
                limit);
    }

    @Override
    public List<HistoryPctDay> findIndustryPctDayForDates(List<String> snapshotDates) {
        return findIndustryPctDayForDates(snapshotDates, Market.A_SHARE);
    }

    @Override
    public List<HistoryPctDay> findIndustryPctDayForDates(
            List<String> snapshotDates, Market market) {
        if (snapshotDates == null || snapshotDates.isEmpty()) {
            return List.of();
        }
        String placeholders =
                String.join(",", java.util.Collections.nCopies(snapshotDates.size(), "?"));
        Object[] args =
                java.util.stream.Stream.concat(
                                java.util.stream.Stream.of(market.name()), snapshotDates.stream())
                        .toArray();
        return jdbcTemplate.query(
                "SELECT snapshot_date, industry, pct_day FROM industry_market_snapshot"
                        + " WHERE row_type = 'INDUSTRY' AND market = ? AND snapshot_date IN ("
                        + placeholders
                        + ") ORDER BY snapshot_date, industry",
                HISTORY_ROW,
                args);
    }

    /** 当轮统一时间戳（整秒 ISO-8601，M27 既有口径）。 */
    private static String nowText() {
        return java.time.Instant.now()
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                .toString();
    }

    /**
     * 清理该 market 当日未命中的旧行业行（P2-01 写侧清理，调用点已在事务内）：DELETE ... WHERE market = ? AND snapshot_date = ?
     * AND row_type = 'INDUSTRY' AND dim_name NOT IN (本次保留集)——A 股同名行/前日行/BOARD 行由 market+日期+row_type 三重隔离不误删。
     */
    private void pruneStaleIndustryRows(
            String market, String snapshotDate, List<MarketSnapshotRow> rows) {
        List<String> keepIndustries =
                rows.stream().map(MarketSnapshotRow::dimName).distinct().toList();
        String placeholders =
                String.join(",", java.util.Collections.nCopies(keepIndustries.size(), "?"));
        Object[] args =
                java.util.stream.Stream.concat(
                                java.util.stream.Stream.of(market, snapshotDate),
                                keepIndustries.stream())
                        .toArray();
        int pruned =
                jdbcTemplate.update(
                        "DELETE FROM industry_market_snapshot"
                                + " WHERE market = ? AND snapshot_date = ? AND row_type = 'INDUSTRY'"
                                + " AND dim_name NOT IN ("
                                + placeholders
                                + ")",
                        args);
        if (pruned > 0) {
            log.info(
                    "行业快照孤儿行清理 market={} date={} pruned={}（subject.industry 改写后旧行不留，P2-01 写侧清理）",
                    market,
                    snapshotDate,
                    pruned);
        }
    }

    private static void setNullableDouble(PreparedStatement ps, int index, Double value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.REAL);
        } else {
            ps.setDouble(index, value);
        }
    }

    private static void setNullableInt(PreparedStatement ps, int index, Integer value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }
}
