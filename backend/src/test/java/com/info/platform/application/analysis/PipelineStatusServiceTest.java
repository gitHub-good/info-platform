package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.domain.analysis.GuardLevel;
import com.info.platform.domain.analysis.NewsAnalysisRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PipelineStatusService 单测（T121 基础版 → T125 完整版，方案 §4.6/§4.8）：护栏面组装（level/预算/阈值/校准值——来自
 * PipelineGuardService 与 pipeline.budget 缺省）、当日计数（含 L2 三态）、l1RateIn30min 与 l2Coverage 算术（无样本
 * null）、Asia/Shanghai 日界折算 UTC、lastTick 透传。全 mock。AAA 结构。
 */
class PipelineStatusServiceTest {

    // 2026-09-22 17:30 上海 = 09:30 UTC → 当日零点（上海）= 前一日 16:00 UTC
    private static final Instant NOW = Instant.parse("2026-09-22T09:30:00Z");

    private static final String TODAY_START = "2026-09-21T16:00:00Z";

    private NewsAnalysisRepository repository;
    private NewsPipelineService pipelineService;
    private PipelineGuardService guardService;
    private PipelineStatusService service;

    @BeforeEach
    void setUp() {
        repository = mock(NewsAnalysisRepository.class);
        pipelineService = mock(NewsPipelineService.class);
        guardService = mock(PipelineGuardService.class);
        when(guardService.currentLevel()).thenReturn(GuardLevel.NORMAL);
        when(guardService.todayCostMicros()).thenReturn(0L);
        RuntimeConfigService configService = mock(RuntimeConfigService.class);
        when(configService.read(anyString())).thenReturn(Optional.empty());
        PipelineSettings settings = new PipelineSettings(configService, new ObjectMapper());
        service =
                new PipelineStatusService(
                        repository,
                        pipelineService,
                        guardService,
                        settings,
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void status_fullGuardFaceComposed() {
        // Arrange：预算键缺省（¥2/日=2,000,000 微元；60%/90% 阈值；校准初值 1100 = 附录 A）
        when(repository.countL0ByResultSince(TODAY_START))
                .thenReturn(Map.of("PASS", 620L, "NOISE", 8L, "NEAR_DUP", 12L));
        when(repository.countL1ByStatusSince(TODAY_START))
                .thenReturn(Map.of("DONE", 600L, "PENDING", 5L, "FAILED", 2L));
        when(repository.countL2ByStatusSince(TODAY_START))
                .thenReturn(Map.of("EXTRACTED", 90L, "NO_EVENT", 20L, "DEFERRED", 10L));
        when(repository.countL1SlaSince(TODAY_START))
                .thenReturn(new NewsAnalysisRepository.L1SlaStats(600L, 570L));
        when(guardService.currentLevel()).thenReturn(GuardLevel.DEGRADED);
        when(guardService.todayCostMicros()).thenReturn(1_350_000L);

        // Act
        PipelineStatusView view = service.status();

        // Assert
        assertThat(view.jobKey()).isEqualTo("NEWS_PIPELINE");
        assertThat(view.level()).isEqualTo(GuardLevel.DEGRADED);
        assertThat(view.todayCostMicros()).isEqualTo(1_350_000L);
        assertThat(view.budgetMicros()).isEqualTo(2_000_000L);
        assertThat(view.degradeAtMicros()).isEqualTo(1_200_000L);
        assertThat(view.fuseAtMicros()).isEqualTo(1_800_000L);
        assertThat(view.calibratedPerItemMicros()).isEqualTo(1_100L);
        assertThat(view.costBasis()).isEqualTo("cost-v1:initial");
    }

    @Test
    void status_slaAndCoverageArithmetic() {
        // l1RateIn30min = 570/600 = 0.95；l2Coverage(coverage-v2) =
        // EXTRACTED/(EXTRACTED+FAILED+DEFERRED+滞留SELECTED)——NO_EVENT 已移出分母（T137）
        when(repository.countL0ByResultSince(anyString())).thenReturn(Map.of());
        when(repository.countL1ByStatusSince(anyString())).thenReturn(Map.of("DONE", 600L));
        when(repository.countL2ByStatusSince(anyString()))
                .thenReturn(Map.of("EXTRACTED", 90L, "NO_EVENT", 20L, "DEFERRED", 10L));
        when(repository.countL1SlaSince(anyString()))
                .thenReturn(new NewsAnalysisRepository.L1SlaStats(600L, 570L));

        PipelineStatusView view = service.status();

        assertThat(view.today().l1RateIn30min()).isEqualTo(0.95);
        assertThat(view.today().l2Coverage()).isEqualTo(90.0 / 100.0);
        assertThat(view.today().l2Extracted()).isEqualTo(90L);
        assertThat(view.today().l2Deferred()).isEqualTo(10L);
    }

    @Test
    void status_slaAndCoverageNullWithoutSamples() {
        // 无 DONE / 无 L2 命中 → 比率 null（P50/P90「无样本 null」先例，可区分 0）
        when(repository.countL0ByResultSince(anyString())).thenReturn(Map.of("PASS", 3L));
        when(repository.countL1ByStatusSince(anyString())).thenReturn(Map.of());
        when(repository.countL2ByStatusSince(anyString())).thenReturn(Map.of());
        when(repository.countL1SlaSince(anyString()))
                .thenReturn(new NewsAnalysisRepository.L1SlaStats(0L, 0L));

        PipelineStatusView view = service.status();

        assertThat(view.today().l0Pass()).isEqualTo(3L);
        assertThat(view.today().l1RateIn30min()).isNull();
        assertThat(view.today().l2Coverage()).isNull();
        assertThat(view.today().noEventRatio()).isNull(); // 无样本同 null 先例
        assertThat(view.today().l2Extracted()).isZero();
        assertThat(view.lastTick()).isNull(); // 未跑过批窗口
    }

    // ---- T137：coverage-v2 口径（AMB-02 落地，方案 §4.10） ----

    @Test
    void status_coverageV2_excludesNoEventAndAddsNoEventRatio() {
        // Arrange：EXTRACTED=90 / NO_EVENT=20 / FAILED=2 / DEFERRED=10
        when(repository.countL0ByResultSince(anyString())).thenReturn(Map.of());
        when(repository.countL1ByStatusSince(anyString())).thenReturn(Map.of("DONE", 600L));
        when(repository.countL2ByStatusSince(anyString()))
                .thenReturn(
                        Map.of("EXTRACTED", 90L, "NO_EVENT", 20L, "FAILED", 2L, "DEFERRED", 10L));
        when(repository.countL1SlaSince(anyString()))
                .thenReturn(new NewsAnalysisRepository.L1SlaStats(0L, 0L));

        // Act
        PipelineStatusView view = service.status();

        // Assert：覆盖率分母剔除 NO_EVENT = 90 ÷ (90+2+10) = 0.9；NO_EVENT 占比独立指标 =
        // 20 ÷ (90+20+2+10) = 20/122；口径版本串 coverage-v2（阈值 80% 判定不变）
        assertThat(view.today().l2Coverage()).isEqualTo(90.0 / 102.0);
        assertThat(view.today().noEventRatio()).isEqualTo(20.0 / 122.0);
        assertThat(view.coverageBasis()).isEqualTo("coverage-v2");
    }

    @Test
    void status_coverageV2_selectedStuckCountedInDenominator() {
        // Arrange：当日滞留处理中（SELECTED 未终态）计入分母——正确拒绝不算失败，滞留算未完成
        when(repository.countL0ByResultSince(anyString())).thenReturn(Map.of());
        when(repository.countL1ByStatusSince(anyString())).thenReturn(Map.of());
        when(repository.countL2ByStatusSince(anyString()))
                .thenReturn(Map.of("EXTRACTED", 8L, "SELECTED", 2L));
        when(repository.countL1SlaSince(anyString()))
                .thenReturn(new NewsAnalysisRepository.L1SlaStats(0L, 0L));

        // Act
        PipelineStatusView view = service.status();

        // Assert：8 ÷ (8 + 2) = 0.8；noEventRatio = 0 ÷ 10 = 0.0（非 null——有样本）
        assertThat(view.today().l2Coverage()).isEqualTo(0.8);
        assertThat(view.today().noEventRatio()).isEqualTo(0.0);
    }

    @Test
    void status_coverageV2_m15ThreeSnapshotsReplayAll100() {
        // M15 测试报告 §3.3 三拍（AMB-02 澄清前 coverage-v1 读数 86.4% / 80.0% / 79.1%）：
        // EXTRACTED/分母 = 19/22 → 28/35 → 34/43，差额全为 NO_EVENT（FAILED/DEFERRED/滞留均为 0）。
        // coverage-v2 分母剔除 NO_EVENT 后三拍全部 = 100%（正确拒绝不计失败，防诱导硬凑事件）。
        long[][] snapshots = {{19, 3}, {28, 7}, {34, 9}};
        for (long[] snapshot : snapshots) {
            when(repository.countL2ByStatusSince(anyString()))
                    .thenReturn(
                            Map.of(
                                    "EXTRACTED", snapshot[0],
                                    "NO_EVENT", snapshot[1]));
            when(repository.countL0ByResultSince(anyString())).thenReturn(Map.of());
            when(repository.countL1ByStatusSince(anyString())).thenReturn(Map.of());
            when(repository.countL1SlaSince(anyString()))
                    .thenReturn(new NewsAnalysisRepository.L1SlaStats(0L, 0L));

            PipelineStatusView view = service.status();

            long extracted = snapshot[0];
            long noEvent = snapshot[1];
            assertThat(view.today().l2Coverage())
                    .as("M15 三拍回放 EXTRACTED=%d NO_EVENT=%d", extracted, noEvent)
                    .isEqualTo(1.0);
            assertThat(view.today().noEventRatio())
                    .isEqualTo((double) noEvent / (extracted + noEvent));
        }
    }

    @Test
    void status_shanghaiDayBoundaryConvertedToUtc() {
        // 次日 00:30 上海 = 前日 16:30 UTC → 窗口起点 = 当日上海零点（2026-09-22T16:00:00Z）
        service =
                new PipelineStatusService(
                        repository,
                        pipelineService,
                        guardService,
                        settingsOfDefaults(),
                        Clock.fixed(Instant.parse("2026-09-22T16:30:00Z"), ZoneOffset.UTC));
        when(repository.countL0ByResultSince(anyString())).thenReturn(Map.of());
        when(repository.countL1ByStatusSince(anyString())).thenReturn(Map.of());
        when(repository.countL2ByStatusSince(anyString())).thenReturn(Map.of());
        when(repository.countL1SlaSince(anyString()))
                .thenReturn(new NewsAnalysisRepository.L1SlaStats(0L, 0L));

        service.status();

        verifyWindowStart("2026-09-22T16:00:00Z");
    }

    private PipelineSettings settingsOfDefaults() {
        RuntimeConfigService configService = mock(RuntimeConfigService.class);
        when(configService.read(anyString())).thenReturn(Optional.empty());
        return new PipelineSettings(configService, new ObjectMapper());
    }

    private void verifyWindowStart(String expected) {
        org.mockito.Mockito.verify(repository).countL0ByResultSince(expected);
        org.mockito.Mockito.verify(repository).countL1ByStatusSince(expected);
        org.mockito.Mockito.verify(repository).countL1SlaSince(expected);
    }
}
