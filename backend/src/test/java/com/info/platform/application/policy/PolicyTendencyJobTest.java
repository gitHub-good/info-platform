package com.info.platform.application.policy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.domain.policy.AiTendency;
import com.info.platform.domain.policy.PolicyItem;
import com.info.platform.domain.policy.PolicyRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * PolicyTendencyJob 单测（T28）：扫 ai_tendency=0 待判条目 → 逐条判断 → 单条容错不阻断。AAA 结构。
 *
 * <p>不加载 Spring 上下文（Job 受 @ConditionalOnProperty 约束，测试 profile 不装配）；直接构造 Job 实例调 {@link
 * PolicyTendencyJob#judgePending}（@Scheduled 方法包/公可见，单测可直调，对齐 DailyRecommendationJobTest 模式）。
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
        when(repository.findRecentUnjudged(anyInt(), anyInt())).thenReturn(List.of());
        PolicyTendencyJob job = new PolicyTendencyJob(service, repository, 7, 20);

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
        when(repository.findRecentUnjudged(anyInt(), anyInt())).thenReturn(pending);
        when(service.judgeTendency(pending.get(0))).thenReturn(AiTendency.BULLISH);
        when(service.judgeTendency(pending.get(1))).thenReturn(AiTendency.BULLISH);
        when(service.judgeTendency(pending.get(2))).thenReturn(AiTendency.UNJUDGED);

        PolicyTendencyJob job = new PolicyTendencyJob(service, repository, 7, 20);

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
        when(repository.findRecentUnjudged(anyInt(), anyInt())).thenReturn(pending);
        when(service.judgeTendency(pending.get(0))).thenThrow(new RuntimeException("未预期"));
        when(service.judgeTendency(pending.get(1))).thenReturn(AiTendency.NEUTRAL);

        PolicyTendencyJob job = new PolicyTendencyJob(service, repository, 7, 20);

        // Act：不应抛
        job.judgePending();

        // Assert：异常条目后继续处理次条
        verify(service, times(1)).judgeTendency(pending.get(1));
    }

    @Test
    void judgePending_nonPositiveBatchSize_defaultsTo20() {
        // Arrange：batch-size 配 0 → 兜底 20，传给 repository 的 limit 应为 20
        when(repository.findRecentUnjudged(anyInt(), anyInt())).thenReturn(List.of());
        PolicyTendencyJob job = new PolicyTendencyJob(service, repository, 7, 0);

        // Act
        job.judgePending();

        // Assert：limit=20（兜底）
        verify(repository).findRecentUnjudged(7, 20);
    }

    @Test
    void constructor_passesConfiguredDaysAndBatchSize() {
        // Arrange：自定义 days=3 / batchSize=5
        when(repository.findRecentUnjudged(anyInt(), anyInt())).thenReturn(List.of());
        PolicyTendencyJob job = new PolicyTendencyJob(service, repository, 3, 5);

        // Act
        job.judgePending();

        // Assert：传 days=3 / limit=5
        verify(repository).findRecentUnjudged(3, 5);
        verify(service, never()).judgeTendency(any());
    }
}
