package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.info.platform.domain.ai.LlmCallLog;
import com.info.platform.domain.ai.LlmCallLogRepository;
import com.info.platform.domain.ai.LlmCallStatus;
import com.info.platform.domain.ai.LlmProvider;
import com.info.platform.domain.analysis.NewsAnalysisRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PipelineStatusService 单测（T121 基础版，方案 §4.8）：当日三态计数聚合、Asia/Shanghai 日界折算 UTC、成本只计 scene=5
 * SUCCESS、lastTick 透传。全 mock。AAA 结构。
 */
class PipelineStatusServiceTest {

    // 2026-09-22 17:30 上海 = 09:30 UTC → 当日零点（上海）= 前一日 16:00 UTC
    private static final Instant NOW = Instant.parse("2026-09-22T09:30:00Z");

    private NewsAnalysisRepository repository;
    private LlmCallLogRepository llmCallLogRepository;
    private NewsPipelineService pipelineService;
    private PipelineStatusService service;

    @BeforeEach
    void setUp() {
        repository = mock(NewsAnalysisRepository.class);
        llmCallLogRepository = mock(LlmCallLogRepository.class);
        pipelineService = mock(NewsPipelineService.class);
        service =
                new PipelineStatusService(
                        repository,
                        llmCallLogRepository,
                        pipelineService,
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static LlmCallLog costLog(String sceneKey, LlmCallStatus status, long micros) {
        LlmCallLog log = LlmCallLog.begin(0L, sceneKey);
        log.markSuccess(
                LlmProvider.DEEPSEEK.configName(),
                "deepseek-flash",
                new com.info.platform.domain.ai.LlmUsage(100, 50),
                micros,
                100L);
        return log;
    }

    @Test
    void status_countsAndCostComposed() {
        // Arrange
        when(repository.countL0ByResultSince("2026-09-21T16:00:00Z"))
                .thenReturn(Map.of("PASS", 620L, "NOISE", 8L, "NEAR_DUP", 12L));
        when(repository.countL1ByStatusSince("2026-09-21T16:00:00Z"))
                .thenReturn(Map.of("DONE", 600L, "PENDING", 5L, "FAILED", 2L));
        when(llmCallLogRepository.findCreatedSince(any(Instant.class), anyInt()))
                .thenReturn(
                        List.of(
                                costLog("5", LlmCallStatus.SUCCESS, 9115L),
                                costLog("5", LlmCallStatus.SUCCESS, 4560L),
                                costLog("1", LlmCallStatus.SUCCESS, 999_999L), // 非管道 scene 不计
                                failedLog()));
        when(pipelineService.lastTick())
                .thenReturn(
                        new NewsPipelineService.LastTick(
                                NOW.minusSeconds(600),
                                NOW.minusSeconds(540),
                                "l0=pass:3; l1=done:3"));

        // Act
        PipelineStatusView view = service.status();

        // Assert
        assertThat(view.jobKey()).isEqualTo("NEWS_PIPELINE");
        assertThat(view.today().l0Pass()).isEqualTo(620L);
        assertThat(view.today().l0Noise()).isEqualTo(8L);
        assertThat(view.today().l0NearDup()).isEqualTo(12L);
        assertThat(view.today().l1Done()).isEqualTo(600L);
        assertThat(view.today().l1Pending()).isEqualTo(5L);
        assertThat(view.today().l1Failed()).isEqualTo(2L);
        assertThat(view.todayCostMicros()).isEqualTo(9115L + 4560L); // 只计 scene=5 SUCCESS
        assertThat(view.lastTick().startedAt()).isEqualTo("2026-09-22T09:20:00Z");
        assertThat(view.lastTick().detail()).isEqualTo("l0=pass:3; l1=done:3");
    }

    private static LlmCallLog failedLog() {
        LlmCallLog log = LlmCallLog.begin(0L, "5");
        log.markFailed("所有 provider 失败", 100L);
        return log;
    }

    @Test
    void status_missingStatesCountedAsZero() {
        when(repository.countL0ByResultSince(anyString())).thenReturn(Map.of("PASS", 3L));
        when(repository.countL1ByStatusSince(anyString())).thenReturn(Map.of());
        when(llmCallLogRepository.findCreatedSince(any(Instant.class), anyInt()))
                .thenReturn(List.of());

        PipelineStatusView view = service.status();

        assertThat(view.today().l0Pass()).isEqualTo(3L);
        assertThat(view.today().l0Noise()).isZero();
        assertThat(view.today().l1Done()).isZero();
        assertThat(view.todayCostMicros()).isZero();
        assertThat(view.lastTick()).isNull(); // 未跑过批窗口
    }

    @Test
    void status_shanghaiDayBoundaryConvertedToUtc() {
        // 次日 00:30 上海 = 前日 16:30 UTC → 窗口起点 = 当日上海零点（2026-09-22T16:00:00Z）
        service =
                new PipelineStatusService(
                        repository,
                        llmCallLogRepository,
                        pipelineService,
                        Clock.fixed(Instant.parse("2026-09-22T16:30:00Z"), ZoneOffset.UTC));
        when(repository.countL0ByResultSince(anyString())).thenReturn(Map.of());
        when(repository.countL1ByStatusSince(anyString())).thenReturn(Map.of());
        when(llmCallLogRepository.findCreatedSince(any(Instant.class), anyInt()))
                .thenReturn(List.of());

        service.status();

        verifyWindowStart("2026-09-22T16:00:00Z");
    }

    private void verifyWindowStart(String expected) {
        org.mockito.Mockito.verify(repository).countL0ByResultSince(expected);
        org.mockito.Mockito.verify(repository).countL1ByStatusSince(expected);
    }
}
