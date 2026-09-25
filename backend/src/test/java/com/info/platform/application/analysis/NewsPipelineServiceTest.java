package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.domain.analysis.GuardLevel;
import com.info.platform.domain.analysis.NewsAnalysisRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * NewsPipelineService 单测（T121/T122，方案 §4.3/§4.4/§4.7）：tick 编排（L0→L1→L2 顺序）、段间独立容错（一段失败不阻断后段与下轮）、
 * 批分片（≤ l1BatchSize/l2BatchSize 每批）、空批静默、lastTick 留痕。全 mock。AAA 结构。
 */
class NewsPipelineServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");

    private L0PrefilterService l0Prefilter;
    private ClassificationService classificationService;
    private EventExtractionService eventExtractionService;
    private NewsAnalysisRepository repository;
    private PipelineGuardService guardService;
    private AiExclusionResolver exclusionResolver;
    private NewsPipelineService service;

    /** 固定时钟（tick 内多段取时一致性）。 */
    private static final class FixedClock extends Clock {

        @Override
        public Instant instant() {
            return NOW;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    @BeforeEach
    void setUp() {
        l0Prefilter = mock(L0PrefilterService.class);
        classificationService = mock(ClassificationService.class);
        eventExtractionService = mock(EventExtractionService.class);
        repository = mock(NewsAnalysisRepository.class);
        guardService = mock(PipelineGuardService.class);
        when(guardService.currentLevel()).thenReturn(GuardLevel.NORMAL);
        exclusionResolver = mock(AiExclusionResolver.class);
        when(exclusionResolver.excludedSourceIds(any())).thenReturn(List.of());
        RuntimeConfigService configService = mock(RuntimeConfigService.class);
        when(configService.read(anyString())).thenReturn(Optional.empty());
        PipelineSettings settings = new PipelineSettings(configService, new ObjectMapper());
        service =
                new NewsPipelineService(
                        l0Prefilter,
                        classificationService,
                        eventExtractionService,
                        repository,
                        settings,
                        guardService,
                        exclusionResolver,
                        new FixedClock());
        when(eventExtractionService.runL2Window())
                .thenReturn(new EventExtractionService.L2Report(0, 0, 0, 0, 0));
    }

    private static NewsAnalysisRepository.ClassificationCandidate candidate(long id) {
        return new NewsAnalysisRepository.ClassificationCandidate(
                id, "标题" + id, "摘要", "新浪财经", NOW, NOW);
    }

    @Test
    void tick_l0ThenL1ThenL2_detailsComposed() {
        // Arrange
        when(l0Prefilter.run()).thenReturn(new L0PrefilterService.L0Report(5, 2, 1));
        when(repository.findPendingForL1(anyString(), anyInt(), anyList(), anyInt()))
                .thenReturn(List.of(candidate(1), candidate(2)));
        when(classificationService.classifyBatch(anyList()))
                .thenReturn(new ClassificationService.BatchOutcome(2, 0));
        when(eventExtractionService.runL2Window())
                .thenReturn(new EventExtractionService.L2Report(2, 2, 1, 0, 1));

        // Act
        NewsPipelineService.TickReport report = service.tick();

        // Assert：三段顺序（verify 顺序由 InOrder 太重，此处以明细串断言组合）
        assertThat(report.detail())
                .isEqualTo(
                        "l0=pass:5; noise:2; near_dup:1; l1=done:2; fail:0; pending=2; "
                                + "l2=extracted:2; no_event:1; failed:0; deferred:1");
        assertThat(report.processed()).isEqualTo(13); // L0 8 + L1 done 2 + L2 3

        NewsPipelineService.LastTick lastTick = service.lastTick();
        assertThat(lastTick.startedAt()).isEqualTo(NOW);
        assertThat(lastTick.finishedAt()).isEqualTo(NOW);
        assertThat(lastTick.detail()).isEqualTo(report.detail());
    }

    @Test
    void tick_pendingSplitIntoBatches_ofConfiguredSize() {
        // Arrange：45 条待处理 → 批 20 缺省 → 3 批（20/20/5）
        when(l0Prefilter.run()).thenReturn(new L0PrefilterService.L0Report(0, 0, 0));
        List<NewsAnalysisRepository.ClassificationCandidate> pending =
                IntStream.rangeClosed(1, 45).mapToObj(NewsPipelineServiceTest::candidate).toList();
        when(repository.findPendingForL1(anyString(), anyInt(), anyList(), anyInt()))
                .thenReturn(pending);
        when(classificationService.classifyBatch(anyList()))
                .thenReturn(new ClassificationService.BatchOutcome(20, 0));

        // Act
        NewsPipelineService.TickReport report = service.tick();

        // Assert
        verify(classificationService, times(3)).classifyBatch(anyList());
        assertThat(report.detail()).contains("l1=done:60").contains("pending=45");
    }

    @Test
    void tick_emptyPending_silentNoCalls() {
        when(l0Prefilter.run()).thenReturn(new L0PrefilterService.L0Report(0, 0, 0));
        when(repository.findPendingForL1(anyString(), anyInt(), anyList(), anyInt()))
                .thenReturn(List.of());

        NewsPipelineService.TickReport report = service.tick();

        assertThat(report.detail())
                .isEqualTo(
                        "l0=pass:0; noise:0; near_dup:0; l1=done:0; fail:0; pending=0; "
                                + "l2=extracted:0; no_event:0; failed:0; deferred:0");
        verify(classificationService, never()).classifyBatch(anyList());
    }

    @Test
    void tick_l0Failure_doesNotBlockL1AndL2() {
        // 段间独立容错（ADR-0046 裁决 4）：L0 抛错只置 l0=error，L1/L2 照常执行
        when(l0Prefilter.run()).thenThrow(new RuntimeException("L0 段故障"));
        when(repository.findPendingForL1(anyString(), anyInt(), anyList(), anyInt()))
                .thenReturn(List.of(candidate(1)));
        when(classificationService.classifyBatch(anyList()))
                .thenReturn(new ClassificationService.BatchOutcome(1, 0));

        NewsPipelineService.TickReport report = service.tick();

        assertThat(report.detail()).startsWith("l0=error; ").contains("l1=done:1");
        verify(eventExtractionService).runL2Window();
    }

    @Test
    void tick_l1Failure_l0StillReported_l2StillRuns() {
        when(l0Prefilter.run()).thenReturn(new L0PrefilterService.L0Report(3, 0, 0));
        when(repository.findPendingForL1(anyString(), anyInt(), anyList(), anyInt()))
                .thenThrow(new RuntimeException("L1 段故障"));

        NewsPipelineService.TickReport report = service.tick();

        assertThat(report.detail())
                .isEqualTo(
                        "l0=pass:3; noise:0; near_dup:0; l1=error; "
                                + "l2=extracted:0; no_event:0; failed:0; deferred:0");
        assertThat(report.processed()).isEqualTo(3); // L0 产出仍计入
        verify(eventExtractionService).runL2Window(); // L1 失败不阻断 L2 段
    }

    @Test
    void tick_l2Failure_l0L1StillReported() {
        when(l0Prefilter.run()).thenReturn(new L0PrefilterService.L0Report(3, 0, 0));
        when(repository.findPendingForL1(anyString(), anyInt(), anyList(), anyInt()))
                .thenReturn(List.of());
        when(eventExtractionService.runL2Window()).thenThrow(new RuntimeException("L2 段故障"));

        NewsPipelineService.TickReport report = service.tick();

        assertThat(report.detail())
                .isEqualTo(
                        "l0=pass:3; noise:0; near_dup:0; l1=done:0; fail:0; pending=0; l2=error");
        assertThat(report.processed()).isEqualTo(3);
    }

    @Test
    void tick_backfillWindowPassedToRepository() {
        when(l0Prefilter.run()).thenReturn(new L0PrefilterService.L0Report(0, 0, 0));
        when(repository.findPendingForL1(anyString(), anyInt(), anyList(), anyInt()))
                .thenReturn(List.of());

        service.tick();

        // 24h 补跑窗口（缺省）+ maxRetries 3 + tick 上限 400
        verify(repository)
                .findPendingForL1(
                        NOW.minus(Duration.ofHours(24)).toString(),
                        3,
                        java.util.List.of(),
                        PipelineSettings.L1_TICK_CAP);
    }

    // ---- T125：护栏挂钩（DEGRADED 跳 L2 / FUSED 连 L1 也跳 / 24h 窗口自然补跑，方案 §4.6） ----

    @Test
    void tick_degraded_skipsL2Only() {
        // Arrange：成本 ≥60% 预算 → DEGRADED（跳 L2 保 L1，REQ 拍板四-1）
        when(guardService.currentLevel()).thenReturn(GuardLevel.DEGRADED);
        when(l0Prefilter.run()).thenReturn(new L0PrefilterService.L0Report(2, 0, 0));
        when(repository.findPendingForL1(anyString(), anyInt(), anyList(), anyInt()))
                .thenReturn(List.of(candidate(1)));
        when(classificationService.classifyBatch(anyList()))
                .thenReturn(new ClassificationService.BatchOutcome(1, 0));

        NewsPipelineService.TickReport report = service.tick();

        // Assert：L1 照常、L2 段被跳过（留痕 skipL2 标记）
        verify(classificationService).classifyBatch(anyList());
        verify(eventExtractionService, never()).runL2Window();
        assertThat(report.detail()).contains("l1=done:1").contains("skipL2=1(degraded)");
    }

    @Test
    void tick_fused_skipsL1AndL2_l0StillRuns() {
        // Arrange：成本 ≥90% 预算 → FUSED（L1/L2 全跳；L0 零成本照常，方案 §4.6）
        when(guardService.currentLevel()).thenReturn(GuardLevel.FUSED);
        when(l0Prefilter.run()).thenReturn(new L0PrefilterService.L0Report(4, 1, 0));

        NewsPipelineService.TickReport report = service.tick();

        // Assert：L1/L2 全零调用；明细带 fused 留痕；L0 产出仍计入
        verify(repository, never()).findPendingForL1(anyString(), anyInt(), anyList(), anyInt());
        verify(eventExtractionService, never()).runL2Window();
        assertThat(report.detail())
                .contains("l0=pass:4")
                .contains("l1=skip(fused)")
                .contains("l2=skip(fused)");
        assertThat(report.processed()).isEqualTo(5);
    }

    @Test
    void tick_l1ExclusionListPassedToRepository() {
        // aiExclusion=ALL 源条目不进 L1 待处理查询（T125：L1 段排除面）
        when(l0Prefilter.run()).thenReturn(new L0PrefilterService.L0Report(0, 0, 0));
        when(repository.findPendingForL1(anyString(), anyInt(), anyList(), anyInt()))
                .thenReturn(List.of());
        when(exclusionResolver.excludedSourceIds(com.info.platform.domain.feed.AiExclusion.ALL))
                .thenReturn(List.of(21L));

        service.tick();

        verify(repository).findPendingForL1(anyString(), anyInt(), eq(List.of(21L)), anyInt());
    }

    // ---- T125：单条成本校准自动触发（每日上海 23 点后首个 tick，内存当日去重） ----

    @Test
    void tick_afterShanghai2300_triggersCalibrationOncePerDay() {
        // 15:30Z = 23:30 上海 → 触发；同日再 tick 不重复（内存去重）
        service = serviceWithClock(Instant.parse("2026-09-22T15:30:00Z"));
        when(l0Prefilter.run()).thenReturn(new L0PrefilterService.L0Report(0, 0, 0));
        when(repository.findPendingForL1(anyString(), anyInt(), anyList(), anyInt()))
                .thenReturn(List.of());

        service.tick();
        service.tick();

        verify(guardService, times(1)).calibratePerItemCost();
    }

    @Test
    void tick_beforeShanghai2300_calibrationNotTriggered() {
        // 14:59Z = 22:59 上海 → 不触发
        service = serviceWithClock(Instant.parse("2026-09-22T14:59:00Z"));
        when(l0Prefilter.run()).thenReturn(new L0PrefilterService.L0Report(0, 0, 0));
        when(repository.findPendingForL1(anyString(), anyInt(), anyList(), anyInt()))
                .thenReturn(List.of());

        service.tick();

        verify(guardService, never()).calibratePerItemCost();
    }

    private NewsPipelineService serviceWithClock(Instant now) {
        RuntimeConfigService configService = mock(RuntimeConfigService.class);
        when(configService.read(anyString())).thenReturn(Optional.empty());
        return new NewsPipelineService(
                l0Prefilter,
                classificationService,
                eventExtractionService,
                repository,
                new PipelineSettings(configService, new ObjectMapper()),
                guardService,
                exclusionResolver,
                Clock.fixed(now, java.time.ZoneOffset.UTC));
    }
}
