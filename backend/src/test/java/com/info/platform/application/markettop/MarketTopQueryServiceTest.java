package com.info.platform.application.markettop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.markettop.MarketTopQueryService.RankView;
import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.markettop.MarketTopRepository;
import com.info.platform.domain.markettop.MarketTopRepository.MarketTopBatchRow;
import com.info.platform.domain.markettop.MarketTopRepository.MarketTopRankRow;
import com.info.platform.domain.markettop.MarketTopRepository.MarketTopVersion;
import com.info.platform.domain.markettop.MarketTopRepository.VersionSummary;
import com.info.platform.domain.valuation.FactorSnapshotRepository;
import com.info.platform.domain.valuation.FactorSnapshotRepository.PoolRow;
import com.info.platform.domain.valuation.ValuationParams;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * MarketTopQueryService 单测（M21 T183，方案 §4.7.1）：最新版本/指定版本读取 + 五维分解补全（快照 join 复现）+ 错误口径矩阵（30089 无榜单 /
 * 30090 非法日期与版本不存在）。
 */
@ExtendWith(MockitoExtension.class)
class MarketTopQueryServiceTest {

    private static final String DATE = "2026-09-22";

    @Mock private MarketTopRepository repository;

    @Mock private FactorSnapshotRepository snapshotRepository;

    private MarketTopQueryService service;

    @BeforeEach
    void setUp() {
        service = new MarketTopQueryService(repository, snapshotRepository, new ObjectMapper());
        lenient().when(snapshotRepository.findPoolRowsByDate(DATE)).thenReturn(poolRows());
    }

    private static List<PoolRow> poolRows() {
        return List.of(
                new PoolRow(
                        1L,
                        "SZ300024",
                        "机器人",
                        "消费电子",
                        81.2,
                        29.4,
                        55.1,
                        70.0,
                        50.0,
                        58.4,
                        true,
                        "{}",
                        ValuationParams.defaults().basis(),
                        "2026-09-21"));
    }

    private static MarketTopVersion version(int versionNo) {
        return new MarketTopVersion(
                new MarketTopBatchRow(
                        Market.A_SHARE,
                        DATE,
                        versionNo,
                        "DAILY",
                        DATE,
                        "{\"topSize\":1,\"poolSize\":300}",
                        false,
                        null,
                        "[]",
                        120_000L,
                        10,
                        "v1.0",
                        "mt-v1:...",
                        null,
                        "2026-09-22T10:03:00Z"),
                List.of(
                        new MarketTopRankRow(
                                Market.A_SHARE,
                                DATE,
                                versionNo,
                                1,
                                1L,
                                "SZ300024",
                                "机器人",
                                58.4,
                                60.06,
                                99.0,
                                true,
                                "FULL",
                                "LLM",
                                "论点摘要",
                                "{\"thesis\":\"论点\"}",
                                3,
                                "2026-09-21",
                                null,
                                "NEW",
                                "mt-v1:...",
                                "2026-09-22T10:03:00Z")));
    }

    @Test
    void rank_latestAnyDate_fullViewWithFactors() {
        when(repository.findLatestAnyDate(Market.A_SHARE)).thenReturn(Optional.of(version(2)));

        RankView view = service.rank(null, null, null);

        assertThat(view.rankDate()).isEqualTo(DATE);
        assertThat(view.version()).isEqualTo(2);
        assertThat(view.disclaimer()).contains("不构成投资建议");
        assertThat(view.items()).hasSize(1);
        var item = view.items().get(0);
        assertThat(item.factors()).hasSize(5);
        assertThat(item.factors().get(0).key()).isEqualTo("catalyst");
        assertThat(item.factors().get(0).weight()).isEqualTo(0.40);
        assertThat(item.diveDetail().path("thesis").asText()).isEqualTo("论点");
        assertThat(view.batch().funnelStats().path("poolSize").asInt()).isEqualTo(300);
    }

    @Test
    void rank_explicitVersion_delegates() {
        when(repository.find(DATE, 1, Market.A_SHARE)).thenReturn(Optional.of(version(1)));

        assertThat(service.rank(null, DATE, "1").version()).isEqualTo(1);
    }

    @Test
    void rank_invalidDateOrVersion_30090() {
        assertThatThrownBy(() -> service.rank(null, "2026/09/22", null))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ErrorCode.MARKET_TOP_QUERY_INVALID));
        assertThatThrownBy(() -> service.rank(null, DATE, "abc"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ErrorCode.MARKET_TOP_QUERY_INVALID));
        // date+version 组合无版本
        when(repository.find(DATE, 7, Market.A_SHARE)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.rank(null, DATE, "7"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ErrorCode.MARKET_TOP_QUERY_INVALID));
    }

    @Test
    void rank_noRankingForDate_30089() {
        when(repository.findLatest(DATE, Market.A_SHARE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rank(null, DATE, null))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ErrorCode.MARKET_TOP_NOT_FOUND));
    }

    @Test
    void versions_delegatesWithDateFilter() {
        when(repository.listVersions(null, Market.A_SHARE, 200))
                .thenReturn(
                        List.of(
                                new VersionSummary(
                                        Market.A_SHARE,
                                        DATE,
                                        1,
                                        "DAILY",
                                        false,
                                        null,
                                        DATE,
                                        10,
                                        "2026-09-22T10:03:00Z")));

        List<VersionSummary> versions = service.versions(null, null);

        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).rankDate()).isEqualTo(DATE);
        assertThat(versions.get(0).topSize()).isEqualTo(10);
    }

    // ---- M29 T256：market 参数与 leaderboard 维度回显 ----

    @Test
    void rank_marketDefaultAShare_leaderboardEchoesCnyDiveAvailable() {
        // 缺省 market=A_SHARE 零回归 + 维度回显（A 股：CNY / 深析可用 / 无缺省维）
        when(repository.findLatestAnyDate(Market.A_SHARE)).thenReturn(Optional.of(version(2)));

        RankView view = service.rank(null, null, null);

        assertThat(view.market()).isEqualTo("A_SHARE");
        assertThat(view.leaderboard().currency()).isEqualTo("CNY");
        assertThat(view.leaderboard().industrySystem()).contains("申万");
        assertThat(view.leaderboard().diveAvailable()).isTrue();
        assertThat(view.leaderboard().diveUnavailableReason()).isNull();
        assertThat(view.leaderboard().dimensionMissing()).isNull();
    }

    @Test
    void rank_hkMarket_leaderboardEchoesHkdMissingDimsDiveUnavailable() {
        // 港股：HKD 原币 + 价值维缺省直读 funnel_stats.dimensionMissing + 深析不可用标注（拍板四/六不静默）
        when(repository.findLatestAnyDate(Market.HK)).thenReturn(Optional.of(hkusVersion()));
        lenient().when(snapshotRepository.findPoolRowsByDate(DATE)).thenReturn(List.of());

        RankView view = service.rank("HK", null, null);

        assertThat(view.market()).isEqualTo("HK");
        assertThat(view.leaderboard().currency()).isEqualTo("HKD");
        assertThat(view.leaderboard().industrySystem()).contains("港股");
        assertThat(view.leaderboard().diveAvailable()).isFalse();
        assertThat(view.leaderboard().diveUnavailableReason()).contains("暂未支持深析");
        assertThat(view.leaderboard().dimensionMissing().path("valuation").asText())
                .isEqualTo("本市场暂无价值评分因子");
        assertThat(view.leaderboard().dimensionMissing().path("fundamental").asText())
                .contains("权重置 0 后再归一");
        // 港美股无因子快照行 → 五维分解空如实（缺省由 leaderboard 说明，不造假）
        assertThat(view.items().get(0).factors()).isEmpty();
    }

    @Test
    void rank_invalidMarket_30090() {
        assertThatThrownBy(() -> service.rank("HK_SZ", null, null))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ErrorCode.MARKET_TOP_QUERY_INVALID));
        // INDEX/SECTOR 非榜单市场同 30090（三值白名单）
        assertThatThrownBy(() -> service.rank("INDEX", null, null))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ErrorCode.MARKET_TOP_QUERY_INVALID));
    }

    @Test
    void rank_hkNoRanking_30089WithMarketContext() {
        when(repository.findLatestAnyDate(Market.HK)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rank("HK", null, null))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MARKET_TOP_NOT_FOUND);
                            assertThat(e.getMessage()).contains("HK");
                        });
    }

    @Test
    void versions_marketFilteredDelegation() {
        when(repository.listVersions(null, Market.US, 200)).thenReturn(List.of());

        assertThat(service.versions("US", null)).isEmpty();
        org.mockito.Mockito.verify(repository).listVersions(null, Market.US, 200);
    }

    /** 港美股版本夹具（funnel_stats 含 dimensionMissing——MarketTopService 落库面同契约）。 */
    private static MarketTopVersion hkusVersion() {
        return new MarketTopVersion(
                new MarketTopBatchRow(
                        Market.HK,
                        DATE,
                        1,
                        "DAILY",
                        DATE,
                        "{\"topSize\":1,\"dimensionMissing\":{\"fundamental\":\"本市场暂无基本面因子（F3/F5 权重置 0 后再归一）\",\"valuation\":\"本市场暂无价值评分因子\"},\"divePolicy\":\"A_SHARE_ONLY\"}",
                        false,
                        null,
                        "[]",
                        0L,
                        0,
                        null,
                        "mt-v1:hkus:...",
                        null,
                        "2026-09-22T10:03:00Z"),
                List.of(
                        new MarketTopRankRow(
                                Market.HK,
                                DATE,
                                1,
                                1,
                                1L,
                                "HK00700",
                                "腾讯控股",
                                71.4,
                                71.4,
                                99.0,
                                false,
                                "FACTOR_ONLY",
                                null,
                                "该市场暂未支持深析（价值维因子体系 A 股先行），按因子分排序。",
                                "{}",
                                2,
                                "2026-09-21",
                                null,
                                "NEW",
                                "mt-v1:hkus:...",
                                "2026-09-22T10:03:00Z")));
    }

    // ---- recentIncrement（M22 T192，页头双时间戳 §4.2-②：当日最新 EVENT 版本摘要，无则 null） ----

    @Test
    void rank_recentIncrement_fromLatestEventVersionOfViewedDate() {
        when(repository.findLatestAnyDate(Market.A_SHARE)).thenReturn(Optional.of(version(3)));
        when(repository.findLatestEventVersion(DATE, Market.A_SHARE))
                .thenReturn(
                        Optional.of(
                                new MarketTopRepository.EventVersion(
                                        2,
                                        "2026-09-22T12:33:02Z",
                                        "[{\"eventId\":4821,\"summary\":\"业绩预增\",\"importance\":\"HIGH\"}]")));

        RankView view = service.rank(null, null, null);

        assertThat(view.recentIncrement()).isNotNull();
        assertThat(view.recentIncrement().version()).isEqualTo(2);
        assertThat(view.recentIncrement().computedAt()).isEqualTo("2026-09-22T12:33:02Z");
        assertThat(view.recentIncrement().triggerEvents()).hasSize(1);
        assertThat(view.recentIncrement().triggerEvents().get(0).eventId()).isEqualTo(4821L);
        assertThat(view.recentIncrement().triggerEvents().get(0).summary()).isEqualTo("业绩预增");
        assertThat(view.recentIncrement().triggerEvents().get(0).importance()).isEqualTo("HIGH");
    }

    @Test
    void rank_recentIncrementNull_whenNoEventVersion() {
        when(repository.findLatestAnyDate(Market.A_SHARE)).thenReturn(Optional.of(version(1)));
        when(repository.findLatestEventVersion(DATE, Market.A_SHARE)).thenReturn(Optional.empty());

        RankView view = service.rank(null, null, null);

        assertThat(view.recentIncrement()).isNull();
    }

    @Test
    void rank_recentIncrement_malformedTriggerEventsJson_fallsBackToEmptyList() {
        when(repository.findLatestAnyDate(Market.A_SHARE)).thenReturn(Optional.of(version(3)));
        when(repository.findLatestEventVersion(DATE, Market.A_SHARE))
                .thenReturn(
                        Optional.of(
                                new MarketTopRepository.EventVersion(
                                        2, "2026-09-22T12:33:02Z", "not-json")));

        RankView view = service.rank(null, null, null);

        // 损坏 JSON 容错空表——摘要面缺省不阻断榜单读取
        assertThat(view.recentIncrement()).isNotNull();
        assertThat(view.recentIncrement().triggerEvents()).isEmpty();
    }
}
