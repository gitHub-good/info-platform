package com.info.platform.infrastructure.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.analysis.DailyReportRepository;
import com.info.platform.domain.analysis.IndustryDailyReport;
import com.info.platform.domain.analysis.NewsAnalysis;
import com.info.platform.domain.analysis.NewsAnalysisRepository;
import com.info.platform.domain.analysis.ReportStatus;
import com.info.platform.domain.feed.AdapterType;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.InfoSourceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * DailyReportRepository 集成测试（T124，方案 §4.5/§4.10）：UPSERT by report_date（FAILED 重生成整行替换收敛）、列表
 * report_date DESC 游标分页、日窗各行业计数（PASS+DONE 分组、含容器、NOISE/NEAR_DUP/未归类不进统计）、事件精选（重要度降序 id 升序 +
 * affected/figures 解析）。V23 表由 Flyway 内存库建出。t124_ 前缀隔离清理。
 */
@SpringBootTest
@ActiveProfiles("test")
class DailyReportRepositoryImplTest {

    private static final Instant NOW = Instant.parse("2026-09-23T00:00:00Z");

    @Autowired private DailyReportRepository repository;

    @Autowired private NewsAnalysisRepository newsAnalysisRepository;

    @Autowired private InfoSourceRepository infoSourceRepository;

    @Autowired private JdbcTemplate jdbcTemplate;

    private Long sourceId;

    @BeforeEach
    void setUpSource() {
        InfoSource source =
                InfoSource.create(
                        "t124_report",
                        "t124_report",
                        "快讯",
                        AdapterType.RSS,
                        null,
                        "https://example.com/t124",
                        null,
                        15,
                        true,
                        false);
        infoSourceRepository.save(source);
        sourceId = source.getId();
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM industry_daily_report WHERE report_date LIKE '2026-09-%'");
        jdbcTemplate.update(
                "DELETE FROM event_item WHERE news_id IN "
                        + "(SELECT id FROM news_item WHERE source_id IN "
                        + "(SELECT id FROM info_source WHERE source_code LIKE 't124%'))");
        jdbcTemplate.update(
                "DELETE FROM news_analysis WHERE news_id IN "
                        + "(SELECT id FROM news_item WHERE source_id IN "
                        + "(SELECT id FROM info_source WHERE source_code LIKE 't124%'))");
        jdbcTemplate.update(
                "DELETE FROM news_item WHERE source_id IN "
                        + "(SELECT id FROM info_source WHERE source_code LIKE 't124%')");
        jdbcTemplate.update(
                "DELETE FROM source_poll_state WHERE source_id IN "
                        + "(SELECT id FROM info_source WHERE source_code LIKE 't124%')");
        jdbcTemplate.update("DELETE FROM info_source WHERE source_code LIKE 't124%'");
    }

    private long news(String title, String publishedAtIso) {
        jdbcTemplate.update(
                "INSERT INTO news_item (source_id, external_id, title, summary, url, published_at,"
                        + " fetched_at, fingerprint, status, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)",
                sourceId,
                "t124_" + System.nanoTime(),
                title,
                "摘要-" + title,
                "https://example.com/n/" + title.hashCode(),
                publishedAtIso,
                publishedAtIso,
                "fp-" + title.hashCode() + "-" + System.nanoTime(),
                publishedAtIso,
                publishedAtIso);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM news_item WHERE title = ?", Long.class, title);
    }

    private void classified(String title, String main, String publishedAtIso) {
        long newsId = news(title, publishedAtIso);
        newsAnalysisRepository.insertIgnoreBatch(
                List.of(
                        NewsAnalysis.newForL0(
                                newsId,
                                com.info.platform.domain.analysis.L0Result.PASS,
                                null,
                                null)));
        newsAnalysisRepository.applyL1Result(
                new NewsAnalysisRepository.L1Write(
                        newsId, main, null, null, 0.9, false, null, "v1.0", NOW));
    }

    private void noise(String title, String publishedAtIso) {
        long newsId = news(title, publishedAtIso);
        newsAnalysisRepository.insertIgnoreBatch(
                List.of(
                        NewsAnalysis.newForL0(
                                newsId,
                                com.info.platform.domain.analysis.L0Result.NOISE,
                                null,
                                "广告")));
    }

    private void pending(String title, String publishedAtIso) {
        long newsId = news(title, publishedAtIso);
        newsAnalysisRepository.insertIgnoreBatch(
                List.of(
                        NewsAnalysis.newForL0(
                                newsId,
                                com.info.platform.domain.analysis.L0Result.PASS,
                                null,
                                null)));
    }

    private void event(
            long newsId, String importance, String affectedJson, String eventDate, String summary) {
        jdbcTemplate.update(
                "INSERT INTO event_item (news_id, event_type, summary, affected_industries,"
                        + " direction, importance, key_figures, quote, event_date, created_at, updated_at)"
                        + " VALUES (?, 'POLICY_RELEASE', ?, ?, 'BULLISH', ?, ?, '引用', ?, ?, ?)",
                newsId,
                summary,
                affectedJson,
                importance,
                "[{\"label\":\"金额\",\"value\":\"100亿\",\"unit\":\"\"}]",
                eventDate,
                NOW.toString(),
                NOW.toString());
    }

    @Test
    void upsert_replacesFailedRow_convergesToOne() {
        // Arrange：FAILED 行先落库
        repository.upsert(IndustryDailyReport.failed("2026-09-22", "llm 超时", NOW));

        // Act：重生成 SUCCESS 整行替换
        IndustryDailyReport regenerated =
                repository.upsert(
                        IndustryDailyReport.success(
                                "2026-09-22", "{\"summary\":\"s\"}", "[]", null, "v1.0", "b", NOW));

        // Assert：UNIQUE(report_date) 收敛一行，状态翻转（ON CONFLICT DO UPDATE 不覆写 created_at）
        Integer total =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM industry_daily_report WHERE report_date = '2026-09-22'",
                        Integer.class);
        assertThat(total).isEqualTo(1);
        Optional<IndustryDailyReport> reloaded = repository.findByReportDate("2026-09-22");
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getStatus()).isEqualTo(ReportStatus.SUCCESS);
        assertThat(regenerated.getStatus()).isEqualTo(ReportStatus.SUCCESS);
        assertThat(reloaded.get().getContent()).contains("summary");
    }

    @Test
    void findByReportDate_missing_returnsEmpty() {
        assertThat(repository.findByReportDate("2030-01-01")).isEmpty();
    }

    @Test
    void findPage_ordersReportDateDesc_withCursor() {
        for (int day = 20; day <= 23; day++) {
            repository.upsert(
                    IndustryDailyReport.success(
                            "2026-09-%02d".formatted(day), "{}", "[]", null, "v1.0", "b", NOW));
        }

        List<IndustryDailyReport> firstPage = repository.findPage(null, 3);
        List<IndustryDailyReport> secondPage = repository.findPage(firstPage.get(2).getId(), 3);

        assertThat(firstPage)
                .map(IndustryDailyReport::getReportDate)
                .containsExactly("2026-09-23", "2026-09-22", "2026-09-21");
        assertThat(secondPage)
                .map(IndustryDailyReport::getReportDate)
                .containsExactly("2026-09-20");
    }

    @Test
    void countNewsByIndustry_groupsPassDone_includesContainers_excludesNoisePending() {
        // Arrange：昨日窗（UTC [2026-09-21T16:00, 2026-09-22T16:00) = 上海 09-22 全天）
        String inWindow = "2026-09-22T02:00:00Z";
        String outOfWindow = "2026-09-22T17:00:00Z"; // 上海 09-23 凌晨（次日窗）
        classified("银行新闻1", "银行", inWindow);
        classified("银行新闻2", "银行", inWindow);
        classified("电子新闻", "电子", inWindow);
        classified("宏观新闻", "宏观", inWindow); // 容器计数
        classified("窗外新闻", "银行", outOfWindow);
        noise("广告帖", inWindow); // NOISE 不进统计
        pending("未归类", inWindow); // PENDING 不进统计

        List<DailyReportRepository.IndustryCount> counts =
                repository.countNewsByIndustry("2026-09-21T16:00:00Z", "2026-09-22T16:00:00Z");

        // Assert：按计数降序；窗内 DONE 条目全计入（含容器），窗外/NOISE/未归类不计
        assertThat(counts)
                .containsExactly(
                        new DailyReportRepository.IndustryCount("银行", 2),
                        new DailyReportRepository.IndustryCount("宏观", 1),
                        new DailyReportRepository.IndustryCount("电子", 1));
    }

    @Test
    void findEventsByDate_ordersImportanceDescThenIdAsc_parsesIndustriesAndFigures() {
        long low = newsIdOf("低重要事件载体", "2026-09-22T02:00:00Z");
        long high = newsIdOf("高重要事件载体", "2026-09-22T03:00:00Z");
        long medium = newsIdOf("中重要事件载体", "2026-09-22T04:00:00Z");
        event(low, "LOW", "[\"钢铁\"]", "2026-09-22", "低事件");
        event(high, "HIGH", "[\"银行\",\"房地产\"]", "2026-09-22", "高事件");
        event(medium, "MEDIUM", "[\"电子\"]", "2026-09-22", "中事件");
        // 窗外日不计
        long otherDay = newsIdOf("他日事件载体", "2026-09-21T02:00:00Z");
        event(otherDay, "HIGH", "[\"汽车\"]", "2026-09-21", "他日事件");

        List<DailyReportRepository.ReportEvent> events =
                repository.findEventsByDate("2026-09-22", 500);

        assertThat(events).hasSize(3);
        assertThat(events)
                .map(DailyReportRepository.ReportEvent::summary)
                .containsExactly("高事件", "中事件", "低事件");
        assertThat(events.get(0).industries()).containsExactly("银行", "房地产");
        assertThat(events.get(0).figuresJson()).contains("100亿");
        assertThat(events.get(0).quote()).isEqualTo("引用");
        // cap 生效
        assertThat(repository.findEventsByDate("2026-09-22", 2)).hasSize(2);
    }

    private long newsIdOf(String title, String publishedAtIso) {
        return news(title, publishedAtIso);
    }
}
