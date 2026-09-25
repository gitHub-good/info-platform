package com.info.platform.infrastructure.feed;

import com.info.platform.domain.feed.SourceDailyStats;
import com.info.platform.domain.feed.SourceDailyStatsRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * {@link SourceDailyStatsRepository} 端口的 SQLite 实现（M13 T100，方案 §3.5）。
 *
 * <p>{@link #increment} 走 SQLite UPSERT（{@code ON CONFLICT DO UPDATE} 原子自增，UNIQUE(source_id,
 * stat_date) 幂等锚点）； stat_date 为 Asia/Shanghai yyyy-MM-dd（运营心智本地日，由调用方换算）。
 */
@Repository
public class SourceDailyStatsRepositoryImpl implements SourceDailyStatsRepository {

    private static final String UPSERT_SQL =
            """
            INSERT INTO source_daily_stats
              (source_id, stat_date, poll_count, fail_count, new_count, dup_count,
               created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(source_id, stat_date) DO UPDATE SET
              poll_count = poll_count + excluded.poll_count,
              fail_count = fail_count + excluded.fail_count,
              new_count  = new_count  + excluded.new_count,
              dup_count  = dup_count  + excluded.dup_count,
              updated_at = excluded.updated_at
            """;

    private static final String FIND_SINCE_SQL =
            """
            SELECT id, source_id, stat_date, poll_count, fail_count, new_count, dup_count,
                   created_at, updated_at
              FROM source_daily_stats
             WHERE stat_date >= ?
             ORDER BY source_id, stat_date
            """;

    private static final RowMapper<SourceDailyStats> STATS_ROW =
            (rs, rowNum) ->
                    new SourceDailyStats(
                            rs.getLong("id"),
                            rs.getLong("source_id"),
                            rs.getString("stat_date"),
                            rs.getLong("poll_count"),
                            rs.getLong("fail_count"),
                            rs.getLong("new_count"),
                            rs.getLong("dup_count"),
                            Instant.parse(rs.getString("created_at")),
                            Instant.parse(rs.getString("updated_at")));

    private final JdbcTemplate jdbcTemplate;

    public SourceDailyStatsRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void increment(
            long sourceId,
            String statDate,
            long pollDelta,
            long failDelta,
            long newDelta,
            long dupDelta) {
        Instant now = Instant.now();
        jdbcTemplate.update(
                UPSERT_SQL,
                sourceId,
                statDate,
                pollDelta,
                failDelta,
                newDelta,
                dupDelta,
                now.toString(),
                now.toString());
    }

    @Override
    public List<SourceDailyStats> findSince(String fromDate) {
        return jdbcTemplate.query(FIND_SINCE_SQL, STATS_ROW, fromDate);
    }
}
