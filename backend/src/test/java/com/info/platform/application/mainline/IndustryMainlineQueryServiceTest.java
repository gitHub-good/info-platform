package com.info.platform.application.mainline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.mainline.IndustryMarketSnapshotRepository;
import com.info.platform.domain.mainline.IndustryMarketSnapshotRepository.MarketSnapshotRow;
import com.info.platform.domain.mainline.MainlineRepository;
import com.info.platform.domain.mainline.MainlineRepository.MainlineBatchRow;
import com.info.platform.domain.mainline.MainlineRepository.MainlineRankRow;
import com.info.platform.domain.mainline.MainlineRepository.MainlineVersion;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * IndustryMainlineQueryService 单测（M27 T244，方案 §4.5）：heat-map meta（source/quoteTime/stale 回退最近有行日 /
 * 30093 全空）/ mainline 版本回退读（date+version / 30094 全库无榜 / 30095 版本不存在）/ detail 30095（非申万枚举 /
 * 无快照行）与两形态分叉。
 */
class IndustryMainlineQueryServiceTest {

    /** 固定时钟：2026-09-28T20:00:00+08:00（quoteTime 后 5h——30min 间隔 ×2 阈值远超 → stale）。 */
    private static final Clock STALE_CLOCK =
            Clock.fixed(Instant.parse("2026-09-28T12:00:00Z"), ZoneOffset.UTC);

    private static final Clock FRESH_CLOCK =
            Clock.fixed(Instant.parse("2026-09-28T07:10:00Z"), ZoneOffset.UTC);

    private IndustryMarketSnapshotRepository marketSnapshotRepository;

    private MainlineRepository mainlineRepository;

    private RuntimeConfigService configService;

    private IndustryMainlineQueryService serviceOf(Clock clock) {
        return new IndustryMainlineQueryService(
                marketSnapshotRepository,
                mainlineRepository,
                configService,
                clock,
                new ObjectMapper());
    }

    @BeforeEach
    void setUp() {
        marketSnapshotRepository = mock(IndustryMarketSnapshotRepository.class);
        mainlineRepository = mock(MainlineRepository.class);
        configService = mock(RuntimeConfigService.class);
        lenient()
                .when(configService.read("job.INDUSTRY_MARKET_SNAPSHOT"))
                .thenReturn(Optional.empty());
    }

    private static MarketSnapshotRow industryRow(
            String date, String industry, String source, String quoteTime) {
        return new MarketSnapshotRow(
                "INDUSTRY",
                industry,
                industry,
                date,
                -0.17,
                -0.82,
                57,
                122,
                -16835.63e4,
                36103.24e8,
                "{\"code\":\"601579\",\"name\":\"会稽山\",\"pct\":9.99}",
                source,
                "TENCENT_DIRECT",
                quoteTime);
    }

    @Test
    void heatMap_defaultsToNearestRows_metaCarriesSourceAndStale() {
        when(marketSnapshotRepository.recentSnapshotDates(anyInt()))
                .thenReturn(List.of("2026-09-27", "2026-09-26"));
        when(marketSnapshotRepository.findIndustryRows("2026-09-27"))
                .thenReturn(
                        List.of(
                                industryRow(
                                        "2026-09-27",
                                        "食品饮料",
                                        "tencent-rank",
                                        "2026-09-27T15:00:02+08:00")));

        IndustryMainlineQueryService.HeatMapView view = serviceOf(STALE_CLOCK).heatMap(null);

        assertThat(view.snapshotDate()).isEqualTo("2026-09-27");
        assertThat(view.source()).isEqualTo("tencent-rank");
        assertThat(view.quoteTime()).isEqualTo("2026-09-27T15:00:02+08:00");
        assertThat(view.stale()).isTrue();
        assertThat(view.industries()).hasSize(1);
        assertThat(view.industries().get(0).industry()).isEqualTo("食品饮料");
        assertThat(view.industries().get(0).leaderStock().path("code").asText())
                .isEqualTo("601579");
    }

    @Test
    void heatMap_freshQuote_notStale() {
        when(marketSnapshotRepository.recentSnapshotDates(anyInt()))
                .thenReturn(List.of("2026-09-28"));
        when(marketSnapshotRepository.findIndustryRows("2026-09-28"))
                .thenReturn(
                        List.of(
                                industryRow(
                                        "2026-09-28",
                                        "电子",
                                        "tencent-rank",
                                        "2026-09-28T15:00:02+08:00")));

        IndustryMainlineQueryService.HeatMapView view =
                serviceOf(FRESH_CLOCK).heatMap("2026-09-28");

        assertThat(view.stale()).isFalse();
        assertThat(view.snapshotDate()).isEqualTo("2026-09-28");
    }

    @Test
    void heatMap_emptyLibrary_30093() {
        when(marketSnapshotRepository.recentSnapshotDates(anyInt())).thenReturn(List.of());
        when(marketSnapshotRepository.findIndustryRows("2026-09-28")).thenReturn(List.of());

        assertThatThrownBy(() -> serviceOf(FRESH_CLOCK).heatMap(null))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.INDUSTRY_MARKET_SNAPSHOT_EMPTY));
    }

    @Test
    void heatMap_invalidDate_30095() {
        assertThatThrownBy(() -> serviceOf(FRESH_CLOCK).heatMap("2026/09/28"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("yyyy-MM-dd");
    }

    @Test
    void mainline_dateAndVersion_fallsBackToLatestAnyDate() {
        MainlineVersion version =
                new MainlineVersion(
                        new MainlineBatchRow(
                                "2026-09-27",
                                2,
                                "DAILY",
                                "2026-09-27",
                                "{\"industries\":31}",
                                false,
                                null,
                                "mainline-v1:...",
                                "2026-09-27T10:30:00Z"),
                        List.of(
                                new MainlineRankRow(
                                        "2026-09-27",
                                        2,
                                        1,
                                        "电子",
                                        88.5,
                                        "{\"price\":{\"rank\":1}}",
                                        5,
                                        1,
                                        "NONE",
                                        "[{\"rank\":1,\"rankLabel\":\"龙一\"}]",
                                        "mainline-v1:...",
                                        "2026-09-27T10:30:00Z")));
        when(mainlineRepository.findLatest("2026-09-28")).thenReturn(Optional.empty());
        when(mainlineRepository.findLatestAnyDate()).thenReturn(Optional.of(version));

        IndustryMainlineQueryService.MainlineView view =
                serviceOf(FRESH_CLOCK).mainline("2026-09-28", null);

        // 非交易日回退最近榜日最大版本
        assertThat(view.rankDate()).isEqualTo("2026-09-27");
        assertThat(view.version()).isEqualTo(2);
        assertThat(view.items()).hasSize(1);
        assertThat(view.items().get(0).leaders().get(0).path("rankLabel").asText()).isEqualTo("龙一");
    }

    @Test
    void mainline_explicitVersionMissing_30095() {
        when(mainlineRepository.find("2026-09-27", 9)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceOf(FRESH_CLOCK).mainline("2026-09-27", "9"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.INDUSTRY_MAINLINE_QUERY_INVALID));
    }

    @Test
    void mainline_noRankAnywhere_30094() {
        when(mainlineRepository.findLatestAnyDate()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceOf(FRESH_CLOCK).mainline(null, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.INDUSTRY_MAINLINE_NOT_FOUND));
    }

    @Test
    void detail_invalidIndustry_30095() {
        assertThatThrownBy(() -> serviceOf(FRESH_CLOCK).detail("半导体概念"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.INDUSTRY_MAINLINE_QUERY_INVALID));
    }

    @Test
    void detail_noSnapshotRowForIndustry_30095() {
        when(marketSnapshotRepository.latestSnapshotDate()).thenReturn(Optional.of("2026-09-28"));
        when(marketSnapshotRepository.findIndustryRows("2026-09-28"))
                .thenReturn(
                        List.of(
                                industryRow(
                                        "2026-09-28",
                                        "食品饮料",
                                        "tencent-rank",
                                        "2026-09-28T15:00:02+08:00")));

        assertThatThrownBy(() -> serviceOf(FRESH_CLOCK).detail("电子"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("该行业无快照行");
    }

    @Test
    void detail_channelB_constituentsLeadersAndMemberCount() {
        when(marketSnapshotRepository.latestSnapshotDate()).thenReturn(Optional.of("2026-09-28"));
        MarketSnapshotRow electronics =
                new MarketSnapshotRow(
                        "INDUSTRY",
                        "电子",
                        "电子",
                        "2026-09-28",
                        -4.93,
                        -6.51,
                        30,
                        400,
                        -2e9,
                        15.6e12,
                        "{\"code\":\"300162\",\"name\":\"雷曼光电\",\"pct\":12.89}",
                        "tencent-rank",
                        "TENCENT_DIRECT",
                        "2026-09-28T15:00:02+08:00");
        when(marketSnapshotRepository.findIndustryRows("2026-09-28"))
                .thenReturn(
                        List.of(
                                industryRow(
                                        "2026-09-28",
                                        "食品饮料",
                                        "tencent-rank",
                                        "2026-09-28T15:00:02+08:00"),
                                electronics));
        when(mainlineRepository.findActiveMembers())
                .thenReturn(
                        List.of(
                                new MainlineRepository.MemberRow(88, "SH600519", "贵州茅台", "食品饮料"),
                                new MainlineRepository.MemberRow(90, "SH688981", "中芯国际", "半导体"),
                                new MainlineRepository.MemberRow(91, "SZ300162", "雷曼光电", "光学光电子")));
        when(mainlineRepository.recentMarketQuoteDates(1)).thenReturn(List.of("2026-09-28"));
        when(mainlineRepository.findMarketPctChangeForDates(anyList()))
                .thenReturn(
                        List.of(
                                new MainlineRepository.MarketQuoteRow(
                                        "SH600519", "2026-09-28", 0.56),
                                new MainlineRepository.MarketQuoteRow(
                                        "SH688981", "2026-09-28", -2.85),
                                new MainlineRepository.MarketQuoteRow(
                                        "SZ300162", "2026-09-28", 12.89)));
        when(mainlineRepository.findLatestAnyDate()).thenReturn(Optional.empty());

        IndustryMainlineQueryService.DetailView view = serviceOf(FRESH_CLOCK).detail("电子");

        // 通道 B 形态：成分股（半导体→电子映射 + 光学光电子→电子）+ 领涨股 + 成员统计
        assertThat(view.source()).isEqualTo("tencent-rank");
        assertThat(view.boards()).isEmpty();
        assertThat(view.constituents()).hasSize(2);
        assertThat(view.leaderStock().path("name").asText()).isEqualTo("雷曼光电");
        assertThat(view.memberCount()).isEqualTo(2);
        assertThat(view.leaders().isArray()).isTrue();
    }
}
