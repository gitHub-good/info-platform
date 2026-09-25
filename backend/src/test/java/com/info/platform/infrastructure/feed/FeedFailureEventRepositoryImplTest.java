package com.info.platform.infrastructure.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.feed.FeedFailureEventRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * FeedFailureEventRepository 集成测试（T114）：大盘失败列表读侧——只取 event_type=3 错误、 {@code info:} 前缀键空间（六源域事件不串）、
 * 前缀剥离、时间倒序与条数上限。测试行 {@code info:t114f_} 前缀隔离清理。
 */
@SpringBootTest
@ActiveProfiles("test")
class FeedFailureEventRepositoryImplTest {

    @Autowired private FeedFailureEventRepository repository;

    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM data_source_event WHERE source_code LIKE 'info:t114f_%'");
    }

    private void insertEvent(String sourceCode, int eventType, String detail, String createdAt) {
        jdbcTemplate.update(
                "INSERT INTO data_source_event (source_code, event_type, subject_id, detail,"
                        + " created_at, updated_at) VALUES (?, ?, NULL, ?, ?, ?)",
                sourceCode,
                eventType,
                detail,
                createdAt,
                createdAt);
    }

    @Test
    void findRecent_returnsInfoFailuresOnly_timeDesc_prefixStripped() {
        insertEvent("info:t114f_b", 3, "consecutiveFailures=2; 超时", "2026-09-22T03:00:00Z");
        insertEvent("QUOTE", 3, "六源域错误不入大盘失败列表", "2026-09-22T04:00:00Z");
        insertEvent("info:t114f_a", 3, "consecutiveFailures=1; DNS 解析失败", "2026-09-22T05:00:00Z");
        insertEvent("info:t114f_a", 1, "缺失事件（type=1）不取", "2026-09-22T06:00:00Z");

        List<com.info.platform.domain.feed.FeedFailureEvent> recent = repository.findRecent(20);

        assertThat(recent)
                .filteredOn(e -> e.sourceCode().startsWith("t114f_"))
                .extracting(com.info.platform.domain.feed.FeedFailureEvent::sourceCode)
                .containsExactly("t114f_a", "t114f_b"); // 时间倒序；前缀已剥离
        assertThat(recent)
                .filteredOn(e -> e.sourceCode().startsWith("t114f_"))
                .allSatisfy(e -> assertThat(e.occurredAt()).isNotNull());
    }

    @Test
    void findRecent_respectsLimit() {
        for (int i = 0; i < 5; i++) {
            insertEvent(
                    "info:t114f_l" + i,
                    3,
                    "consecutiveFailures=1; 错误" + i,
                    String.format("2026-09-22T0%d:00:00Z", i));
        }

        assertThat(repository.findRecent(3))
                .extracting(com.info.platform.domain.feed.FeedFailureEvent::sourceCode)
                .containsExactly("t114f_l4", "t114f_l3", "t114f_l2");
    }
}
