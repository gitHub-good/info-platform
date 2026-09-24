package com.info.platform.application.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.domain.policy.AiTendency;
import com.info.platform.domain.policy.PolicyItem;
import com.info.platform.domain.policy.PolicyRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * PolicyTendencyJob 单测（T28）：扫 ai_tendency=0 待判条目 → 逐条判断 → 单条容错不阻断。AAA 结构。
 *
 * <p>不加载 Spring 上下文（Job 受 @ConditionalOnProperty 约束，测试 profile 不装配）；直接构造 Job 实例调 {@link
 * PolicyTendencyJob#judgePending}（@Scheduled 方法包/公可见，单测可直调，对齐 DailyRecommendationJobTest 模式）。
 *
 * <p>P0-3 回归（系统体检 20260924）：判断未成功（UNJUDGED/未预期异常）→ {@code recordTendencyAttempt}
 * 留痕一次尝试；扫描按上限/退避窗参数下发（过滤在仓储 findRecentUnjudged 落地，集成侧另有覆盖）。
 */
class PolicyTendencyJobTest {

    private final PolicyTendencyService service = mock(PolicyTendencyService.class);
    private final PolicyRepository repository = mock(PolicyRepository.class);

    private static PolicyItem unjudged(long id, String title) {
        return PolicyItem.reconstruct(
                id,
                title,
                "国务院政策",
                LocalDate.of(2026, 9, 20),
                "摘要",
                List.of("新能源"),
                AiTendency.UNJUDGED,
                "https://gov/" + id,
                null,
                null);
    }

    @Test
    void judgePending_noPending_skipsWithoutCallingService() {
        // Arrange：无可判条目
        when(repository.findRecentUnjudged(anyInt(), anyInt(), anyInt(), any(Instant.class)))
                .thenReturn(List.of());
        PolicyTendencyJob job = new PolicyTendencyJob(service, repository, 7, 20, 3, 2);

        // Act
        job.judgePending();

        // Assert：空跑，不调 service
        verify(service, never()).judgeTendency(any());
    }

    @Test
    void judgePending_pendingItems_judgesEach() {
        // Arrange：3 条待判，前两条利好、第三条未判（解析失败返未判）
        List<PolicyItem> pending =
                List.of(unjudged(1L, "政策A"), unjudged(2L, "政策B"), unjudged(3L, "政策C"));
        when(repository.findRecentUnjudged(anyInt(), anyInt(), anyInt(), any(Instant.class)))
                .thenReturn(pending);
        when(service.judgeTendency(pending.get(0))).thenReturn(AiTendency.BULLISH);
        when(service.judgeTendency(pending.get(1))).thenReturn(AiTendency.BULLISH);
        when(service.judgeTendency(pending.get(2))).thenReturn(AiTendency.UNJUDGED);

        PolicyTendencyJob job = new PolicyTendencyJob(service, repository, 7, 20, 3, 2);

        // Act
        job.judgePending();

        // Assert：逐条判断，3 条都处理
        verify(service, times(1)).judgeTendency(pending.get(0));
        verify(service, times(1)).judgeTendency(pending.get(1));
        verify(service, times(1)).judgeTendency(pending.get(2));
    }

    @Test
    void judgePending_singleItemUnexpectedException_doesNotBlockOthers() {
        // Arrange：首条 service 抛未预期异常（judgeTendency 内部已吞 LLM/业务异常，此处兜底未预期）
        List<PolicyItem> pending = List.of(unjudged(1L, "政策A"), unjudged(2L, "政策B"));
        when(repository.findRecentUnjudged(anyInt(), anyInt(), anyInt(), any(Instant.class)))
                .thenReturn(pending);
        when(service.judgeTendency(pending.get(0))).thenThrow(new RuntimeException("未预期"));
        when(service.judgeTendency(pending.get(1))).thenReturn(AiTendency.NEUTRAL);

        PolicyTendencyJob job = new PolicyTendencyJob(service, repository, 7, 20, 3, 2);

        // Act：不应抛
        job.judgePending();

        // Assert：异常条目后继续处理次条
        verify(service, times(1)).judgeTendency(pending.get(1));
    }

    @Test
    void judgePending_nonPositiveBatchSize_defaultsTo20() {
        // Arrange：batch-size 配 0 → 兜底 20，传给 repository 的 limit 应为 20
        when(repository.findRecentUnjudged(anyInt(), anyInt(), anyInt(), any(Instant.class)))
                .thenReturn(List.of());
        PolicyTendencyJob job = new PolicyTendencyJob(service, repository, 7, 0, 3, 2);

        // Act
        job.judgePending();

        // Assert：limit=20（兜底）
        verify(repository).findRecentUnjudged(eq(7), eq(20), eq(3), any(Instant.class));
    }

    @Test
    void constructor_passesConfiguredDaysAndBatchSize() {
        // Arrange：自定义 days=3 / batchSize=5
        when(repository.findRecentUnjudged(anyInt(), anyInt(), anyInt(), any(Instant.class)))
                .thenReturn(List.of());
        PolicyTendencyJob job = new PolicyTendencyJob(service, repository, 3, 5, 3, 2);

        // Act
        job.judgePending();

        // Assert：传 days=3 / limit=5
        verify(repository).findRecentUnjudged(eq(3), eq(5), eq(3), any(Instant.class));
        verify(service, never()).judgeTendency(any());
    }

    // ==================== P0-3（系统体检 20260924）：尝试留痕 + 上限/退避参数下发 ====================

    @Test
    void judgePending_passesRetryGovernanceParams_backoffCutoffTwoHoursAgo() {
        // Arrange：捕获下发 findRecentUnjudged 的重试治理参数
        when(repository.findRecentUnjudged(anyInt(), anyInt(), anyInt(), any(Instant.class)))
                .thenReturn(List.of());
        PolicyTendencyJob job = new PolicyTendencyJob(service, repository, 7, 20, 3, 2);

        // Act
        job.judgePending();

        // Assert：maxAttempts=3；退避截止=now-2h（±10s 容差，防执行耗时抖动）
        ArgumentCaptor<Integer> attemptsCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(repository)
                .findRecentUnjudged(anyInt(), anyInt(), attemptsCaptor.capture(), cutoffCaptor.capture());
        assertThat(attemptsCaptor.getValue()).isEqualTo(3);
        assertThat(cutoffCaptor.getValue())
                .isCloseTo(
                        Instant.now().minus(Duration.ofHours(2)),
                        within(10, ChronoUnit.SECONDS));
    }

    @Test
    void judgePending_configGuards_nonPositiveValuesFallBackToDefaults() {
        // Arrange：max-attempts=0 / retry-backoff-hours=-1 → 兜底 3 / 2
        when(repository.findRecentUnjudged(anyInt(), anyInt(), anyInt(), any(Instant.class)))
                .thenReturn(List.of());
        PolicyTendencyJob job = new PolicyTendencyJob(service, repository, 7, 20, 0, -1);

        // Act
        job.judgePending();

        // Assert
        ArgumentCaptor<Integer> attemptsCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(repository)
                .findRecentUnjudged(anyInt(), anyInt(), attemptsCaptor.capture(), cutoffCaptor.capture());
        assertThat(attemptsCaptor.getValue())
                .isEqualTo(PolicyTendencyJob.DEFAULT_MAX_ATTEMPTS);
        assertThat(cutoffCaptor.getValue())
                .isCloseTo(
                        Instant.now()
                                .minus(Duration.ofHours(PolicyTendencyJob.DEFAULT_BACKOFF_HOURS)),
                        within(10, ChronoUnit.SECONDS));
    }

    @Test
    void judgePending_unjudgedOutcome_recordsAttempt() {
        // Arrange：判断失败（返 UNJUDGED）——须留痕一次尝试（上限/退避过滤的计数源）
        List<PolicyItem> pending = List.of(unjudged(1L, "政策A"));
        when(repository.findRecentUnjudged(anyInt(), anyInt(), anyInt(), any(Instant.class)))
                .thenReturn(pending);
        when(service.judgeTendency(pending.get(0))).thenReturn(AiTendency.UNJUDGED);
        PolicyTendencyJob job = new PolicyTendencyJob(service, repository, 7, 20, 3, 2);

        // Act
        job.judgePending();

        // Assert
        verify(repository, times(1)).recordTendencyAttempt(1L);
    }

    @Test
    void judgePending_successOutcome_doesNotRecordAttempt() {
        // Arrange：判断成功填倾向——成功路径不走尝试留痕（出扫靠 ai_tendency≠0）
        List<PolicyItem> pending = List.of(unjudged(1L, "政策A"));
        when(repository.findRecentUnjudged(anyInt(), anyInt(), anyInt(), any(Instant.class)))
                .thenReturn(pending);
        when(service.judgeTendency(pending.get(0))).thenReturn(AiTendency.BULLISH);
        PolicyTendencyJob job = new PolicyTendencyJob(service, repository, 7, 20, 3, 2);

        // Act
        job.judgePending();

        // Assert
        verify(repository, never()).recordTendencyAttempt(anyLong());
    }

    @Test
    void judgePending_unexpectedException_recordsAttempt() {
        // Arrange：service 抛未预期异常（LLM 调用可能已发起并失败）——同样留痕一次尝试
        List<PolicyItem> pending = List.of(unjudged(1L, "政策A"));
        when(repository.findRecentUnjudged(anyInt(), anyInt(), anyInt(), any(Instant.class)))
                .thenReturn(pending);
        when(service.judgeTendency(pending.get(0))).thenThrow(new RuntimeException("未预期"));
        PolicyTendencyJob job = new PolicyTendencyJob(service, repository, 7, 20, 3, 2);

        // Act：不应抛
        job.judgePending();

        // Assert
        verify(repository, times(1)).recordTendencyAttempt(1L);
    }
}
