package com.info.platform.infrastructure.feed;

import com.info.platform.domain.feed.SourcePollState;
import com.info.platform.domain.feed.SourcePollStateRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * {@link SourcePollStateRepository} 端口的 SQLite 实现（M13 T100）。
 *
 * <p>高频运行态表（调度器每轮写）：走 {@link JdbcTemplate} 手写 SQL——初始化需 {@code INSERT OR IGNORE} 幂等， 更新为全字段覆盖（轮次推进由
 * {@code FeedIngestService} 在条目同事务内调用，事务边界归调用方）。 时间戳 ISO-8601 文本，空值列可 NULL。
 */
@Repository
public class SourcePollStateRepositoryImpl implements SourcePollStateRepository {

    private static final String INSERT_IGNORE_SQL =
            """
            INSERT OR IGNORE INTO source_poll_state
              (source_id, next_due_at, consecutive_failures, created_at, updated_at)
            VALUES (?, ?, 0, ?, ?)
            """;

    private static final String UPDATE_SQL =
            """
            UPDATE source_poll_state
               SET last_attempt_at = ?, last_success_at = ?, next_due_at = ?,
                   cursor_value = ?, cursor_updated_at = ?, consecutive_failures = ?,
                   backoff_until = ?, last_duration_millis = ?, last_round_detail = ?,
                   last_error = ?, updated_at = ?
             WHERE source_id = ?
            """;

    private static final String FIND_SQL =
            """
            SELECT source_id, last_attempt_at, last_success_at, next_due_at, cursor_value,
                   cursor_updated_at, consecutive_failures, backoff_until, last_duration_millis,
                   last_round_detail, last_error, created_at, updated_at
              FROM source_poll_state
             WHERE source_id = ?
            """;

    private static final RowMapper<SourcePollState> STATE_ROW =
            (rs, rowNum) ->
                    new SourcePollState(
                            rs.getLong("source_id"),
                            instantOrNull(rs, "last_attempt_at"),
                            instantOrNull(rs, "last_success_at"),
                            Instant.parse(rs.getString("next_due_at")),
                            stringOrNull(rs, "cursor_value"),
                            instantOrNull(rs, "cursor_updated_at"),
                            rs.getInt("consecutive_failures"),
                            instantOrNull(rs, "backoff_until"),
                            longOrNull(rs, "last_duration_millis"),
                            stringOrNull(rs, "last_round_detail"),
                            stringOrNull(rs, "last_error"),
                            Instant.parse(rs.getString("created_at")),
                            Instant.parse(rs.getString("updated_at")));

    private final JdbcTemplate jdbcTemplate;

    public SourcePollStateRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<SourcePollState> findBySourceId(Long sourceId) {
        List<SourcePollState> found = jdbcTemplate.query(FIND_SQL, STATE_ROW, sourceId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public boolean insertIfAbsent(Long sourceId, Instant nextDueAt, Instant now) {
        return jdbcTemplate.update(
                        INSERT_IGNORE_SQL, sourceId, nextDueAt.toString(), now.toString(),
                        now.toString())
                > 0;
    }

    @Override
    public boolean update(SourcePollState state) {
        return jdbcTemplate.update(
                        UPDATE_SQL,
                        text(state.lastAttemptAt()),
                        text(state.lastSuccessAt()),
                        state.nextDueAt().toString(),
                        state.cursorValue(),
                        text(state.cursorUpdatedAt()),
                        state.consecutiveFailures(),
                        text(state.backoffUntil()),
                        state.lastDurationMillis(),
                        state.lastRoundDetail(),
                        state.lastError(),
                        state.updatedAt().toString(),
                        state.sourceId())
                > 0;
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
        String text = rs.getString(column);
        return text == null ? null : Instant.parse(text);
    }

    private static String stringOrNull(ResultSet rs, String column) throws SQLException {
        String text = rs.getString(column);
        return rs.wasNull() || text == null ? null : text;
    }

    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
