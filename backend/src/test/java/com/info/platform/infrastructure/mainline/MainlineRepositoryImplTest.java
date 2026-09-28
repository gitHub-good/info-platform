package com.info.platform.infrastructure.mainline;

import static org.assertj.core.api.Assertions.assertThat;

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
 * MainlineRepositoryImpl 集成测试（M27 T243，V35 表②③）：追加式版本化（同日 version 递增不覆盖）/ findLatest 语义 /
 * findLatestAnyDate 回退 / 事件密度 json_each 加权 SQL（HIGH×2/MEDIUM×1/LOW×0 对账面）。夹具远未来日期隔离，逐轮物理清理。
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
    }

    private static MainlineRankRow rank(String date, int version, int rankNo, String industry) {
        return new MainlineRankRow(
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
        return new MainlineBatchRow(
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

        assertThat(repository.maxVersion(DATE)).isEqualTo(2);
        Optional<MainlineVersion> latest = repository.findLatest(DATE);
        assertThat(latest).isPresent();
        assertThat(latest.orElseThrow().batch().version()).isEqualTo(2);
        assertThat(latest.orElseThrow().batch().degraded()).isTrue();
        assertThat(latest.orElseThrow().batch().degradedReason()).isEqualTo("SNAPSHOT_STALE");
        assertThat(latest.orElseThrow().items()).hasSize(1);
        assertThat(latest.orElseThrow().items().get(0).industry()).isEqualTo("食品饮料");
        // v1 仍在（追加不覆盖）
        assertThat(repository.find(DATE, 1).orElseThrow().items()).hasSize(2);
        assertThat(repository.findLatestAnyDate().orElseThrow().batch().version()).isEqualTo(2);
    }

    @Test
    void findLatestAnyDate_emptyLibrary_returnsEmpty() {
        assertThat(repository.findLatestAnyDate()).isEmpty();
        assertThat(repository.listRankDates(5)).isEmpty();
    }

    @Test
    void sumEventWeightByIndustry_importanceWeightedJsonEach() {
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

        List<EventWeightRow> rows = repository.sumEventWeightByIndustry("2099-12-30", "2099-12-31");

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
