package com.info.platform.infrastructure.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.EventType;
import com.info.platform.domain.analysis.HeatCalculator;
import com.info.platform.domain.analysis.HeatSnapshotRepository;
import com.info.platform.domain.analysis.HeatWindow;
import com.info.platform.domain.analysis.Importance;
import com.info.platform.domain.analysis.IndustryCategory;
import com.info.platform.domain.analysis.IndustryHeatSnapshot;
import com.info.platform.domain.analysis.L0Result;
import com.info.platform.domain.analysis.NewsAnalysis;
import com.info.platform.domain.analysis.NewsAnalysisRepository;
import com.info.platform.domain.feed.AdapterType;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.InfoSourceRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * HeatSnapshotRepository 集成测试（T123，方案 §4.5/§4.10）：62 行 UPSERT 幂等、榜单排序（0 分沉底）、窗口现算取数（PASS+DONE + 事件
 * join + 容器条目进窗）、行业下钻 news/events 游标分页、下钻计数与 HeatCalculator 现算对账（榜单=快照=现算 三面一致）。V23 表由 Flyway
 * 内存库建出。t123_ 前缀隔离清理。
 */
@SpringBootTest
@ActiveProfiles("test")
class HeatSnapshotRepositoryImplTest {

    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");

    @Autowired private HeatSnapshotRepository repository;

    @Autowired private NewsAnalysisRepository newsAnalysisRepository;

    @Autowired private InfoSourceRepository infoSourceRepository;

    @Autowired private JdbcTemplate jdbcTemplate;

    private Long sourceId;

    @BeforeEach
    void setUpSource() {
        InfoSource source =
                InfoSource.create(
                        "t123_heat",
                        "t123_heat",
                        "快讯",
                        AdapterType.RSS,
                        null,
                        "https://example.com/t123",
                        null,
                        15,
                        true,
                        false);
        infoSourceRepository.save(source);
        sourceId = source.getId();
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update(
                "DELETE FROM industry_heat_snapshot WHERE industry IN "
                        + "(SELECT DISTINCT main_category FROM news_analysis WHERE news_id IN "
                        + "(SELECT id FROM news_item WHERE source_id IN "
                        + "(SELECT id FROM info_source WHERE source_code LIKE 't123%')))");
        jdbcTemplate.update(
                "DELETE FROM event_item WHERE news_id IN "
                        + "(SELECT id FROM news_item WHERE source_id IN "
                        + "(SELECT id FROM info_source WHERE source_code LIKE 't123%'))");
        jdbcTemplate.update(
                "DELETE FROM news_analysis WHERE news_id IN "
                        + "(SELECT id FROM news_item WHERE source_id IN "
                        + "(SELECT id FROM info_source WHERE source_code LIKE 't123%'))");
        jdbcTemplate.update(
                "DELETE FROM news_item WHERE source_id IN "
                        + "(SELECT id FROM info_source WHERE source_code LIKE 't123%')");
        jdbcTemplate.update(
                "DELETE FROM source_poll_state WHERE source_id IN "
                        + "(SELECT id FROM info_source WHERE source_code LIKE 't123%')");
        jdbcTemplate.update("DELETE FROM info_source WHERE source_code LIKE 't123%'");
        jdbcTemplate.update("DELETE FROM industry_heat_snapshot");
    }

    private long classifiedNews(
            String title, String main, String publishedAtIso, L0Result l0, String eventJson) {
        jdbcTemplate.update(
                "INSERT INTO news_item (source_id, external_id, title, summary, url, published_at,"
                        + " fetched_at, fingerprint, status, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)",
                sourceId,
                "t123_" + System.nanoTime(),
                title,
                "摘要-" + title,
                "https://example.com/n/" + title.hashCode(),
                publishedAtIso,
                publishedAtIso,
                "fp-" + title.hashCode() + "-" + System.nanoTime(),
                publishedAtIso,
                publishedAtIso);
        long newsId =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM news_item WHERE title = ?", Long.class, title);
        newsAnalysisRepository.insertIgnoreBatch(
                List.of(NewsAnalysis.newForL0(newsId, l0, null, null)));
        if (l0 == L0Result.PASS) {
            newsAnalysisRepository.applyL1Result(
                    new NewsAnalysisRepository.L1Write(
                            newsId, main, null, null, 0.9, false, null, "v1.0", NOW));
        }
        if (eventJson != null) {
            jdbcTemplate.update(
                    "INSERT INTO event_item (news_id, event_type, summary, affected_industries,"
                            + " direction, importance, event_time, event_date, created_at, updated_at)"
                            + " VALUES (?, 'POLICY_RELEASE', '事件摘要', ?, 'BULLISH', 'HIGH', ?, '2026-09-22', ?, ?)",
                    newsId,
                    eventJson,
                    publishedAtIso,
                    publishedAtIso,
                    publishedAtIso);
        }
        return newsId;
    }

    @Test
    void upsertAll_sixtyTwoRowsIdempotentValueUpdate() {
        List<IndustryHeatSnapshot> first = allRows(1.0);
        List<IndustryHeatSnapshot> second = allRows(2.0);

        assertThat(repository.upsertAll(first)).isEqualTo(62);
        assertThat(repository.upsertAll(second)).isEqualTo(62);

        Integer total =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM industry_heat_snapshot", Integer.class);
        assertThat(total).isEqualTo(62); // UNIQUE(industry, window_type) 收敛不重复
        Double bankH24 =
                jdbcTemplate.queryForObject(
                        "SELECT heat_score FROM industry_heat_snapshot WHERE industry = '银行' AND window_type = 'H24'",
                        Double.class);
        assertThat(bankH24).isEqualTo(2.0); // 后写覆盖当前值
    }

    private static List<IndustryHeatSnapshot> allRows(double score) {
        List<IndustryHeatSnapshot> rows = new ArrayList<>();
        for (String industry : IndustryCategory.SW_INDUSTRIES) {
            for (HeatWindow window : HeatWindow.values()) {
                rows.add(
                        IndustryHeatSnapshot.create(
                                industry,
                                window,
                                score,
                                score,
                                1,
                                0,
                                "heat-v1:k1=10;imp=1.0/0.5/0.25;hl=12h|48h",
                                NOW));
            }
        }
        return rows;
    }

    @Test
    void findBoard_ordersHeatDescZeroSinksLast() {
        List<IndustryHeatSnapshot> rows = allRows(0.0);
        int bankH24Index = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).getWindow() == HeatWindow.H24
                    && rows.get(i).getIndustry().equals("银行")) {
                bankH24Index = i;
                break;
            }
        }
        rows.set(
                bankH24Index,
                IndustryHeatSnapshot.create("银行", HeatWindow.H24, 30.0, 0, 10, 2, "heat-v1", NOW));
        repository.upsertAll(rows);

        List<IndustryHeatSnapshot> board = repository.findBoard(HeatWindow.H24);

        assertThat(board).hasSize(31);
        assertThat(board.get(0).getIndustry()).isEqualTo("银行");
        assertThat(board.get(30).getHeatScore()).isZero(); // 0 分沉底
        // D7 窗独立（不串窗）
        assertThat(repository.findBoard(HeatWindow.D7)).hasSize(31);
    }

    @Test
    void findWindowItems_passDoneWithEventJoin_containerIncluded() {
        classifiedNews("窗口内银行条目", "银行", "2026-09-22T07:00:00Z", L0Result.PASS, null);
        classifiedNews("窗口内事件条目", "宏观", "2026-09-22T07:30:00Z", L0Result.PASS, "[\"银行\",\"房地产\"]");
        classifiedNews("噪音条目不进窗", "银行", "2026-09-22T07:00:00Z", L0Result.NOISE, null);
        classifiedNews("窗口外条目", "银行", "2026-09-20T07:00:00Z", L0Result.PASS, null);

        List<HeatSnapshotRepository.WindowItem> items =
                repository.findWindowItems(
                        NOW.minus(java.time.Duration.ofHours(24)).toString(), NOW.toString());

        assertThat(items).hasSize(2); // NOISE 排除（NOISE 恒 PENDING 无 main）、窗口外排除
        HeatSnapshotRepository.WindowItem plain =
                items.stream().filter(i -> i.mainCategory().equals("银行")).findFirst().orElseThrow();
        assertThat(plain.eventImportance()).isNull();
        HeatSnapshotRepository.WindowItem container =
                items.stream().filter(i -> i.mainCategory().equals("宏观")).findFirst().orElseThrow();
        assertThat(container.eventImportance()).isEqualTo(Importance.HIGH);
        assertThat(container.affectedIndustries()).containsExactly("银行", "房地产");
    }

    @Test
    void drillDown_newsPaginationAndReconciliation() {
        // id 自增序 = 建行序（一 < 二 < 三）；清单契约 = news_id DESC（与事件流 id DESC 基线一致）
        long first = classifiedNews("银行下钻条目一", "银行", "2026-09-22T05:00:00Z", L0Result.PASS, null);
        long second =
                classifiedNews("银行下钻条目二", "银行", "2026-09-22T06:00:00Z", L0Result.PASS, "[\"银行\"]");
        long third = classifiedNews("银行下钻条目三", "银行", "2026-09-22T07:00:00Z", L0Result.PASS, null);
        classifiedNews("他业条目不入钻", "钢铁", "2026-09-22T07:00:00Z", L0Result.PASS, null);

        String from = NOW.minus(java.time.Duration.ofHours(24)).toString();
        List<HeatSnapshotRepository.IndustryNewsItem> page1 =
                repository.findIndustryNewsItems("银行", from, NOW.toString(), null, 2);
        long total = repository.countIndustryNewsItems("银行", from, NOW.toString());

        assertThat(page1)
                .extracting(HeatSnapshotRepository.IndustryNewsItem::newsId)
                .containsExactly(third, second); // id DESC
        assertThat(total).isEqualTo(3); // 对账 = 榜单 news_count 口径
        assertThat(page1.get(1).hasEvent()).isTrue(); // L2 事件标记（条目二带事件）
        assertThat(page1.get(0).hasEvent()).isFalse();
        List<HeatSnapshotRepository.IndustryNewsItem> page2 =
                repository.findIndustryNewsItems("银行", from, NOW.toString(), second, 2);
        assertThat(page2)
                .extracting(HeatSnapshotRepository.IndustryNewsItem::newsId)
                .containsExactly(first);
        org.assertj.core.api.Assertions.assertThat(first).isLessThan(second).isLessThan(third);
    }

    @Test
    void drillDown_eventsScopeAndReconciliation() {
        classifiedNews("降准条目", "宏观", "2026-09-22T07:00:00Z", L0Result.PASS, "[\"银行\",\"房地产\"]");
        classifiedNews("银行业绩条目", "银行", "2026-09-22T06:00:00Z", L0Result.PASS, "[\"银行\"]");
        classifiedNews("无事件条目", "银行", "2026-09-22T05:00:00Z", L0Result.PASS, null);
        classifiedNews("窗口外事件", "宏观", "2026-09-19T07:00:00Z", L0Result.PASS, "[\"银行\"]");

        String from = NOW.minus(java.time.Duration.ofHours(24)).toString();
        List<HeatSnapshotRepository.IndustryEventItem> events =
                repository.findIndustryEventItems("银行", from, NOW.toString(), null, 20);
        long total = repository.countIndustryEventItems("银行", from, NOW.toString());

        assertThat(events).hasSize(2); // affected ∋ 银行（含 main=银行直接命中——与事件流同口径）
        assertThat(total).isEqualTo(2);
        assertThat(events.get(0).eventType()).isEqualTo(EventType.POLICY_RELEASE);
        assertThat(events.get(0).direction()).isEqualTo(Direction.BULLISH);
        assertThat(events.get(0).importance()).isEqualTo(Importance.HIGH);
    }

    @Test
    void reconciliation_computedCountsEqualDrillDownCounts() {
        // §4.10「榜单=快照=现算」与「下钻=榜单」双对账：HeatCalculator(窗口现算) 的计数 == 下钻计数 SQL
        classifiedNews("对账银行条目", "银行", "2026-09-22T07:00:00Z", L0Result.PASS, null);
        classifiedNews("对账事件条目", "宏观", "2026-09-22T07:30:00Z", L0Result.PASS, "[\"银行\",\"房地产\"]");
        classifiedNews("对账钢铁条目", "钢铁", "2026-09-22T06:00:00Z", L0Result.PASS, "[\"钢铁\"]");

        String from = NOW.minus(java.time.Duration.ofHours(24)).toString();
        List<HeatCalculator.HeatItem> heatItems =
                repository.findWindowItems(from, NOW.toString()).stream()
                        .map(
                                w ->
                                        new HeatCalculator.HeatItem(
                                                w.mainCategory(),
                                                w.publishedAt(),
                                                w.eventImportance(),
                                                w.affectedIndustries()))
                        .toList();
        Map<String, HeatCalculator.IndustryHeat> computed =
                HeatCalculator.compute(
                        heatItems,
                        HeatCalculator.HeatParams.defaults(),
                        NOW,
                        HeatWindow.H24.length());

        assertThat(computed.get("银行").newsCount())
                .isEqualTo(repository.countIndustryNewsItems("银行", from, NOW.toString()));
        assertThat(computed.get("银行").eventCount())
                .isEqualTo(repository.countIndustryEventItems("银行", from, NOW.toString()));
        assertThat(computed.get("房地产").newsCount())
                .isEqualTo(repository.countIndustryNewsItems("房地产", from, NOW.toString()));
        assertThat(computed.get("房地产").eventCount()).isEqualTo(1L);
        assertThat(computed.get("钢铁").newsCount()).isEqualTo(1L);
        assertThat(computed.get("钢铁").eventCount())
                .isEqualTo(repository.countIndustryEventItems("钢铁", from, NOW.toString()));
    }
}
