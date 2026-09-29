package com.info.platform.infrastructure.mainline;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.mainline.IndustryMarketSnapshotRepository;
import com.info.platform.domain.mainline.IndustryMarketSnapshotRepository.HistoryPctDay;
import com.info.platform.domain.mainline.IndustryMarketSnapshotRepository.MarketSnapshotRow;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * IndustryMarketSnapshotRepositoryImpl 集成测试（M27 T242，V35 表①）：UPSERT 幂等（同日两轮零漂移）/ 行业与板块行读取 /
 * latestSnapshotDate / recentSnapshotDates / 历史 pct_day 投影。夹具用远未来日期隔离，逐轮物理清理。
 */
@SpringBootTest
@ActiveProfiles("test")
class IndustryMarketSnapshotRepositoryImplTest {

    private static final String DATE = "2099-12-31";

    private static final String PREV_DATE = "2099-12-30";

    @Autowired private IndustryMarketSnapshotRepository repository;

    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update(
                "DELETE FROM industry_market_snapshot WHERE snapshot_date >= '2099-01-01'");
    }

    private static MarketSnapshotRow boardRow(
            String date, String name, String industry, double pct) {
        return new MarketSnapshotRow(
                "BOARD",
                name,
                industry,
                date,
                pct,
                null,
                10,
                5,
                1e8,
                100e8,
                null,
                "eastmoney-push2",
                "NONE",
                "2026-09-28T07:00:00Z");
    }

    private static MarketSnapshotRow industryRow(
            String date, String industry, Double pct, Double pctD5) {
        return new MarketSnapshotRow(
                "INDUSTRY",
                industry,
                industry,
                date,
                pct,
                pctD5,
                30,
                15,
                3e8,
                300e8,
                "{\"code\":\"601579\",\"name\":\"会稽山\",\"pct\":9.99}",
                "tencent-rank",
                "TENCENT_DIRECT",
                "2026-09-28T07:00:02Z");
    }

    @Test
    void upsertAll_sameDayTwice_idempotentOverwrite() {
        List<MarketSnapshotRow> first =
                List.of(
                        boardRow(DATE, "半导体", "电子", 2.0),
                        boardRow(DATE, "银行Ⅱ", "银行", 0.5),
                        industryRow(DATE, "电子", 1.625, null));
        List<MarketSnapshotRow> second =
                List.of(boardRow(DATE, "半导体", "电子", 2.5), industryRow(DATE, "电子", 1.8, -0.5));

        int firstRows = repository.upsertAll(first);
        int secondRows = repository.upsertAll(second);

        // Assert：第二轮 UPSERT 覆盖同键行（不重复插）；行数 = 当轮传入行数（ON CONFLICT DO UPDATE 计 1）
        assertThat(firstRows).isEqualTo(3);
        assertThat(secondRows).isEqualTo(2);
        List<MarketSnapshotRow> boards = repository.findBoardRows(DATE);
        assertThat(boards).hasSize(2);
        assertThat(repository.findIndustryRows(DATE)).hasSize(1);
        MarketSnapshotRow electronics = repository.findIndustryRows(DATE).get(0);
        assertThat(electronics.pctDay()).isEqualTo(1.8);
        assertThat(electronics.pctD5()).isEqualTo(-0.5);
        assertThat(electronics.leaderStockJson()).contains("会稽山");
        assertThat(electronics.aggMethod()).isEqualTo("TENCENT_DIRECT");
    }

    @Test
    void findBoardRowsOfIndustry_filtersByIndustry() {
        repository.upsertAll(
                List.of(
                        boardRow(DATE, "半导体", "电子", 2.0),
                        boardRow(DATE, "消费电子", "电子", -1.0),
                        boardRow(DATE, "银行Ⅱ", "银行", 0.5)));

        List<MarketSnapshotRow> electronics = repository.findBoardRowsOfIndustry(DATE, "电子");

        assertThat(electronics).hasSize(2);
        assertThat(electronics).allSatisfy(row -> assertThat(row.industry()).isEqualTo("电子"));
        assertThat(repository.findBoardRowsOfIndustry(DATE, "医药生物")).isEmpty();
    }

    @Test
    void latestSnapshotDate_andRecentSnapshotDates_descending() {
        repository.upsertAll(
                List.of(
                        industryRow(PREV_DATE, "电子", 1.0, null),
                        industryRow(DATE, "电子", 1.5, null)));

        Optional<String> latest = repository.latestSnapshotDate();
        assertThat(latest).contains(DATE);
        assertThat(repository.recentSnapshotDates(5)).containsExactly(DATE, PREV_DATE);
        assertThat(repository.recentSnapshotDates(1)).containsExactly(DATE);
    }

    @Test
    void findIndustryPctDayForDates_projectsHistoryForPctD5() {
        repository.upsertAll(
                List.of(
                        industryRow(PREV_DATE, "电子", 1.0, null),
                        industryRow(PREV_DATE, "银行", -0.5, null),
                        industryRow(DATE, "电子", 2.0, null)));

        List<HistoryPctDay> history =
                repository.findIndustryPctDayForDates(List.of(DATE, PREV_DATE));

        assertThat(history).hasSize(3);
        assertThat(history)
                .anySatisfy(
                        row -> {
                            assertThat(row.snapshotDate()).isEqualTo(PREV_DATE);
                            assertThat(row.industry()).isEqualTo("电子");
                            assertThat(row.pctDay()).isEqualTo(1.0);
                        });
    }

    @Test
    void latestSnapshotDate_emptyLibrary_returnsEmpty() {
        jdbcTemplate.update(
                "DELETE FROM industry_market_snapshot WHERE snapshot_date >= '2099-01-01'");
        assertThat(repository.latestSnapshotDate()).isEmpty();
        assertThat(repository.recentSnapshotDates(5)).isEmpty();
    }

    // ---- M29 P2-01 回归：单市场行业行「替换写」——本次未命中的旧行当日清理 ----

    /** 港美股行业行（16 参主构造：market/rowType/dimName/industry/date/...）。 */
    private static MarketSnapshotRow hkusIndustryRow(
            String market, String date, String industry, double pct) {
        return new MarketSnapshotRow(
                market,
                "INDUSTRY",
                industry,
                industry,
                date,
                pct,
                null,
                3,
                1,
                null,
                100e8,
                "HKD",
                null,
                "hkus-aggregate",
                "CAP_WEIGHTED",
                "2026-09-29T08:08:08Z");
    }

    @Test
    void replaceIndustryRows_prunesStaleRows_marketAndDateScoped() {
        // Arrange：当日 HK 旧行 {互联网（待清理的改写前残留）, 银行} + A股同日同名行 + HK 前日行
        repository.upsertAll(
                List.of(
                        hkusIndustryRow("HK", DATE, "互联网", -1.77),
                        hkusIndustryRow("HK", DATE, "银行", 0.5),
                        industryRow(DATE, "银行", 0.4, null), // A_SHARE 同名行业行（消歧防误删）
                        hkusIndustryRow("HK", PREV_DATE, "互联网", -2.0), // 前日行（retention 域，不当清）
                        boardRow(DATE, "半导体", "电子", 2.0))); // BOARD 行（row_type 隔离防误删）

        // Act：行业改写后次轮聚合 → 保留集 {银行, 软件服务}（「互联网」本次未命中）
        int written =
                repository.replaceIndustryRows(
                        "HK",
                        DATE,
                        List.of(
                                hkusIndustryRow("HK", DATE, "银行", 0.6),
                                hkusIndustryRow("HK", DATE, "软件服务", -0.59)));

        // Assert：UPSERT 受影响 2 行；HK 当日最终 = 实际行业数 2（孤儿行清理，32→31 口径对齐）
        assertThat(written).isEqualTo(2);
        assertThat(repository.findIndustryRows(DATE, Market.HK))
                .extracting(MarketSnapshotRow::dimName)
                .containsExactlyInAnyOrder("银行", "软件服务"); // 「互联网」已清

        // Assert：清理作用域隔离——A 股同名行 / HK 前日行 / BOARD 行不受影响
        assertThat(repository.findIndustryRows(DATE)).hasSize(1); // A_SHARE 仅剩「银行」
        assertThat(repository.findIndustryRows(PREV_DATE, Market.HK))
                .extracting(MarketSnapshotRow::dimName)
                .containsExactly("互联网");
        assertThat(repository.findBoardRows(DATE)).hasSize(1);
    }

    @Test
    void replaceIndustryRows_sameSetTwice_idempotentNoPrune() {
        // Arrange：两轮同集（盘中幂等覆盖）→ 第二轮零清理、行数与值稳定
        repository.replaceIndustryRows(
                "HK", DATE, List.of(hkusIndustryRow("HK", DATE, "银行", 0.5)));
        int second =
                repository.replaceIndustryRows(
                        "HK", DATE, List.of(hkusIndustryRow("HK", DATE, "银行", 0.7)));

        assertThat(second).isEqualTo(1); // UPSERT 覆盖计 1（无旧行可清）
        assertThat(repository.findIndustryRows(DATE, Market.HK)).hasSize(1);
        assertThat(repository.findIndustryRows(DATE, Market.HK).get(0).pctDay()).isEqualTo(0.7);
    }

    @Test
    void replaceIndustryRows_emptyRows_noOpWithoutPruning() {
        // Arrange：已有当日行 + 空聚合清单（数据异常轮）
        repository.upsertAll(List.of(hkusIndustryRow("HK", DATE, "银行", 0.5)));

        // Act：空清单 → no-op 不清理（端口契约：保守不动旧快照，沿双链全败语义）
        int written = repository.replaceIndustryRows("HK", DATE, List.of());

        // Assert
        assertThat(written).isZero();
        assertThat(repository.findIndustryRows(DATE, Market.HK)).hasSize(1);
    }
}
