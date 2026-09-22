package com.info.platform.application.ai;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigEntry;
import com.info.platform.application.common.RuntimeConfigService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * DailyRecommendationJob 单测（T23；T37 收编后 user-ids 改每轮现读 runtime_config）：user-id 列表解析 + 盘前预热调用 +
 * 单用户容错 + 配置缺失空跑。AAA 结构。
 *
 * <p>不加载 Spring 上下文；直接构造 Job 实例调 {@link DailyRecommendationJob#prefetchDaily}（mock
 * RuntimeConfigService 返回 {@code job.DAILY_RECOMMEND} 文档，对齐 AnomalyDetectionJobTest 直调模式）。
 */
class DailyRecommendationJobTest {

    private final DailyRecommendationService service = mock(DailyRecommendationService.class);
    private final RuntimeConfigService runtimeConfig = mock(RuntimeConfigService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        when(runtimeConfig.read("job.DAILY_RECOMMEND")).thenReturn(Optional.empty());
    }

    private void stubUserIds(String userIds) {
        String json =
                "{\"enabled\":false,\"scheduleType\":\"CRON\",\"cron\":\"0 0 9 * * ?\",\"userIds\":\""
                        + userIds
                        + "\"}";
        try {
            when(runtimeConfig.read("job.DAILY_RECOMMEND"))
                    .thenReturn(
                            Optional.of(
                                    new RuntimeConfigEntry(
                                            "job.DAILY_RECOMMEND",
                                            json,
                                            objectMapper.readTree(json),
                                            null,
                                            null)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void prefetchDaily_configAbsent_skipsWithoutCallingService() {
        // Arrange：键缺失（种子前兜底）
        DailyRecommendationJob job = new DailyRecommendationJob(service, runtimeConfig);

        // Act
        job.prefetchDaily();

        // Assert：空跑，不调 generateDaily
        verify(service, never()).generateDaily(anyLong());
    }

    @Test
    void prefetchDaily_emptyUserIds_skipsWithoutCallingService() {
        // Arrange：未配置 user-ids
        stubUserIds("");
        DailyRecommendationJob job = new DailyRecommendationJob(service, runtimeConfig);

        // Act
        job.prefetchDaily();

        // Assert：空跑，不调 generateDaily
        verify(service, never()).generateDaily(anyLong());
    }

    @Test
    void prefetchDaily_validUserIds_generatesForEach() {
        // Arrange
        when(service.generateDaily(anyLong()))
                .thenReturn(
                        new DailyRecommendationResult(
                                DailyRecommendationResult.STATUS_DONE,
                                java.util.List.of(),
                                "AI 生成，非投资建议",
                                false));
        stubUserIds("1, 2");
        DailyRecommendationJob job = new DailyRecommendationJob(service, runtimeConfig);

        // Act
        job.prefetchDaily();

        // Assert：两个 user-id 各预热一次
        verify(service, times(1)).generateDaily(1L);
        verify(service, times(1)).generateDaily(2L);
    }

    @Test
    void prefetchDaily_invalidEntriesSkipped() {
        // Arrange：含非数字项，跳过
        when(service.generateDaily(anyLong()))
                .thenReturn(
                        new DailyRecommendationResult(
                                DailyRecommendationResult.STATUS_FALLBACK,
                                java.util.List.of(),
                                "AI 生成，非投资建议",
                                true));
        stubUserIds("1,bad,2,,");
        DailyRecommendationJob job = new DailyRecommendationJob(service, runtimeConfig);

        // Act
        job.prefetchDaily();

        // Assert：仅 1 与 2 被调（bad / 空 跳过）
        verify(service, times(1)).generateDaily(1L);
        verify(service, times(1)).generateDaily(2L);
    }

    @Test
    void prefetchDaily_singleUserFailure_doesNotBlockOthers() {
        // Arrange：首用户抛异常，次用户正常——单用户异常不阻断其余
        when(service.generateDaily(1L)).thenThrow(new RuntimeException("LLM 网关故障"));
        when(service.generateDaily(2L))
                .thenReturn(
                        new DailyRecommendationResult(
                                DailyRecommendationResult.STATUS_DONE,
                                java.util.List.of(),
                                "AI 生成，非投资建议",
                                false));
        stubUserIds("1,2");
        DailyRecommendationJob job = new DailyRecommendationJob(service, runtimeConfig);

        // Act：不应抛
        job.prefetchDaily();

        // Assert：异常用户后继续处理次用户
        verify(service, times(1)).generateDaily(2L);
    }
}
