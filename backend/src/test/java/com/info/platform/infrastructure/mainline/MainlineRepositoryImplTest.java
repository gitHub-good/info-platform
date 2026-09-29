package com.info.platform.infrastructure.mainline;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.mainline.MainlineRepository;
import com.info.platform.domain.mainline.MainlineRepository.EventWeightRow;
import com.info.platform.domain.mainline.MainlineRepository.MainlineBatchRow;
import com.info.platform.domain.mainline.MainlineRepository.MainlineRankRow;
import com.info.platform.domain.mainline.MainlineRepository.MainlineVersion;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * MainlineRepositoryImpl 集成测试（M27 T243，V35 表②③；M29 T255 market 维）：追加式版本化（同日同市场 version 递增不覆盖；跨市场同日
 * 独立版本共存）/ findLatest 市场内语义 / findLatestAnyDate 市场内回退 / 事件密度 json_each 加权 SQL（HIGH×2/MEDIUM×1/LOW×0
 * 对账面 + l1_market 分桶消歧）。夹具远未来日期隔离，逐轮物理清理。
 */
@SpringBootTest
@ActiveProfiles("test")
class MainlineRepositoryImplTest {

    private static final String DATE = "2099-12-31";

    @Autowired private MainlineRepository repository;

    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM industry_mainline WHERE rank_date >= '2099-01-01'");
        jdbcTemplate.update("DELETE FROM industry_mainline_batch WHERE rank_date >= '2099-01-01'");
        jdbcTemplate.update("DELETE FROM event_item WHERE event_date >= '2099-01-01'");
        jdbcTemplate.update("DELETE FROM news_analysis WHERE news_id >= 9000");
        jdbcTemplate.update("DELETE FROM news_item WHERE id >= 9000");
        jdbcTemplate.update("DELETE FROM info_source WHERE source_code LIKE 't255_%'");
    }

    /** 装载 news_item + news_analysis（指定 l1_market）——事件密度 l1_market join 的原料。 */
    private void seedAnalyzedNews(long newsId, String l1Market) {
        jdbcTemplate.update(
                "INSERT INTO info_source (source_code, name, category, adapter_type, endpoint,"
                        + " interval_minutes, enabled, is_preset, deleted, created_at, updated_at)"
                        + " VALUES ('t255_src_"
                        + newsId
                        + "', 't255', '快讯', 'rss',"
                        + " 'https://example.com/t255', 15, 1, 0, 0, '2026-09-30T10:00:00Z',"
                        + " '2026-09-30T10:00:00Z')");
        Long sourceId =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM info_source WHERE source_code = ?",
                        Long.class,
                        "t255_src_" + newsId);
        jdbcTemplate.update(
                "INSERT INTO news_item (id, source_id, external_id, title, summary, url,"
                        + " published_at, fetched_at, fingerprint, status, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, NULL, NULL, '2026-09-30T10:00:00Z',"
                        + " '2026-09-30T10:00:00Z', ?, 1, '2026-09-30T10:00:00Z',"
                        + " '2026-09-30T10:00:00Z')",
                newsId,
                sourceId,
                "t255_" + newsId,
                "t255条目" + newsId,
                "fp-t255-" + newsId);
        jdbcTemplate.update(
                "INSERT INTO news_analysis (news_id, l0_result, l1_status, main_category, l1_market,"
                        + " created_at, updated_at) VALUES (?, 'PASS', 'DONE', '银行', ?,"
                        + " '2026-09-30T10:00:00Z', '2026-09-30T10:00:00Z')",
                newsId,
                l1Market);
    }

    private static MainlineRankRow rank(String date, int version, int rankNo, String industry) {
        return rank(Market.A_SHARE, date, version, rankNo, industry);
    }

    private static MainlineRankRow rank(
            Market market, String date, int version, int rankNo, String industry) {
        return new MainlineRankRow(
                market,
                date,
                version,
                rankNo,
                industry,
                90d - rankNo,
                "{\"price\":{\"rank\":1,\"score\":95.0,\"raw\":2.5}}",
                3,
                2,
                "NONE",
                "[]",
                "mainline-v1:...",
                "2099-12-31T10:30:00Z");
    }

    private static MainlineBatchRow batch(String date, int version, boolean degraded) {
        return batch(Market.A_SHARE, date, version, degraded);
    }

    private static MainlineBatchRow batch(
            Market market, String date, int version, boolean degraded) {
        return new MainlineBatchRow(
                market,
                date,
                version,
                "DAILY",
                date,
                "{\"industries\":31,\"persistPass\":5}",
                degraded,
                degraded ? "SNAPSHOT_STALE" : null,
                "mainline-v1:...",
                "2099-12-31T10:30:00Z");
    }

    @Test
    void insertVersion_appendsVersions_findLatestReadsMaxVersion() {
        repository.insertVersion(
                batch(DATE, 1, false), List.of(rank(DATE, 1, 1, "电子"), rank(DATE, 1, 2, "银行")));
        repository.insertVersion(batch(DATE, 2, true), List.of(rank(DATE, 2, 1, "食品饮料")));

        assertThat(repository.maxVersion(DATE, Market.A_SHARE)).isEqualTo(2);
        Optional<MainlineVersion> latest = repository.findLatest(DATE, Market.A_SHARE);
        assertThat(latest).isPresent();
        assertThat(latest.orElseThrow().batch().version()).isEqualTo(2);
        assertThat(latest.orElseThrow().batch().degraded()).isTrue();
        assertThat(latest.orElseThrow().batch().degradedReason()).isEqualTo("SNAPSHOT_STALE");
        assertThat(latest.orElseThrow().items()).hasSize(1);
        assertThat(latest.orElseThrow().items().get(0).industry()).isEqualTo("食品饮料");
        // v1 仍在（追加不覆盖）
        assertThat(repository.find(DATE, 1, Market.A_SHARE).orElseThrow().items()).hasSize(2);
        assertThat(repository.findLatestAnyDate(Market.A_SHARE).orElseThrow().batch().version())
                .isEqualTo(2);

        // M29 T255：同日多市场版本共存——HK 榜同日 version 独立从 1 起、行集互不串读
        repository.insertVersion(
                batch(Market.HK, DATE, 1, false), List.of(rank(Market.HK, DATE, 1, 1, "软件服务")));
        assertThat(repository.maxVersion(DATE, Market.HK)).isEqualTo(1);
        assertThat(repository.maxVersion(DATE, Market.A_SHARE)).isEqualTo(2); // A 股不受 HK 落库影响
        assertThat(repository.find(DATE, 1, Market.HK).orElseThrow().items().get(0).industry())
                .isEqualTo("软件服务");
        assertThat(repository.find(DATE, 1, Market.A_SHARE).orElseThrow().items().get(0).industry())
                .isEqualTo("电子");
        // rank/batch 行 market 维回读
        assertThat(repository.findLatest(DATE, Market.HK).orElseThrow().batch().market())
                .isEqualTo(Market.HK);
    }

    @Test
    void findLatestAnyDate_emptyLibrary_returnsEmpty() {
        assertThat(repository.findLatestAnyDate(Market.A_SHARE)).isEmpty();
        assertThat(repository.listRankDates(5, Market.A_SHARE)).isEmpty();
    }

    @Test
    void sumEventWeightByIndustry_importanceWeightedJsonEach() {
        seedAnalyzedNews(9001, "A_SHARE");
        seedAnalyzedNews(9002, "A_SHARE");
        seedAnalyzedNews(9003, "A_SHARE");
        jdbcTemplate.update(
                "INSERT INTO event_item (news_id, event_type, summary, affected_industries,"
                        + " direction, importance, event_date, created_at, updated_at) VALUES (9001,"
                        + " 'TECH_BREAKTHROUGH', 's', '[\"电子\",\"计算机\"]', 'BULLISH', 'HIGH',"
                        + " '2099-12-30', '2026-09-30T10:00:00Z', '2026-09-30T10:00:00Z')");
        jdbcTemplate.update(
                "INSERT INTO event_item (news_id, event_type, summary, affected_industries,"
                        + " direction, importance, event_date, created_at, updated_at) VALUES (9002,"
                        + " 'OTHER', 's', '[\"电子\"]', 'NEUTRAL', 'MEDIUM', '2099-12-31',"
                        + " '2026-09-30T10:00:00Z', '2026-09-30T10:00:00Z')");
        jdbcTemplate.update(
                "INSERT INTO event_item (news_id, event_type, summary, affected_industries,"
                        + " direction, importance, event_date, created_at, updated_at) VALUES (9003,"
                        + " 'OTHER', 's', '[\"电子\"]', 'BEARISH', 'LOW', '2099-12-29',"
                        + " '2026-09-30T10:00:00Z', '2026-09-30T10:00:00Z')");

        List<EventWeightRow> rows =
                repository.sumEventWeightByIndustry("2099-12-30", "2099-12-31", Market.A_SHARE);

        // 电子 = HIGH(2) + MEDIUM(1) = 3（LOW×0 且 12-29 越窗不计）；计算机 = 2
        assertThat(rows)
                .anySatisfy(
                        row -> {
                            assertThat(row.industry()).isEqualTo("电子");
                            assertThat(row.weightedCount()).isEqualTo(3d);
                        })
                .anySatisfy(
                        row -> {
                            assertThat(row.industry()).isEqualTo("计算机");
                            assertThat(row.weightedCount()).isEqualTo(2d);
                        });

        // M29 T255：l1_market 分桶——A 股桶对 HK 条目事件零混入（跨市场重名「银行」消歧）
        seedAnalyzedNews(9004, "HK");
        jdbcTemplate.update(
                "INSERT INTO event_item (news_id, event_type, summary, affected_industries,"
                        + " direction, importance, event_date, created_at, updated_at) VALUES (9004,"
                        + " 'OTHER', 's', '[\"银行\"]', 'BULLISH', 'HIGH', '2099-12-30',"
                        + " '2026-09-30T10:00:00Z', '2026-09-30T10:00:00Z')");
        List<EventWeightRow> aShare =
                repository.sumEventWeightByIndustry("2099-12-30", "2099-12-31", Market.A_SHARE);
        List<EventWeightRow> hk =
                repository.sumEventWeightByIndustry("2099-12-30", "2099-12-31", Market.HK);
        assertThat(aShare)
                .extracting(EventWeightRow::industry)
                .doesNotContain("银行"); // HK 银行事件不进 A 股桶（l1_market 消歧）
        assertThat(hk)
                .anySatisfy(
                        row -> {
                            assertThat(row.industry()).isEqualTo("银行");
                            assertThat(row.weightedCount()).isEqualTo(2d); // HIGH×2
                        });
    }

    @Test
    void findRecentHeatTop_readsDailyReportJson() {
        // JSON 文本含双引号——参数化插入
        jdbcTemplate.update(
                "INSERT INTO industry_daily_report (report_date, status, heat_top, created_at,"
                        + " updated_at) VALUES (?, 'SUCCESS', ?, '2026-09-30T10:00:00Z',"
                        + " '2026-09-30T10:00:00Z')",
                "2099-12-30",
                "[{\"industry\":\"电子\",\"heatScore\":88.5},{\"industry\":\"银行\",\"heatScore\":12.0}]");

        var rows = repository.findRecentHeatTop(14);

        assertThat(rows)
                .anySatisfy(
                        day -> {
                            assertThat(day.reportDate()).isEqualTo("2099-12-30");
                            assertThat(day.heatTopJson()).contains("电子").contains("88.5");
                        });
        jdbcTemplate.update("DELETE FROM industry_daily_report WHERE report_date = '2099-12-30'");
    }
}
