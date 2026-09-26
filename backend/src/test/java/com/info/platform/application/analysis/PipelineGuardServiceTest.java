package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.domain.ai.LlmCallLog;
import com.info.platform.domain.ai.LlmCallLogRepository;
import com.info.platform.domain.ai.LlmProvider;
import com.info.platform.domain.analysis.GuardLevel;
import com.info.platform.domain.push.PipelineFusedEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * PipelineGuardService 单测（T125，方案 §3.5/§4.6 裁决 5）：llm_call_log scene 5/6/7 当日 SUCCESS 成本口径、60%/90%
 * 两级派生边界 （≥ 含边界）、FUSED 告警一次节流、DEGRADED 不告警、换日窗口滚动自动恢复、校准值写入（Σcost ÷ 当日净入库）。全 mock。AAA 结构。
 */
class PipelineGuardServiceTest {

    // 2026-09-22 17:30 上海 = 09:30 UTC → 当日零点（上海）= 2026-09-21T16:00:00Z
    private static final Instant NOW = Instant.parse("2026-09-22T09:30:00Z");

    /** 预算键缺省：¥2/日 = 2,000,000 微元 → 降级阈值 1,200,000 / 熔断阈值 1,800,000。 */
    private static final long BUDGET = 2_000_000L;

    private static final long DEGRADE_AT = 1_200_000L;

    private static final long FUSE_AT = 1_800_000L;

    private LlmCallLogRepository llmCallLogRepository;
    private ApplicationEventPublisher eventPublisher;
    private RuntimeConfigService configService;
    private NewsAnalysisRepositoryStub repository;
    private PipelineGuardService service;

    /** 成本可控的 news_analysis 仓储桩（校准值分母：当日净入库条数）。 */
    private static final class NewsAnalysisRepositoryStub
            implements com.info.platform.domain.analysis.NewsAnalysisRepository {

        private long newsCount = 0;

        @Override
        public long countNewsItemsCreatedSince(String createdSinceIso) {
            return newsCount;
        }

        @Override
        public int failOrphanExtractedRows() {
            return 0; // OBS-04 一次性归位（T130）——护栏测试不消费
        }

        @Override
        public int insertIgnoreBatch(List<com.info.platform.domain.analysis.NewsAnalysis> rows) {
            return 0;
        }

        @Override
        public List<NewsCandidate> findUnanalyzed(
                String createdBeforeIso, List<Long> excludeSourceIds, int limit) {
            return List.of();
        }

        @Override
        public List<NewsCandidate> findPassPoolSince(String publishedSinceIso, int limit) {
            return List.of();
        }

        @Override
        public List<ClassificationCandidate> findPendingForL1(
                String createdSinceIso, int maxAttempts, List<Long> excludeSourceIds, int limit) {
            return List.of();
        }

        @Override
        public int applyL1Result(L1Write write) {
            return 0;
        }

        @Override
        public int markL1Failed(List<Long> newsIds) {
            return 0;
        }

        @Override
        public List<L2Candidate> findL2Candidates(
                String todayStartIso,
                String backfillSinceIso,
                int maxAttempts,
                List<Long> excludeSourceIds,
                int limit) {
            return List.of();
        }

        @Override
        public int updateImportanceScores(java.util.Map<Long, Double> scoresByNewsId) {
            return 0;
        }

        @Override
        public int markL2Selected(List<Long> newsIds) {
            return 0;
        }

        @Override
        public int markL2Deferred(List<Long> newsIds) {
            return 0;
        }

        @Override
        public int applyL2Result(L2Write write) {
            return 0;
        }

        @Override
        public int markL2Failed(List<Long> newsIds) {
            return 0;
        }

        @Override
        public long countL1DoneSince(String createdSinceIso) {
            return 0;
        }

        @Override
        public long countL2ProcessedSince(String sinceIso) {
            return 0;
        }

        @Override
        public java.util.Map<String, Long> countL2ByStatusSince(String createdSinceIso) {
            return java.util.Map.of();
        }

        @Override
        public java.util.Map<String, Long> countL0ByResultSince(String createdSinceIso) {
            return java.util.Map.of();
        }

        @Override
        public java.util.Map<String, Long> countL1ByStatusSince(String createdSinceIso) {
            return java.util.Map.of();
        }

        @Override
        public L1SlaStats countL1SlaSince(String createdSinceIso) {
            return new L1SlaStats(0, 0);
        }
    }

    @BeforeEach
    void setUp() {
        llmCallLogRepository = mock(LlmCallLogRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        configService = mock(RuntimeConfigService.class);
        when(configService.read(any())).thenReturn(Optional.empty());
        repository = new NewsAnalysisRepositoryStub();
        service = guardWithClock(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private PipelineGuardService guardWithClock(Clock clock) {
        return new PipelineGuardService(
                llmCallLogRepository,
                repository,
                configService,
                new PipelineSettings(configService, new ObjectMapper()),
                eventPublisher,
                new ObjectMapper(),
                clock);
    }

    private static LlmCallLog costLog(String sceneKey, long micros) {
        LlmCallLog log = LlmCallLog.begin(0L, sceneKey);
        log.markSuccess(
                LlmProvider.DEEPSEEK.configName(),
                "deepseek-flash",
                new com.info.platform.domain.ai.LlmUsage(100, 50),
                micros,
                100L);
        return log;
    }

    private static LlmCallLog failedLog(String sceneKey) {
        LlmCallLog log = LlmCallLog.begin(0L, sceneKey);
        log.markFailed("所有 provider 失败", 100L);
        return log;
    }

    private void givenCostLogs(List<LlmCallLog> logs) {
        when(llmCallLogRepository.findCreatedSince(any(Instant.class), anyInt())).thenReturn(logs);
    }

    @Test
    void level_belowDegradeThreshold_isNormal() {
        // 1,199,999 微元 < 60%×2,000,000（59.99995%）→ NORMAL
        givenCostLogs(
                List.of(
                        costLog("5", 600_000L),
                        costLog("6", 500_000L),
                        costLog("7", 99_999L),
                        costLog("1", 5_000_000L), // 非管道 scene 不计
                        failedLog("5"))); // FAILED 不计

        assertThat(service.todayCostMicros()).isEqualTo(1_199_999L);
        assertThat(service.currentLevel()).isEqualTo(GuardLevel.NORMAL);
        verify(eventPublisher, never()).publishEvent(any(PipelineFusedEvent.class));
    }

    @Test
    void level_atDegradeBoundary_isDegraded() {
        // 恰 60%（1,200,000 = 0.6×2,000,000）→ DEGRADED（≥ 含边界，方案 §4.6）
        givenCostLogs(List.of(costLog("5", DEGRADE_AT)));

        assertThat(service.currentLevel()).isEqualTo(GuardLevel.DEGRADED);
        verify(eventPublisher, never()).publishEvent(any(PipelineFusedEvent.class)); // 降级不告警
    }

    @Test
    void level_belowFuseBoundary_isDegraded() {
        // 1,799,999 < 90% → 仍 DEGRADED
        givenCostLogs(List.of(costLog("6", FUSE_AT - 1)));

        assertThat(service.currentLevel()).isEqualTo(GuardLevel.DEGRADED);
    }

    @Test
    void level_atFuseBoundary_isFused_andAlertsOnce() {
        // 恰 90%（1,800,000）→ FUSED；首次进入发布一次告警，重复评估不重发（内存节流）
        givenCostLogs(List.of(costLog("5", FUSE_AT)));

        assertThat(service.currentLevel()).isEqualTo(GuardLevel.FUSED);
        ArgumentCaptor<PipelineFusedEvent> captor =
                ArgumentCaptor.forClass(PipelineFusedEvent.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());
        assertThat(captor.getValue().costMicros()).isEqualTo(FUSE_AT);
        assertThat(captor.getValue().budgetMicros()).isEqualTo(BUDGET);

        // 同一告警 episode 内第二次评估：仍 FUSED，不再发
        assertThat(service.currentLevel()).isEqualTo(GuardLevel.FUSED);
        verify(eventPublisher, times(1)).publishEvent(any(PipelineFusedEvent.class));
    }

    @Test
    void level_dayRollover_recoversToNormal_andRearmsAlert() {
        // 换日查询窗口滚动：同一成本留痕在次日窗口外 → NORMAL（自动恢复零代码，裁决 5）
        givenCostLogs(List.of(costLog("5", FUSE_AT)));
        assertThat(service.currentLevel()).isEqualTo(GuardLevel.FUSED);

        // 次日 00:30 上海（2026-09-22T16:30:00Z）窗口内无留痕
        when(llmCallLogRepository.findCreatedSince(any(Instant.class), anyInt()))
                .thenReturn(List.of());
        service =
                guardWithClock(Clock.fixed(Instant.parse("2026-09-22T16:30:00Z"), ZoneOffset.UTC));

        assertThat(service.currentLevel()).isEqualTo(GuardLevel.NORMAL);
    }

    @Test
    void calibrate_writesPerItemCostAndBumpsBasis() {
        // 首跑校准（REQ 拍板四-4）：Σcost ÷ 当日净入库 = 1,350,000 ÷ 600 = 2250 微元/条，写 pipeline.budget 并升版
        // costBasis
        givenCostLogs(List.of(costLog("5", 1_350_000L)));
        repository.newsCount = 600;

        long calibrated = service.calibratePerItemCost();

        assertThat(calibrated).isEqualTo(2_250L);
        org.mockito.ArgumentCaptor<String> jsonCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(configService)
                .write(
                        org.mockito.ArgumentMatchers.eq("pipeline.budget"),
                        jsonCaptor.capture(),
                        any());
        assertThat(jsonCaptor.getValue())
                .contains("\"calibratedPerItemMicros\":2250")
                .contains("\"costBasis\":\"cost-v2:calibrated:2026-09-22\"")
                .contains("\"dailyBudgetMicros\":2000000"); // 既有字段保留（整体替换语义）
    }

    @Test
    void calibrate_noIntakeToday_skipsWrite() {
        // 当日无净入库（分母 0）→ 不写（-1 哨兵），避免以 0 条分母写出天文数字
        givenCostLogs(List.of(costLog("5", 1_350_000L)));
        repository.newsCount = 0;

        assertThat(service.calibratePerItemCost()).isEqualTo(-1L);
        verify(configService, never()).write(any(), any(), any());
    }
}
