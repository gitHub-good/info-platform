package com.info.platform.application.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.application.aggregation.DataSourceConfigFacade;
import com.info.platform.application.aggregation.DataSourceConfigFacade.AggregationView;
import com.info.platform.application.aggregation.DataSourceConfigFacade.DataSourceConfigView;
import com.info.platform.application.aggregation.DataSourceConfigFacade.HealthView;
import com.info.platform.application.aggregation.DataSourceConfigFacade.SourceCardView;
import com.info.platform.application.common.OverviewService.AnomalyTodayCard;
import com.info.platform.application.common.OverviewService.JobHealthCard;
import com.info.platform.application.common.OverviewService.LlmTodayCard;
import com.info.platform.application.common.OverviewService.OverviewView;
import com.info.platform.application.common.OverviewService.Policy24hCard;
import com.info.platform.application.common.OverviewService.SourceHealthItem;
import com.info.platform.application.jobrun.JobRegistry;
import com.info.platform.application.jobrun.ManagedJob;
import com.info.platform.application.jobrun.ScheduleType;
import com.info.platform.domain.ai.LlmCallLog;
import com.info.platform.domain.ai.LlmCallLogRepository;
import com.info.platform.domain.ai.LlmCostBudget;
import com.info.platform.domain.ai.LlmUsage;
import com.info.platform.domain.common.JobExecutionLog;
import com.info.platform.domain.common.JobExecutionLogRepository;
import com.info.platform.domain.common.JobExecutionStatus;
import com.info.platform.domain.policy.PolicyItem;
import com.info.platform.domain.policy.PolicyRepository;
import com.info.platform.domain.push.AnomalyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * OverviewService 单测（T42，方案 §4.6 口径）：今日日界/滚动 24h 窗口、探活剔除（scene=test）、 预算三态阈值、jobName→jobKey
 * 映射、卡片级容错（单卡失败不拖垮整页）、空数据全零不报错。
 *
 * <p>固定时钟 2026-09-22T06:30:00Z@Asia/Shanghai：今日日界 = 2026-09-21T16:00:00Z，滚动 24h 起点 =
 * 2026-09-21T06:30:00Z。
 */
class OverviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-22T06:30:00Z");
    private static final Instant TODAY_START = Instant.parse("2026-09-21T16:00:00Z");
    private static final Instant SINCE_24H = Instant.parse("2026-09-21T06:30:00Z");

    private final LlmCallLogRepository llmCallLogRepository = mock(LlmCallLogRepository.class);
    private final LlmCostBudget budgetPolicy = mock(LlmCostBudget.class);
    private final AnomalyRepository anomalyRepository = mock(AnomalyRepository.class);
    private final PolicyRepository policyRepository = mock(PolicyRepository.class);
    private final JobExecutionLogRepository jobLogRepository =
            mock(JobExecutionLogRepository.class);
    private final DataSourceConfigFacade dataSourceConfigFacade =
            mock(DataSourceConfigFacade.class);

    private OverviewService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneId.of("Asia/Shanghai"));
        JobRegistry registry =
                new JobRegistry(
                        List.of(
                                jobOf("PUSH_RETRY", "PushRetryJob"),
                                jobOf("ANOMALY_DETECT", "AnomalyDetectionJob")));
        service =
                new OverviewService(
                        llmCallLogRepository,
                        budgetPolicy,
                        anomalyRepository,
                        policyRepository,
                        jobLogRepository,
                        dataSourceConfigFacade,
                        registry,
                        clock);

        lenient().when(budgetPolicy.dailyBudgetTokens()).thenReturn(20_000L);
        lenient().when(budgetPolicy.budgetWarnRatio()).thenReturn(0.8);
        lenient()
                .when(llmCallLogRepository.findCreatedSince(any(), anyInt()))
                .thenReturn(List.of());
        lenient().when(anomalyRepository.countTriggeredSince(any())).thenReturn(0L);
        lenient().when(policyRepository.countCreatedSince(any())).thenReturn(0L);
        lenient()
                .when(policyRepository.findLatestCreatedSince(any(), anyInt()))
                .thenReturn(List.of());
        lenient().when(jobLogRepository.countSince(any())).thenReturn(0L);
        lenient().when(jobLogRepository.countFailedSince(any())).thenReturn(0L);
        lenient().when(jobLogRepository.findFailedSince(any(), anyInt())).thenReturn(List.of());
        lenient()
                .when(jobLogRepository.byJobNameCursor(any(), any(), anyInt()))
                .thenReturn(List.of());
        lenient()
                .when(dataSourceConfigFacade.view())
                .thenReturn(
                        new DataSourceConfigView(
                                List.of(
                                        new SourceCardView(
                                                "QUOTE",
                                                "行情源",
                                                true,
                                                "REAL",
                                                1500,
                                                0,
                                                5,
                                                30,
                                                Map.of(),
                                                new HealthView("OK", "2026-09-22T02:00:00Z", 0),
                                                "2026-09-22T01:00:00Z",
                                                Map.of())),
                                new AggregationView(2000, "2026-09-22T01:00:00Z", Map.of())));
    }

    // —— 主路径：五卡片聚合 + 口径 ——

    @Test
    void view_llmToday_countsSuccessTokensExcludingConnectivityTestRows() {
        LlmCallLog business = LlmCallLog.begin(1, "1");
        business.markSuccess("deepseek", "deepseek-flash", new LlmUsage(800, 200), 12_345, 10);
        LlmCallLog probe = LlmCallLog.begin(1, "test");
        probe.markSuccess("deepseek", "deepseek-flash", new LlmUsage(5000, 4000), 999, 10);
        LlmCallLog failed = LlmCallLog.begin(1, "1");
        failed.markFailed("deepseek→glm", 10);
        when(llmCallLogRepository.findCreatedSince(any(), anyInt()))
                .thenReturn(List.of(business, probe, failed));

        OverviewView view = service.view();

        LlmTodayCard card = view.llmToday();
        assertThat(card.tokenUsed()).isEqualTo(1000); // 仅 SUCCESS 业务行，探活与失败行不计
        assertThat(card.costMicros()).isEqualTo(12_345); // 探活成本剔除
        assertThat(card.budgetTokens()).isEqualTo(20_000); // 运行时当前预算（LlmCostBudget 端口）
        assertThat(card.status()).isEqualTo("OK");
        assertThat(card.error()).isNull();

        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(llmCallLogRepository).findCreatedSince(since.capture(), anyInt());
        assertThat(since.getValue()).isEqualTo(TODAY_START); // 今日日界（系统时区 00:00）
    }

    @Test
    void view_llmToday_statusThresholds_warningThenExhausted() {
        when(budgetPolicy.dailyBudgetTokens()).thenReturn(2000L);
        when(budgetPolicy.budgetWarnRatio()).thenReturn(0.5);

        LlmCallLog warn = LlmCallLog.begin(1, "1");
        warn.markSuccess("deepseek", "m", new LlmUsage(1000, 0), 0, 10);
        when(llmCallLogRepository.findCreatedSince(any(), anyInt())).thenReturn(List.of(warn));
        assertThat(service.view().llmToday().status()).isEqualTo("WARNING"); // 用量 = warnLine(1000)

        LlmCallLog exhausted = LlmCallLog.begin(1, "1");
        exhausted.markSuccess("deepseek", "m", new LlmUsage(2000, 0), 0, 10);
        when(llmCallLogRepository.findCreatedSince(any(), anyInt())).thenReturn(List.of(exhausted));
        assertThat(service.view().llmToday().status()).isEqualTo("EXHAUSTED"); // 用量 ≥ 预算
    }

    @Test
    void view_anomalyToday_countsFromTodayBoundaryNotRolling24h() {
        when(anomalyRepository.countTriggeredSince(any())).thenReturn(3L);

        AnomalyTodayCard card = service.view().anomalyToday();

        assertThat(card.count()).isEqualTo(3);
        assertThat(card.error()).isNull();
        verify(anomalyRepository).countTriggeredSince(TODAY_START); // 今日日界，非 now-24h
    }

    @Test
    void view_policy24h_countsRolling24hByCreatedAtAndMapsLatestItems() {
        when(policyRepository.countCreatedSince(any())).thenReturn(12L);
        when(policyRepository.findLatestCreatedSince(any(), anyInt()))
                .thenReturn(
                        List.of(
                                policyItem(101L, "政策甲", "2026-09-21"),
                                policyItem(102L, "政策乙", "2026-09-20")));

        Policy24hCard card = service.view().policy24h();

        assertThat(card.count()).isEqualTo(12);
        assertThat(card.error()).isNull();
        assertThat(card.latest()).hasSize(2);
        assertThat(card.latest().get(0).id()).isEqualTo(101L);
        assertThat(card.latest().get(0).title()).isEqualTo("政策甲");
        assertThat(card.latest().get(0).publishedAt()).isEqualTo("2026-09-21");
        verify(policyRepository).countCreatedSince(SINCE_24H); // 滚动 24h
        verify(policyRepository).findLatestCreatedSince(SINCE_24H, 5);
    }

    @Test
    void view_jobHealth_unhealthyJobsFromWindowFailuresAndLatestFailed() {
        when(jobLogRepository.countSince(any())).thenReturn(41L);
        when(jobLogRepository.countFailedSince(any())).thenReturn(2L);
        JobExecutionLog pushRetryFailure =
                JobExecutionLog.reconstruct(
                        1L,
                        "PushRetryJob",
                        NOW,
                        NOW,
                        JobExecutionStatus.FAILED,
                        null,
                        0,
                        1,
                        "boom",
                        null,
                        null);
        JobExecutionLog legacyFailure =
                JobExecutionLog.reconstruct(
                        2L,
                        "LegacyGoneJob",
                        NOW,
                        NOW,
                        JobExecutionStatus.FAILED,
                        null,
                        0,
                        1,
                        "gone",
                        null,
                        null);
        when(jobLogRepository.findFailedSince(any(), anyInt()))
                .thenReturn(List.of(pushRetryFailure, legacyFailure));
        when(jobLogRepository.byJobNameCursor(eq("PushRetryJob"), any(), anyInt()))
                .thenReturn(
                        List.of(
                                JobExecutionLog.reconstruct(
                                        3L,
                                        "PushRetryJob",
                                        NOW,
                                        NOW,
                                        JobExecutionStatus.SUCCESS,
                                        null,
                                        0,
                                        0,
                                        null,
                                        null,
                                        null))); // 最新一次成功：不重复计入
        when(jobLogRepository.byJobNameCursor(eq("AnomalyDetectionJob"), any(), anyInt()))
                .thenReturn(
                        List.of(
                                JobExecutionLog.reconstruct(
                                        4L,
                                        "AnomalyDetectionJob",
                                        NOW,
                                        NOW,
                                        JobExecutionStatus.FAILED,
                                        null,
                                        0,
                                        1,
                                        "err",
                                        null,
                                        null))); // 最近一次执行 FAILED

        JobHealthCard card = service.view().jobHealth();

        assertThat(card.windowRuns()).isEqualTo(41);
        assertThat(card.windowFailed()).isEqualTo(2);
        assertThat(card.unhealthyJobs())
                .containsExactly("PUSH_RETRY", "LegacyGoneJob", "ANOMALY_DETECT");
        assertThat(card.error()).isNull();
        verify(jobLogRepository).countSince(SINCE_24H);
    }

    @Test
    void view_sourceHealth_mapsFacadeCardsWithModeAndHeartbeat() {
        List<SourceHealthItem> sources = service.view().sourceHealth();

        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).sourceCode()).isEqualTo("QUOTE");
        assertThat(sources.get(0).mode()).isEqualTo("REAL");
        assertThat(sources.get(0).lastEventType()).isEqualTo("OK"); // 含 OK 心跳口径
        assertThat(sources.get(0).lastEventAt()).isEqualTo("2026-09-22T02:00:00Z");
        assertThat(sources.get(0).errors24h()).isZero();
        assertThat(service.view().sourceHealthError()).isNull();
    }

    // —— 空态：全零不报错 ——

    @Test
    void view_emptyData_allCardsZeroWithoutError() {
        OverviewView view = service.view();

        assertThat(view.llmToday()).isEqualTo(new LlmTodayCard(0, 0, 20_000, "OK", null));
        assertThat(view.anomalyToday()).isEqualTo(new AnomalyTodayCard(0, null));
        assertThat(view.policy24h().count()).isZero();
        assertThat(view.policy24h().latest()).isEmpty();
        assertThat(view.policy24h().error()).isNull();
        assertThat(view.jobHealth()).isEqualTo(new JobHealthCard(0, 0, List.of(), null));
        assertThat(view.sourceHealth()).isNotEmpty(); // 7 源卡片结构恒在（无事件源 health 两字段 null）
    }

    // —— 异常路径：卡片级容错，单卡失败不拖垮整页 ——

    @Test
    void view_policyCardFails_returnsCardErrorOthersIntact() {
        when(policyRepository.countCreatedSince(any()))
                .thenThrow(new RuntimeException("database is locked"));

        OverviewView view = service.view();

        assertThat(view.policy24h().error()).contains("database is locked");
        assertThat(view.policy24h().count()).isZero();
        assertThat(view.policy24h().latest()).isEmpty();
        assertThat(view.llmToday().error()).isNull();
        assertThat(view.anomalyToday().error()).isNull();
        assertThat(view.jobHealth().error()).isNull();
        assertThat(view.sourceHealthError()).isNull();
    }

    @Test
    void view_sourceHealthCardFails_returnsSiblingErrorWithEmptyList() {
        when(dataSourceConfigFacade.view()).thenThrow(new IllegalStateException("snapshot absent"));

        OverviewView view = service.view();

        assertThat(view.sourceHealth()).isEmpty();
        assertThat(view.sourceHealthError()).contains("snapshot absent");
        assertThat(view.llmToday().error()).isNull();
        assertThat(view.policy24h().error()).isNull();
    }

    @Test
    void view_llmTodayFails_returnsZeroCardWithError() {
        when(llmCallLogRepository.findCreatedSince(any(), anyInt()))
                .thenThrow(new RuntimeException("disk I/O error"));

        LlmTodayCard card = service.view().llmToday();

        assertThat(card.error()).contains("disk I/O error");
        assertThat(card.tokenUsed()).isZero();
        assertThat(card.budgetTokens()).isZero(); // 取数失败时预算兜底 0，前端按 error 态展示
        assertThat(service.view().anomalyToday().error()).isNull();
    }

    // —— 辅助 ——

    /** 已落库形态的政策条目（id 非空，供 latest 映射）。 */
    private static PolicyItem policyItem(long id, String title, String publishedAt) {
        return PolicyItem.reconstruct(
                id,
                title,
                "国务院政策",
                LocalDate.parse(publishedAt),
                null,
                List.of(),
                null,
                null,
                null,
                null);
    }

    /** 真实 JobRegistry 用最小 ManagedJob 桩（jobName 对齐 job_execution_log.job_name 派生规则）。 */
    private static ManagedJob jobOf(String jobKey, String jobName) {
        return new ManagedJob() {
            @Override
            public String jobKey() {
                return jobKey;
            }

            @Override
            public String jobName() {
                return jobName;
            }

            @Override
            public String displayName() {
                return jobKey;
            }

            @Override
            public String description() {
                return "";
            }

            @Override
            public ScheduleType scheduleType() {
                return ScheduleType.FIXED_DELAY;
            }

            @Override
            public void run() {}
        };
    }
}
