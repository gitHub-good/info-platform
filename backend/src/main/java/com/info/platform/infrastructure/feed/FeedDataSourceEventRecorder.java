package com.info.platform.infrastructure.feed;

import com.info.platform.domain.feed.FeedEventRecorder;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 资讯源旁路事件记录器（M13 T103，方案 §4.8）：失败/静默事件沿用 {@code data_source_event}（不扩 event_type）， {@code
 * source_code = "info:{source_code}"} 前缀命名与六源域键空间区隔（ADR-0038 边界）。
 *
 * <p>节流归调用方（FeedIngestService：首败与每 10 次，ADR-0040）；本件只负责落库且<b>永不抛异常</b>（可观测不能拖垮主链路，
 * DataSourceEventRecorder 同契约）。
 */
@Component
public class FeedDataSourceEventRecorder implements FeedEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(FeedDataSourceEventRecorder.class);

    /** data_source_event.event_type=3 错误（既有枚举语义：取数异常）。 */
    private static final int EVENT_TYPE_ERROR = 3;

    private static final String INSERT_SQL =
            """
            INSERT INTO data_source_event
              (source_code, event_type, subject_id, detail, created_at, updated_at)
            VALUES (?, ?, NULL, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public FeedDataSourceEventRecorder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 失败事件（source_code = info:{sourceCode}）；落库失败仅记 ERROR，不上抛。 */
    @Override
    public void recordFailure(String sourceCode, int consecutiveFailures, String detail) {
        try {
            String now = Instant.now().toString();
            jdbcTemplate.update(
                    INSERT_SQL,
                    "info:" + sourceCode,
                    EVENT_TYPE_ERROR,
                    "consecutiveFailures=" + consecutiveFailures + "; " + detail,
                    now,
                    now);
        } catch (Exception e) {
            log.error("记录资讯源失败事件失败 sourceCode={} failures={}", sourceCode, consecutiveFailures, e);
        }
    }
}
