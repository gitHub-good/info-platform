package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.application.recommendation.RecommendationSettings;
import com.info.platform.domain.analysis.GuardLevel;
import com.info.platform.domain.analysis.L0Result;
import com.info.platform.domain.analysis.NewsAnalysis;
import com.info.platform.domain.analysis.NewsAnalysisRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 快速通道服务单测（T131，ADR-0051 裁决 1 / 方案 §4.3）：选取窗口（30s 缓冲下传）、预筛分阈值边界（4.0 过/3.99 等价形态 不过）、 noise
 * 条目交常规批、FUSED 全跳、DEGRADED 跳 L2 保 L1、L0 复用建行 + importance_score 落库、L1 只归类本批 PASS 行、tick 明细。 AAA
 * 结构，mock 协作服务（LLM 零外呼）+ 缺省参数 Settings。
 */
class PipelineExpressServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");

    private NewsAnalysisRepository repository;

    private L0PrefilterService l0Prefilter;

    private ClassificationService classificationService;

    private EventExtractionService eventExtractionService;

    private SubjectMatcher subjectMatcher;

    private AiExclusionResolver exclusionResolver;

    private PipelineGuardService guardService;

    private PipelineExpressService service;

    @BeforeEach
    void setUp() {
        repository = mock(NewsAnalysisRepository.class);
        l0Prefilter = mock(L0PrefilterService.class);
        classificationService = mock(ClassificationService.class);
        eventExtractionService = mock(EventExtractionService.class);
        subjectMatcher = mock(SubjectMatcher.class);
        exclusionResolver = mock(AiExclusionResolver.class);
        guardService = mock(PipelineGuardService.class);
        when(exclusionResolver.excludedSourceIds(any())).thenReturn(List.of());
        when(subjectMatcher.match(anyString(), anyString())).thenReturn(List.of());
        when(guardService.currentLevel()).thenReturn(GuardLevel.NORMAL);
        RuntimeConfigService configService = mock(RuntimeConfigService.class);
        when(configService.read(anyString())).thenReturn(Optional.empty());
        service =
                new PipelineExpressService(
                        repository,
                        l0Prefilter,
                        classificationService,
                        eventExtractionService,
                        subjectMatcher,
                        exclusionResolver,
                        guardService,
                        new PipelineSettings(configService, new ObjectMapper()),
                        new RecommendationSettings(configService, new ObjectMapper()),
                        Clock.fixed(NOW, java.time.ZoneOffset.UTC));
    }

    private static NewsAnalysisRepository.NewsCandidate candidate(
            long id, String title, String sourceCategory) {
        return new NewsAnalysisRepository.NewsCandidate(
                id,
                1L,
                null,
                title,
                "摘要",
                sourceCategory,
                NOW.minusSeconds(120),
                NOW.minusSeconds(60));
    }

    private void stubCandidates(NewsAnalysisRepository.NewsCandidate... candidates) {
        when(repository.findUnanalyzed(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(candidates));
        when(repository.findPassPoolSince(anyString(), anyInt())).thenReturn(List.of());
    }

    private void stubRowsForHits(List<NewsAnalysisRepository.NewsCandidate> hits) {
        List<NewsAnalysis> rows =
                hits.stream()
                        .map(c -> NewsAnalysis.newForL0(c.newsId(), L0Result.PASS, null, null))
                        .toList();
        when(l0Prefilter.buildRowsForSurvived(anyList(), anyList())).thenReturn(rows);
        when(repository.insertIgnoreBatch(anyList())).thenReturn(rows.size());
        when(repository.findPendingForL1(anyString(), anyInt(), anyList(), anyInt()))
                .thenReturn(
                        hits.stream()
                                .map(
                                        c ->
                                                new NewsAnalysisRepository.ClassificationCandidate(
                                                        c.newsId(),
                                                        c.title(),
                                                        c.summary(),
                                                        "源",
                                                        c.publishedAt(),
                                                        c.createdAt()))
                                .toList());
    }

    @Test
    void tick_fusedGuard_skipsEverything() {
        when(guardService.currentLevel()).thenReturn(GuardLevel.FUSED);

        PipelineExpressService.ExpressReport report = service.tick();

        assertThat(report.detail()).isEqualTo("express=skip(fused)");
        verify(repository, never()).findUnanalyzed(anyString(), anyList(), anyInt());
    }

    @Test
    void tick_selectionWindow_uses30sBuffer() {
        stubCandidates();

        service.tick();

        // 选取窗口：created_at <= now-30s（快速通道缓冲，方案 §3.1）
        verify(repository)
                .findUnanalyzed(
                        org.mockito.ArgumentMatchers.eq(NOW.minusSeconds(30).toString()),
                        anyList(),
                        anyInt());
    }

    @Test
    void tick_thresholdBoundary_scoreFourPasses_lowerWaitsRegularBatch() {
        // 宏观 2.0 + 强触发「回购」2.0 = 4.0 → 命中；媒体 1.0 + 强触发 2.0 = 3.0 → 等常规批
        NewsAnalysisRepository.NewsCandidate hit = candidate(1, "某公司公告回购股份计划", "宏观");
        NewsAnalysisRepository.NewsCandidate miss = candidate(2, "某公司公告回购股份计划", "媒体");
        stubCandidates(hit, miss);

        service.tick();

        ArgumentCaptor<List<NewsAnalysisRepository.NewsCandidate>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(l0Prefilter).buildRowsForSurvived(captor.capture(), anyList());
        assertThat(captor.getValue())
                .extracting(NewsAnalysisRepository.NewsCandidate::newsId)
                .containsExactly(1L);
    }

    @Test
    void tick_noiseCandidate_waitsRegularBatch_notBuiltInExpress() {
        // noise 条目不在快速通道建行（避免噪音词误配强触发，方案 §4.3）
        NewsAnalysisRepository.NewsCandidate noise = candidate(1, "开户礼佣金万二广告推广", "宏观");
        NewsAnalysisRepository.NewsCandidate hit = candidate(2, "央行降准落地释放流动性", "宏观");
        stubCandidates(noise, hit);

        service.tick();

        ArgumentCaptor<List<NewsAnalysisRepository.NewsCandidate>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(l0Prefilter).buildRowsForSurvived(captor.capture(), anyList());
        assertThat(captor.getValue())
                .extracting(NewsAnalysisRepository.NewsCandidate::newsId)
                .containsExactly(2L);
    }

    @Test
    void tick_noHits_silentReport_noInsertNoL1NoL2() {
        stubCandidates(candidate(1, "一般资讯标题", "媒体"));

        PipelineExpressService.ExpressReport report = service.tick();

        assertThat(report.detail()).isEqualTo("express=scan:1; hit:0");
        verify(repository, never()).insertIgnoreBatch(anyList());
        verify(classificationService, never()).classifyBatch(anyList());
        verify(eventExtractionService, never()).runL2Window();
    }

    @Test
    void tick_normalPath_buildsRowsUpdatesScoresClassifiesAndRunsL2() {
        NewsAnalysisRepository.NewsCandidate hit = candidate(11, "央行宣布降准0.5个百分点", "宏观");
        stubCandidates(hit);
        stubRowsForHits(List.of(hit));
        when(classificationService.classifyBatch(anyList()))
                .thenReturn(new ClassificationService.BatchOutcome(1, 0));
        when(eventExtractionService.runL2Window())
                .thenReturn(new EventExtractionService.L2Report(1, 1, 0, 0, 0));

        PipelineExpressService.ExpressReport report = service.tick();

        // importance_score 落库（本批 PASS 行）
        ArgumentCaptor<Map<Long, Double>> scores = ArgumentCaptor.forClass(Map.class);
        verify(repository).updateImportanceScores(scores.capture());
        assertThat(scores.getValue()).containsEntry(11L, 4.0);
        // L1 归类本批
        ArgumentCaptor<List<NewsAnalysisRepository.ClassificationCandidate>> batch =
                ArgumentCaptor.forClass(List.class);
        verify(classificationService).classifyBatch(batch.capture());
        assertThat(batch.getValue()).hasSize(1);
        // L2 当日重扫
        verify(eventExtractionService).runL2Window();
        assertThat(report.detail()).isEqualTo("express=scan:1; hit:1; l0=1; l1=1; fail:0; l2=1");
        assertThat(report.processed()).isEqualTo(3);
    }

    @Test
    void tick_degradedGuard_skipsL2KeepsL1() {
        when(guardService.currentLevel()).thenReturn(GuardLevel.DEGRADED);
        NewsAnalysisRepository.NewsCandidate hit = candidate(21, "央行宣布降准0.5个百分点", "宏观");
        stubCandidates(hit);
        stubRowsForHits(List.of(hit));
        when(classificationService.classifyBatch(anyList()))
                .thenReturn(new ClassificationService.BatchOutcome(1, 0));

        PipelineExpressService.ExpressReport report = service.tick();

        verify(classificationService).classifyBatch(anyList());
        verify(eventExtractionService, never()).runL2Window();
        assertThat(report.detail()).endsWith("l2=skip(degraded)");
    }

    @Test
    void tick_l1FiltersToExpressInsertedRowsOnly() {
        // 常规批遗留的 PENDING 行（id 99）不在 express L1 批内——只归类本批建行条目
        NewsAnalysisRepository.NewsCandidate hit = candidate(31, "央行宣布降准0.5个百分点", "宏观");
        stubCandidates(hit);
        stubRowsForHits(List.of(hit));
        NewsAnalysisRepository.ClassificationCandidate stale =
                new NewsAnalysisRepository.ClassificationCandidate(
                        99L, "历史条目", "摘要", "源", NOW.minusSeconds(7200), NOW.minusSeconds(7000));
        NewsAnalysisRepository.ClassificationCandidate fresh =
                new NewsAnalysisRepository.ClassificationCandidate(
                        31L,
                        "央行宣布降准0.5个百分点",
                        "摘要",
                        "源",
                        NOW.minusSeconds(120),
                        NOW.minusSeconds(60));
        when(repository.findPendingForL1(anyString(), anyInt(), anyList(), anyInt()))
                .thenReturn(List.of(stale, fresh));

        service.tick();

        ArgumentCaptor<List<NewsAnalysisRepository.ClassificationCandidate>> batch =
                ArgumentCaptor.forClass(List.class);
        verify(classificationService).classifyBatch(batch.capture());
        assertThat(batch.getValue())
                .extracting(NewsAnalysisRepository.ClassificationCandidate::newsId)
                .containsExactly(31L);
    }
}
