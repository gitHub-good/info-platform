package com.info.platform.infrastructure.valuation;

import com.info.platform.domain.valuation.MarketDailySnapshotRepository;
import com.info.platform.domain.valuation.MarketDailySnapshotRepository.MarketDailyRow;
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
 * {@link MarketDailySnapshotRepository} 端口的 SQLite 实现（M20 T170，V30 表）：UPSERT {@code ON
 * CONFLICT(subject_id, snapshot_date) DO UPDATE}（当日重跑同键覆盖）；按日全量读（F5 横截面 + 缺行判定） 与按日计数（coverage
 * marketDataRows）。
 */
@Repository
public class MarketDailySnapshotRepositoryImpl implements MarketDailySnapshotRepository {

    private static final String UPSERT_SQL =
            """
            INSERT INTO market_daily_snapshot
              (subject_id, snapshot_date, close_price, pct_change, turnover_rate, amplitude,
               volume, pe_ttm, pb, source, quote_time, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(subject_id, snapshot_date) DO UPDATE SET
              close_price = excluded.close_price,
              pct_change = excluded.pct_change,
              turnover_rate = excluded.turnover_rate,
              amplitude = excluded.amplitude,
              volume = excluded.volume,
              pe_ttm = excluded.pe_ttm,
              pb = excluded.pb,
              source = excluded.source,
              quote_time = excluded.quote_time,
              updated_at = excluded.updated_at
            """;

    private static final String FIND_BY_DATE_SQL =
            """
            SELECT subject_id, snapshot_date, close_price, pct_change, turnover_rate, amplitude,
                   volume, pe_ttm, pb, source, quote_time
              FROM market_daily_snapshot
             WHERE snapshot_date = ?
            """;

    private static final RowMapper<MarketDailyRow> ROW =
            (rs, rowNum) ->
                    new MarketDailyRow(
                            rs.getLong("subject_id"),
                            rs.getString("snapshot_date"),
                            nullableDouble(rs, "close_price"),
                            nullableDouble(rs, "pct_change"),
                            nullableDouble(rs, "turnover_rate"),
                            nullableDouble(rs, "amplitude"),
                            nullableDouble(rs, "volume"),
                            nullableDouble(rs, "pe_ttm"),
                            nullableDouble(rs, "pb"),
                            rs.getString("source"),
                            rs.getString("quote_time"));

    private final JdbcTemplate jdbcTemplate;

    public MarketDailySnapshotRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int upsertAll(List<MarketDailyRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        String now = Instant.now().toString();
        int[] results =
                jdbcTemplate.batchUpdate(
                        UPSERT_SQL,
                        new BatchPreparedStatementSetter() {
                            @Override
                            public void setValues(PreparedStatement ps, int i) throws SQLException {
                                MarketDailyRow row = rows.get(i);
                                ps.setLong(1, row.subjectId());
                                ps.setString(2, row.snapshotDate());
                                setNullableDouble(ps, 3, row.closePrice());
                                setNullableDouble(ps, 4, row.pctChange());
                                setNullableDouble(ps, 5, row.turnoverRate());
                                setNullableDouble(ps, 6, row.amplitude());
                                setNullableDouble(ps, 7, row.volume());
                                setNullableDouble(ps, 8, row.peTtm());
                                setNullableDouble(ps, 9, row.pb());
                                ps.setString(10, row.source());
                                ps.setString(11, row.quoteTime());
                                ps.setString(12, now);
                                ps.setString(13, now);
                            }

                            @Override
                            public int getBatchSize() {
                                return rows.size();
                            }
                        });
        int upserted = 0;
        for (int result : results) {
            upserted += result == java.sql.Statement.SUCCESS_NO_INFO ? 1 : Math.max(0, result);
        }
        return upserted;
    }

    @Override
    public Map<Long, MarketDailyRow> findByDate(String snapshotDate) {
        Map<Long, MarketDailyRow> bySubject = new HashMap<>();
        for (MarketDailyRow row : jdbcTemplate.query(FIND_BY_DATE_SQL, ROW, snapshotDate)) {
            bySubject.put(row.subjectId(), row);
        }
        return bySubject;
    }

    @Override
    public long countByDate(String snapshotDate) {
        Long count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM market_daily_snapshot WHERE snapshot_date = ?",
                        Long.class,
                        snapshotDate);
        return count == null ? 0 : count;
    }

    /** M22 T193：交易日序列（distinct 升序——库内交易日历代理，窗口推导原料）。 */
    @Override
    public List<String> findTradingDates() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT snapshot_date FROM market_daily_snapshot ORDER BY snapshot_date ASC",
                String.class);
    }

    /** M22 T193：收盘价投影（close NULL 行缺键——有价样本口径与对账 SQL 的 IS NOT NULL 过滤同义）。 */
    @Override
    public Map<Long, Double> findClosePrices(String snapshotDate) {
        Map<Long, Double> closes = new HashMap<>();
        for (MarketDailyRow row : jdbcTemplate.query(FIND_BY_DATE_SQL, ROW, snapshotDate)) {
            if (row.closePrice() != null) {
                closes.put(row.subjectId(), row.closePrice());
            }
        }
        return closes;
    }

    private static Double nullableDouble(java.sql.ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static void setNullableDouble(PreparedStatement ps, int index, Double value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.REAL);
        } else {
            ps.setDouble(index, value);
        }
    }
}
