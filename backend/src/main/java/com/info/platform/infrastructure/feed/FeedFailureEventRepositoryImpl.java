package com.info.platform.infrastructure.feed;

import com.info.platform.domain.feed.FeedFailureEvent;
import com.info.platform.domain.feed.FeedFailureEventRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * {@link FeedFailureEventRepository} 端口的 SQLite 实现（M14 T114）：读 {@code data_source_event} 旁路事件（
 * event_type=3 错误、{@code info:{sourceCode}} 前缀键空间），剥前缀返回裸 sourceCode 供大盘失败列表对源维表回连。
 */
@Repository
public class FeedFailureEventRepositoryImpl implements FeedFailureEventRepository {

    /** data_source_event.event_type=3 错误（与写侧 FeedDataSourceEventRecorder 同枚举语义）。 */
    private static final int EVENT_TYPE_ERROR = 3;

    /** info 源键前缀（与写侧 FeedDataSourceEventRecorder 同常量口径）。 */
    private static final String SOURCE_CODE_PREFIX = "info:";

    private static final String FIND_RECENT_SQL =
            """
            SELECT source_code, detail, created_at
              FROM data_source_event
             WHERE event_type = ? AND source_code LIKE 'info:%'
             ORDER BY created_at DESC, id DESC
             LIMIT ?
            """;

    private static final RowMapper<FeedFailureEvent> EVENT_ROW =
            (rs, rowNum) ->
                    new FeedFailureEvent(
                            stripPrefix(rs.getString("source_code")),
                            Instant.parse(rs.getString("created_at")),
                            rs.getString("detail"));

    private final JdbcTemplate jdbcTemplate;

    public FeedFailureEventRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<FeedFailureEvent> findRecent(int limit) {
        return jdbcTemplate.query(FIND_RECENT_SQL, EVENT_ROW, EVENT_TYPE_ERROR, limit);
    }

    private static String stripPrefix(String sourceCode) {
        return sourceCode.startsWith(SOURCE_CODE_PREFIX)
                ? sourceCode.substring(SOURCE_CODE_PREFIX.length())
                : sourceCode;
    }
}
