package com.info.platform.application.markettop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.markettop.MarketTopQueryService.RankView;
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
        when(repository.findLatestAnyDate()).thenReturn(Optional.of(version(2)));

        RankView view = service.rank(null, null);

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
        when(repository.find(DATE, 1)).thenReturn(Optional.of(version(1)));

        assertThat(service.rank(DATE, "1").version()).isEqualTo(1);
    }

    @Test
    void rank_invalidDateOrVersion_30090() {
        assertThatThrownBy(() -> service.rank("2026/09/22", null))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ErrorCode.MARKET_TOP_QUERY_INVALID));
        assertThatThrownBy(() -> service.rank(DATE, "abc"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ErrorCode.MARKET_TOP_QUERY_INVALID));
        // date+version 组合无版本
        when(repository.find(DATE, 7)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.rank(DATE, "7"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ErrorCode.MARKET_TOP_QUERY_INVALID));
    }

    @Test
    void rank_noRankingForDate_30089() {
        when(repository.findLatest(DATE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rank(DATE, null))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ErrorCode.MARKET_TOP_NOT_FOUND));
    }

    @Test
    void versions_delegatesWithDateFilter() {
        when(repository.listVersions(null, 200))
                .thenReturn(
                        List.of(
                                new VersionSummary(
                                        DATE,
                                        1,
                                        "DAILY",
                                        false,
                                        null,
                                        DATE,
                                        10,
                                        "2026-09-22T10:03:00Z")));

        List<VersionSummary> versions = service.versions(null);

        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).rankDate()).isEqualTo(DATE);
        assertThat(versions.get(0).topSize()).isEqualTo(10);
    }
}
