package com.info.platform.infrastructure.mainline;

import com.info.platform.domain.mainline.IndustryMarketSnapshotRepository;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link IndustryMarketSnapshotRepository} 端口的 SQLite 实现（M27 T242，V35 表①）：UPSERT 走 {@code INSERT
 * ... ON CONFLICT(row_type, dim_name, snapshot_date) DO UPDATE}（盘中轮幂等覆盖，跨日自然新增）；读取按快照日（idx_ims_date
 * 命中）。
 */
@Repository
public class IndustryMarketSnapshotRepositoryImpl implements IndustryMarketSnapshotRepository {

    private static final String UPSERT_SQL =
            """
            INSERT INTO industry_market_snapshot
              (row_type, dim_name, industry, snapshot_date, pct_day, pct_d5, up_count, down_count,
               main_net_flow, total_mv, leader_stock, source, agg_method, quote_time,
               created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(row_type, dim_name, snapshot_date) DO UPDATE SET
              pct_day = excluded.pct_day, pct_d5 = excluded.pct_d5,
              up_count = excluded.up_count, down_count = excluded.down_count,
              main_net_flow = excluded.main_net_flow, total_mv = excluded.total_mv,
              leader_stock = excluded.leader_stock, source = excluded.source,
              agg_method = excluded.agg_method, quote_time = excluded.quote_time,
              updated_at = excluded.updated_at
            """;

    private static final String ROW_COLUMNS =
            """
            row_type, dim_name, industry, snapshot_date, pct_day, pct_d5, up_count, down_count,
            main_net_flow, total_mv, leader_stock, source, agg_method, quote_time
            """;

    private static final RowMapper<MarketSnapshotRow> ROW =
            (rs, rowNum) ->
                    new MarketSnapshotRow(
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
        String now =
                java.time.Instant.now()
                        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                        .toString();
        Integer written =
                transactionTemplate.execute(
                        status -> {
                            int total = 0;
                            for (MarketSnapshotRow row : rows) {
                                total +=
                                        jdbcTemplate.update(
                                                connection -> {
                                                    PreparedStatement ps =
                                                            connection.prepareStatement(UPSERT_SQL);
                                                    ps.setString(1, row.rowType());
                                                    ps.setString(2, row.dimName());
                                                    ps.setString(3, row.industry());
                                                    ps.setString(4, row.snapshotDate());
                                                    setNullableDouble(ps, 5, row.pctDay());
                                                    setNullableDouble(ps, 6, row.pctD5());
                                                    setNullableInt(ps, 7, row.upCount());
                                                    setNullableInt(ps, 8, row.downCount());
                                                    setNullableDouble(ps, 9, row.mainNetFlow());
                                                    setNullableDouble(ps, 10, row.totalMv());
                                                    ps.setString(11, row.leaderStockJson());
                                                    ps.setString(12, row.source());
                                                    ps.setString(13, row.aggMethod());
                                                    ps.setString(14, row.quoteTime());
                                                    ps.setString(15, now);
                                                    ps.setString(16, now);
                                                    return ps;
                                                });
                            }
                            return total;
                        });
        return written == null ? 0 : written;
    }

    @Override
    public List<MarketSnapshotRow> findIndustryRows(String snapshotDate) {
        return jdbcTemplate.query(
                "SELECT "
                        + ROW_COLUMNS
                        + " FROM industry_market_snapshot"
                        + " WHERE snapshot_date = ? AND row_type = 'INDUSTRY' ORDER BY dim_name",
                ROW,
                snapshotDate);
    }

    @Override
    public List<MarketSnapshotRow> findBoardRows(String snapshotDate) {
        return jdbcTemplate.query(
                "SELECT "
                        + ROW_COLUMNS
                        + " FROM industry_market_snapshot"
                        + " WHERE snapshot_date = ? AND row_type = 'BOARD' ORDER BY dim_name",
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
                        + " ORDER BY dim_name",
                ROW,
                snapshotDate,
                industry);
    }

    @Override
    public Optional<String> latestSnapshotDate() {
        return Optional.ofNullable(
                jdbcTemplate.queryForObject(
                        "SELECT MAX(snapshot_date) FROM industry_market_snapshot", String.class));
    }

    @Override
    public List<String> recentSnapshotDates(int limit) {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT snapshot_date FROM industry_market_snapshot"
                        + " ORDER BY snapshot_date DESC LIMIT ?",
                String.class,
                limit);
    }

    @Override
    public List<HistoryPctDay> findIndustryPctDayForDates(List<String> snapshotDates) {
        if (snapshotDates == null || snapshotDates.isEmpty()) {
            return List.of();
        }
        String placeholders =
                String.join(",", java.util.Collections.nCopies(snapshotDates.size(), "?"));
        Object[] args = snapshotDates.toArray();
        return jdbcTemplate.query(
                "SELECT snapshot_date, industry, pct_day FROM industry_market_snapshot"
                        + " WHERE row_type = 'INDUSTRY' AND snapshot_date IN ("
                        + placeholders
                        + ") ORDER BY snapshot_date, industry",
                HISTORY_ROW,
                args);
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
