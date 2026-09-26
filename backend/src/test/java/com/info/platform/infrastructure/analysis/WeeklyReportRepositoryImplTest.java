package com.info.platform.infrastructure.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.analysis.DailyReportRepository;
import com.info.platform.domain.analysis.IndustryWeeklyReport;
import com.info.platform.domain.analysis.ReportStatus;
import com.info.platform.domain.analysis.WeeklyReportRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 周报仓储集成测试（M17 T145，V29 {@code industry_weekly_report}）：UNIQUE(week_start) UPSERT（FAILED
 * 重生成/幂等收敛一行）、 week_start DESC 游标分页、周窗事件区间取数（主键归并——event_item 一行一主线）。t145w_ 前缀数据隔离清理。
 */
@SpringBootTest
@ActiveProfiles("test")
class WeeklyReportRepositoryImplTest {

    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");

    @Autowired private WeeklyReportRepository repository;

    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update(
                "DELETE FROM industry_weekly_report WHERE week_start IN ('2027-09-20','2027-09-13')");
        jdbcTemplate.update("DELETE FROM event_item WHERE news_id BETWEEN 9500 AND 9599");
        jdbcTemplate.update("DELETE FROM news_item WHERE id BETWEEN 9500 AND 9599");
        jdbcTemplate.update("DELETE FROM info_source WHERE id = 9501");
    }

    @Test
    void upsert_thenFindByWeekStart_roundTrip() {
        repository.upsert(
                IndustryWeeklyReport.success(
                        "2027-09-20", "{\"summary\":\"ok\"}", "[]", null, "v1.0", "trend-v1", NOW));

        Optional<IndustryWeeklyReport> loaded = repository.findByWeekStart("2027-09-20");

        assertThat(loaded).isPresent();
        assertThat(loaded.get().getStatus()).isEqualTo(ReportStatus.SUCCESS);
        assertThat(loaded.get().getContent()).contains("summary");

        // FAILED 重生成整体替换收敛一行
        repository.upsert(
                IndustryWeeklyReport.success(
                        "2027-09-20",
                        "{\"summary\":\"regenerated\"}",
                        "[]",
                        null,
                        "v1.1",
                        "trend-v1",
                        NOW));
        assertThat(repository.findByWeekStart("2027-09-20")).isPresent();
        Integer rows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM industry_weekly_report WHERE week_start = '2027-09-20'",
                        Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void findPage_weekStartDescCursor() {
        repository.upsert(IndustryWeeklyReport.failed("2027-09-13", "err", NOW));
        repository.upsert(
                IndustryWeeklyReport.success("2027-09-20", "{}", "[]", null, "v1.0", "b", NOW));

        List<IndustryWeeklyReport> page = repository.findPage(null, 10);

        assertThat(page.stream().map(IndustryWeeklyReport::getWeekStart))
                .contains("2027-09-20", "2027-09-13");
        assertThat(repository.findPage(null, 10).get(0).getWeekStart()).isEqualTo("2027-09-20");
    }

    @Test
    void findEventsBetween_primaryKeyMergedWindow() {
        seedEvent(9501, "2026-09-21");
        seedEvent(9502, "2026-09-25");
        seedEvent(9503, "2026-09-28"); // 窗外

        List<DailyReportRepository.ReportEvent> events =
                repository.findEventsBetween("2026-09-21", "2026-09-27", 100);

        assertThat(events.stream().map(DailyReportRepository.ReportEvent::eventId))
                .as("周窗 [from, to] 事件主键归并（跨日去重天然成立，一行一主线）")
                .containsExactlyInAnyOrder(9501L, 9502L);
    }

    private void seedEvent(long eventId, String eventDate) {
        jdbcTemplate.update(
                """
                INSERT OR IGNORE INTO info_source (id, source_code, name, category, adapter_type, endpoint,
                                        created_at, updated_at)
                VALUES (9501, 't145w_src', 'T145W源', '快讯', 'rss', 'https://example.com/rss',
                        '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z')
                """);
        jdbcTemplate.update(
                """
                INSERT OR IGNORE INTO news_item (id, source_id, title, published_at, fetched_at, fingerprint,
                                       created_at, updated_at)
                VALUES (?, 9501, 'T145W原文', ?, ?, ?, ?, ?)
                """,
                eventId,
                eventDate + "T02:00:00Z",
                eventDate + "T02:01:00Z",
                "fp-t145w-" + eventId,
                eventDate + "T02:01:00Z",
                eventDate + "T02:01:00Z");
        jdbcTemplate.update(
                """
                INSERT INTO event_item (id, news_id, event_type, summary, affected_industries,
                                        direction, importance, event_date, created_at, updated_at)
                VALUES (?, ?, 'POLICY_RELEASE', 'T145W事件', '["银行"]', 'BULLISH', 'HIGH', ?, ?, ?)
                """,
                eventId,
                eventId,
                eventDate,
                eventDate + "T02:02:00Z",
                eventDate + "T02:02:00Z");
    }
}
