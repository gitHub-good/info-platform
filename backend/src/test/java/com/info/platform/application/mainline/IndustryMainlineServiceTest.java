package com.info.platform.application.mainline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.analysis.HeatSnapshotRepository;
import com.info.platform.domain.analysis.HeatWindow;
import com.info.platform.domain.analysis.IndustryHeatSnapshot;
import com.info.platform.domain.mainline.IndustryMarketSnapshotRepository;
import com.info.platform.domain.mainline.IndustryMarketSnapshotRepository.HistoryPctDay;
import com.info.platform.domain.mainline.IndustryMarketSnapshotRepository.MarketSnapshotRow;
import com.info.platform.domain.mainline.MainlineRepository;
import com.info.platform.domain.mainline.MainlineRepository.EventWeightRow;
import com.info.platform.domain.mainline.MainlineRepository.HeatTopDay;
import com.info.platform.domain.mainline.MainlineRepository.MainlineBatchRow;
import com.info.platform.domain.mainline.MainlineRepository.MainlineRankRow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * IndustryMainlineService 单测（M27 T243，方案 §4.3 + ADR-0063 裁决 4）：快照守卫（无行情 no-op / 缺当日行 degraded
 * 回退最近有行日）/ 版本化追加（同日重算 version+1）/ 纯函数零漂移（同输入两轮 rank 行内容相等——幂等复算红线）/ 漏斗留痕 / basis 输入指纹。
 */
class IndustryMainlineServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-28T10:30:00Z"), ZoneOffset.UTC);

    private static final String TODAY = "2026-09-28";

    private IndustryMarketSnapshotRepository marketSnapshotRepository;

    private MainlineRepository mainlineRepository;

    private HeatSnapshotRepository heatSnapshotRepository;

    private IndustryMainlineSettings settings;

    private IndustryMainlineService service;

    @BeforeEach
    void setUp() {
        marketSnapshotRepository = mock(IndustryMarketSnapshotRepository.class);
        mainlineRepository = mock(MainlineRepository.class);
        heatSnapshotRepository = mock(HeatSnapshotRepository.class);
        com.info.platform.application.common.RuntimeConfigService configService =
                mock(com.info.platform.application.common.RuntimeConfigService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        settings = new IndustryMainlineSettings(configService);
        service =
                new IndustryMainlineService(
                        marketSnapshotRepository,
                        mainlineRepository,
                        heatSnapshotRepository,
                        settings,
                        FIXED_CLOCK,
                        new ObjectMapper());
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
                50,
                40,
                1e8,
                1e12,
                null,
                "tencent-rank",
                "TENCENT_DIRECT",
                "2026-09-28T07:00:02Z");
    }

    private static IndustryHeatSnapshot heat(String industry, double score, double delta) {
        return IndustryHeatSnapshot.create(
                industry,
                HeatWindow.H24,
                score,
                0d,
                0L,
                0L,
                "heat-v1",
                Instant.parse("2026-09-28T08:00:00Z"));
    }

    private void stubHappyPath(String snapshotDate) {
        when(marketSnapshotRepository.latestSnapshotDate()).thenReturn(Optional.of(snapshotDate));
        when(marketSnapshotRepository.findIndustryRows(snapshotDate))
                .thenReturn(
                        List.of(
                                industryRow(snapshotDate, "电子", 3.0, 5.0),
                                industryRow(snapshotDate, "银行", 1.0, 2.0),
                                industryRow(snapshotDate, "食品饮料", -1.0, -2.0),
                                industryRow(snapshotDate, "医药生物", 0.5, 0.0),
                                industryRow(snapshotDate, "计算机", 2.0, 3.0)));
        when(marketSnapshotRepository.recentSnapshotDates(anyInt()))
                .thenReturn(
                        List.of(
                                snapshotDate,
                                "2026-09-26",
                                "2026-09-25",
                                "2026-09-24",
                                "2026-09-22"));
        when(marketSnapshotRepository.findIndustryPctDayForDates(anyList()))
                .thenReturn(
                        List.of(
                                new HistoryPctDay("2026-09-22", "电子", 2.0),
                                new HistoryPctDay("2026-09-24", "电子", 2.5),
                                new HistoryPctDay("2026-09-25", "电子", 1.5),
                                new HistoryPctDay("2026-09-26", "电子", 2.0),
                                new HistoryPctDay("2026-09-22", "银行", 0.5),
                                new HistoryPctDay("2026-09-24", "银行", 0.8),
                                new HistoryPctDay("2026-09-25", "银行", 1.2),
                                new HistoryPctDay("2026-09-26", "银行", 0.4),
                                new HistoryPctDay("2026-09-22", "食品饮料", -0.5),
                                new HistoryPctDay("2026-09-24", "食品饮料", -0.8),
                                new HistoryPctDay("2026-09-25", "食品饮料", 0.2),
                                new HistoryPctDay("2026-09-26", "食品饮料", -0.3),
                                new HistoryPctDay("2026-09-22", "医药生物", 0.1),
                                new HistoryPctDay("2026-09-24", "医药生物", -0.2),
                                new HistoryPctDay("2026-09-25", "医药生物", 0.3),
                                new HistoryPctDay("2026-09-26", "医药生物", 0.0),
                                new HistoryPctDay("2026-09-22", "计算机", 1.0),
                                new HistoryPctDay("2026-09-24", "计算机", 1.4),
                                new HistoryPctDay("2026-09-25", "计算机", 0.9),
                                new HistoryPctDay("2026-09-26", "计算机", 1.2)));
        when(heatSnapshotRepository.findBoard(HeatWindow.H24))
                .thenReturn(
                        List.of(
                                heat("电子", 90d, 40d),
                                heat("计算机", 70d, 20d),
                                heat("银行", 50d, 0d),
                                heat("医药生物", 30d, -10d),
                                heat("食品饮料", 10d, -30d)));
        when(heatSnapshotRepository.findBoard(HeatWindow.D7))
                .thenReturn(
                        List.of(
                                heat("电子", 80d, 10d),
                                heat("计算机", 60d, 5d),
                                heat("银行", 40d, 0d),
                                heat("医药生物", 20d, 0d),
                                heat("食品饮料", 5d, 0d)));
        when(mainlineRepository.findRecentHeatTop(anyInt()))
                .thenReturn(
                        List.of(
                                new HeatTopDay(
                                        "2026-09-26",
                                        "[{\"industry\":\"电子\",\"heatScore\":85},{\"industry\":\"计算机\",\"heatScore\":65},{\"industry\":\"银行\",\"heatScore\":45},{\"industry\":\"医药生物\",\"heatScore\":25},{\"industry\":\"食品饮料\",\"heatScore\":8}]"),
                                new HeatTopDay(
                                        "2026-09-25",
                                        "[{\"industry\":\"电子\",\"heatScore\":80},{\"industry\":\"计算机\",\"heatScore\":60},{\"industry\":\"银行\",\"heatScore\":50},{\"industry\":\"医药生物\",\"heatScore\":30},{\"industry\":\"食品饮料\",\"heatScore\":10}]")));
        when(mainlineRepository.sumEventWeightByIndustry(anyString(), anyString()))
                .thenReturn(
                        List.of(
                                new EventWeightRow("电子", 6d),
                                new EventWeightRow("计算机", 3d),
                                new EventWeightRow("银行", 1d)));
        when(mainlineRepository.maxVersion(anyString())).thenReturn(0);
        when(mainlineRepository.insertVersion(any(), anyList()))
                .thenAnswer(inv -> ((List<?>) inv.getArgument(1)).size());
    }

    @Test
    void compute_noSnapshotAtAll_noopSkip() {
        when(marketSnapshotRepository.latestSnapshotDate()).thenReturn(Optional.empty());

        IndustryMainlineService.GenerationReport report =
                service.compute(LocalDate.parse(TODAY), false);

        assertThat(report.topSize()).isZero();
        assertThat(report.detail()).contains("no-snapshot");
        verify(mainlineRepository, never()).insertVersion(any(), anyList());
    }

    @Test
    void compute_staleSnapshot_consumesNearestAndFlagsDegraded() {
        stubHappyPath("2026-09-25");

        IndustryMainlineService.GenerationReport report =
                service.compute(LocalDate.parse(TODAY), false);

        assertThat(report.topSize()).isPositive();
        assertThat(report.detail())
                .contains("degraded=SNAPSHOT_STALE")
                .contains("snapshot=2026-09-25");
        ArgumentCaptor<MainlineBatchRow> batchCaptor =
                ArgumentCaptor.forClass(MainlineBatchRow.class);
        verify(mainlineRepository).insertVersion(batchCaptor.capture(), anyList());
        assertThat(batchCaptor.getValue().degraded()).isTrue();
        assertThat(batchCaptor.getValue().degradedReason()).isEqualTo("SNAPSHOT_STALE");
        assertThat(batchCaptor.getValue().snapshotDate()).isEqualTo("2026-09-25");
    }

    @Test
    void compute_happyPath_writesVersionedRowsWithBasisAndFunnel() {
        stubHappyPath(TODAY);

        IndustryMainlineService.GenerationReport report =
                service.compute(LocalDate.parse(TODAY), false);

        assertThat(report.topSize()).isPositive();
        assertThat(report.detail()).contains("version=1").contains("gatePassed=");
        ArgumentCaptor<MainlineBatchRow> batchCaptor =
                ArgumentCaptor.forClass(MainlineBatchRow.class);
        ArgumentCaptor<List<MainlineRankRow>> rowsCaptor = ArgumentCaptor.forClass(List.class);
        verify(mainlineRepository).insertVersion(batchCaptor.capture(), rowsCaptor.capture());
        MainlineBatchRow batch = batchCaptor.getValue();
        assertThat(batch.triggerSource()).isEqualTo("DAILY");
        assertThat(batch.degraded()).isFalse();
        assertThat(batch.basis())
                .startsWith("mainline-v1:wp=0.4")
                .contains("persist>=2/5")
                .contains("input=IMS:2026-09-28");
        assertThat(batch.funnelStatsJson()).contains("\"industries\":5").contains("topN\":");
        List<MainlineRankRow> rows = rowsCaptor.getValue();
        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0).rankNo()).isEqualTo(1);
        assertThat(rows.get(0).industry()).isEqualTo("电子"); // 三维全顶
        assertThat(rows.get(0).dimDetailJson())
                .contains("\"price\"")
                .contains("\"heat\"")
                .contains("\"event\"");
        assertThat(rows.get(0).leadersJson()).isEqualTo("[]"); // T243 先空占位，T244 填充
        assertThat(rows.get(0).persistentDays()).isEqualTo(5);
    }

    @Test
    void compute_sameDayRecompute_versionIncrements_contentZeroDrift() {
        stubHappyPath(TODAY);
        when(mainlineRepository.maxVersion(TODAY)).thenReturn(0, 1);

        service.compute(LocalDate.parse(TODAY), true);
        service.compute(LocalDate.parse(TODAY), true);

        // 第二次 version=2（手动重算语义）；两轮 rank 行内容逐字段相等（除 version/computedAt——纯函数零漂移红线）
        ArgumentCaptor<List<MainlineRankRow>> rowsCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<MainlineBatchRow> batchCaptor =
                ArgumentCaptor.forClass(MainlineBatchRow.class);
        verify(mainlineRepository, times(2))
                .insertVersion(batchCaptor.capture(), rowsCaptor.capture());
        List<MainlineRankRow> first = rowsCaptor.getAllValues().get(0);
        List<MainlineRankRow> second = rowsCaptor.getAllValues().get(1);
        assertThat(batchCaptor.getAllValues().get(1).version()).isEqualTo(2);
        assertThat(batchCaptor.getAllValues().get(1).triggerSource()).isEqualTo("MANUAL");
        assertThat(first.size()).isEqualTo(second.size());
        for (int i = 0; i < first.size(); i++) {
            MainlineRankRow a = first.get(i);
            MainlineRankRow b = second.get(i);
            assertThat(b.version()).isEqualTo(2);
            assertThat(b.industry()).isEqualTo(a.industry());
            assertThat(b.mainScore()).isEqualTo(a.mainScore());
            assertThat(b.dimDetailJson()).isEqualTo(a.dimDetailJson());
            assertThat(b.persistentDays()).isEqualTo(a.persistentDays());
            assertThat(b.basis()).isEqualTo(a.basis());
        }
    }

    @Test
    void compute_emptyHeatBoard_neutralDimensionStillOutputsRanking() {
        stubHappyPath(TODAY);
        when(heatSnapshotRepository.findBoard(any(HeatWindow.class))).thenReturn(List.of());
        when(mainlineRepository.findRecentHeatTop(anyInt())).thenReturn(List.of());

        IndustryMainlineService.GenerationReport report =
                service.compute(LocalDate.parse(TODAY), false);

        // 热度维全缺 → 中性 50 + dimensionMissing 留痕，榜单照常（缺维不出空榜）
        assertThat(report.topSize()).isPositive();
        ArgumentCaptor<MainlineBatchRow> batchCaptor =
                ArgumentCaptor.forClass(MainlineBatchRow.class);
        verify(mainlineRepository, atLeastOnce()).insertVersion(batchCaptor.capture(), anyList());
        assertThat(batchCaptor.getValue().funnelStatsJson()).contains("dimensionMissing");
    }
}
