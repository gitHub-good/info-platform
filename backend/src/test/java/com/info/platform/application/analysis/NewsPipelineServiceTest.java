package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigService;
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
 * NewsPipelineService 单测（T121，方案 §4.3/§4.7）：tick 编排（L0→L1 顺序）、段间独立容错（一段失败不阻断另一段与下轮）、 批分片（≤
 * l1BatchSize/批）、空批静默、lastTick 留痕。全 mock。AAA 结构。
 */
class NewsPipelineServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");

    private L0PrefilterService l0Prefilter;
    private ClassificationService classificationService;
    private NewsAnalysisRepository repository;
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
        repository = mock(NewsAnalysisRepository.class);
        RuntimeConfigService configService = mock(RuntimeConfigService.class);
        when(configService.read(anyString())).thenReturn(Optional.empty());
        PipelineSettings settings = new PipelineSettings(configService, new ObjectMapper());
        service =
                new NewsPipelineService(
                        l0Prefilter, classificationService, repository, settings, new FixedClock());
    }

    private static NewsAnalysisRepository.ClassificationCandidate candidate(long id) {
        return new NewsAnalysisRepository.ClassificationCandidate(
                id, "标题" + id, "摘要", "新浪财经", NOW, NOW);
    }

    @Test
    void tick_l0ThenL1_detailsComposed() {
        // Arrange
        when(l0Prefilter.run()).thenReturn(new L0PrefilterService.L0Report(5, 2, 1));
        when(repository.findPendingForL1(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(candidate(1), candidate(2)));
        when(classificationService.classifyBatch(anyList()))
                .thenReturn(new ClassificationService.BatchOutcome(2, 0));

        // Act
        NewsPipelineService.TickReport report = service.tick();

        // Assert
        assertThat(report.detail())
                .isEqualTo("l0=pass:5; noise:2; near_dup:1; l1=done:2; fail:0; pending=2");
        assertThat(report.processed()).isEqualTo(10); // L0 8 条 + L1 done 2

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
        when(repository.findPendingForL1(anyString(), anyInt(), anyInt())).thenReturn(pending);
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
        when(repository.findPendingForL1(anyString(), anyInt(), anyInt())).thenReturn(List.of());

        NewsPipelineService.TickReport report = service.tick();

        assertThat(report.detail())
                .isEqualTo("l0=pass:0; noise:0; near_dup:0; l1=done:0; fail:0; pending=0");
        verify(classificationService, never()).classifyBatch(anyList());
    }

    @Test
    void tick_l0Failure_doesNotBlockL1() {
        // 段间独立容错（ADR-0046 裁决 4）：L0 抛错只置 l0=error，L1 照常执行
        when(l0Prefilter.run()).thenThrow(new RuntimeException("L0 段故障"));
        when(repository.findPendingForL1(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(candidate(1)));
        when(classificationService.classifyBatch(anyList()))
                .thenReturn(new ClassificationService.BatchOutcome(1, 0));

        NewsPipelineService.TickReport report = service.tick();

        assertThat(report.detail()).startsWith("l0=error; ").contains("l1=done:1");
        verify(classificationService).classifyBatch(anyList());
    }

    @Test
    void tick_l1Failure_l0StillReported() {
        when(l0Prefilter.run()).thenReturn(new L0PrefilterService.L0Report(3, 0, 0));
        when(repository.findPendingForL1(anyString(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("L1 段故障"));

        NewsPipelineService.TickReport report = service.tick();

        assertThat(report.detail()).isEqualTo("l0=pass:3; noise:0; near_dup:0; l1=error");
        assertThat(report.processed()).isEqualTo(3); // L0 产出仍计入
    }

    @Test
    void tick_backfillWindowPassedToRepository() {
        when(l0Prefilter.run()).thenReturn(new L0PrefilterService.L0Report(0, 0, 0));
        when(repository.findPendingForL1(anyString(), anyInt(), anyInt())).thenReturn(List.of());

        service.tick();

        // 24h 补跑窗口（缺省）+ maxRetries 3 + tick 上限 400
        verify(repository)
                .findPendingForL1(
                        NOW.minus(Duration.ofHours(24)).toString(),
                        3,
                        PipelineSettings.L1_TICK_CAP);
    }
}
