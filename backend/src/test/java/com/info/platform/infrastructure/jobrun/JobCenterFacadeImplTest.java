package com.info.platform.infrastructure.jobrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigEntry;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.application.jobrun.JobCenterFacade;
import com.info.platform.application.jobrun.JobCenterFacade.JobConfigUpdate;
import com.info.platform.application.jobrun.JobCenterFacade.JobView;
import com.info.platform.application.jobrun.JobRegistry;
import com.info.platform.application.jobrun.ManagedJob;
import com.info.platform.application.jobrun.ScheduleType;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.JobExecutionLog;
import com.info.platform.domain.common.JobExecutionLogRepository;
import com.info.platform.domain.common.JobExecutionStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * JobCenterFacadeImpl 单测（T37，方案 §4.4.3）：总览视图组装（元数据/调度设置/上次执行/运行态/下次执行）+ PATCH 合并写 + 手动触发透传 +
 * 30062/合并语义。AAA 结构（mock 全部协作方，不依赖 Spring 上下文）。
 */
class JobCenterFacadeImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuntimeConfigService configService = mock(RuntimeConfigService.class);
    private final JobExecutionLogRepository logRepository = mock(JobExecutionLogRepository.class);
    private final JobExecutor jobExecutor = mock(JobExecutor.class);
    private final JobScheduler jobScheduler = mock(JobScheduler.class);
    private JobCenterFacade facade;
    private ManagedJob pushRetry;

    @BeforeEach
    void setUp() {
        pushRetry = stubJob("PUSH_RETRY", "PushRetryJob", "推送补推", ScheduleType.FIXED_DELAY);
        facade =
                new JobCenterFacadeImpl(
                        new JobRegistry(List.of(pushRetry)),
                        configService,
                        logRepository,
                        jobExecutor,
                        jobScheduler,
                        Clock.fixed(
                                Instant.parse("2026-09-22T03:00:00Z"), Clock.systemUTC().getZone()),
                        objectMapper);
    }

    private static ManagedJob stubJob(
            String jobKey, String jobName, String displayName, ScheduleType type) {
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
                return displayName;
            }

            @Override
            public String description() {
                return "desc-" + jobKey;
            }

            @Override
            public ScheduleType scheduleType() {
                return type;
            }

            @Override
            public void run() {}
        };
    }

    private void stubEntry(String jobKey, String json, String updatedAt) {
        try {
            when(configService.read("job." + jobKey))
                    .thenReturn(
                            Optional.of(
                                    new RuntimeConfigEntry(
                                            "job." + jobKey,
                                            json,
                                            objectMapper.readTree(json),
                                            null,
                                            Instant.parse(updatedAt))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void view_assemblesMetadataSettingsLastExecutionAndRunning() {
        // Arrange：启用中的间隔型任务，有一条 SUCCESS 留痕
        stubEntry(
                "PUSH_RETRY",
                "{\"enabled\":true,\"scheduleType\":\"FIXED_DELAY\",\"intervalMillis\":30000}",
                "2026-09-22T01:00:00Z");
        when(logRepository.byJobNameCursor("PushRetryJob", null, 1))
                .thenReturn(
                        List.of(
                                JobExecutionLog.reconstruct(
                                        7L,
                                        "PushRetryJob",
                                        Instant.parse("2026-09-22T02:59:30Z"),
                                        Instant.parse("2026-09-22T02:59:31.2Z"),
                                        JobExecutionStatus.SUCCESS,
                                        1200L,
                                        0,
                                        0,
                                        null,
                                        null,
                                        null)));
        when(jobExecutor.isRunning("PUSH_RETRY")).thenReturn(false);
        when(jobScheduler.nextExecutionTime("PUSH_RETRY"))
                .thenReturn(Optional.of(Instant.parse("2026-09-22T03:00:31Z")));

        // Act
        JobView view = facade.view().jobs().get(0);

        // Assert：总览行字段齐备（契约 §4.4.3）
        assertThat(view.jobKey()).isEqualTo("PUSH_RETRY");
        assertThat(view.jobName()).isEqualTo("PushRetryJob");
        assertThat(view.name()).isEqualTo("推送补推");
        assertThat(view.scheduleType()).isEqualTo("FIXED_DELAY");
        assertThat(view.intervalMillis()).isEqualTo(30000L);
        assertThat(view.enabled()).isTrue();
        assertThat(view.running()).isFalse();
        assertThat(view.lastExecution().status()).isEqualTo("SUCCESS");
        assertThat(view.lastExecution().durationMillis()).isEqualTo(1200L);
        assertThat(view.nextExecutionTime()).isNotBlank();
        assertThat(view.effectiveModes())
                .containsEntry("enabled", "LIVE")
                .containsEntry("intervalMillis", "LIVE_NEXT_CYCLE");
    }

    @Test
    void view_neverExecuted_lastExecutionNull() {
        // Arrange：从未执行（PRD 场景 4.5 空态）
        stubEntry(
                "PUSH_RETRY",
                "{\"enabled\":true,\"scheduleType\":\"FIXED_DELAY\",\"intervalMillis\":30000}",
                "2026-09-22T01:00:00Z");
        when(logRepository.byJobNameCursor("PushRetryJob", null, 1)).thenReturn(List.of());
        when(jobScheduler.nextExecutionTime("PUSH_RETRY"))
                .thenReturn(Optional.of(Instant.parse("2026-09-22T03:00:31Z")));

        // Act
        JobView view = facade.view().jobs().get(0);

        // Assert：lastExecution=null，下次执行仍展示
        assertThat(view.lastExecution()).isNull();
        assertThat(view.nextExecutionTime()).isNotBlank();
    }

    @Test
    void view_disabled_nextExecutionTimeNull() {
        stubEntry(
                "PUSH_RETRY",
                "{\"enabled\":false,\"scheduleType\":\"FIXED_DELAY\",\"intervalMillis\":30000}",
                "2026-09-22T01:00:00Z");

        JobView view = facade.view().jobs().get(0);

        assertThat(view.enabled()).isFalse();
        assertThat(view.nextExecutionTime()).isNull();
    }

    @Test
    void update_mergesPatchFieldsAndWritesWholeDoc() throws Exception {
        // Arrange：当前文档 + 落库后回读
        stubEntry(
                "PUSH_RETRY",
                "{\"enabled\":false,\"scheduleType\":\"FIXED_DELAY\",\"intervalMillis\":30000}",
                "2026-09-22T01:00:00Z");
        String updatedJson =
                "{\"enabled\":true,\"scheduleType\":\"FIXED_DELAY\",\"intervalMillis\":60000}";
        when(configService.write(
                        eq("job.PUSH_RETRY"),
                        startsWith("{\"enabled\":true"),
                        eq(Instant.parse("2026-09-22T01:00:00Z"))))
                .thenReturn(
                        new RuntimeConfigEntry(
                                "job.PUSH_RETRY",
                                updatedJson,
                                objectMapper.readTree(updatedJson),
                                null,
                                Instant.parse("2026-09-22T02:00:00Z")));
        when(logRepository.byJobNameCursor(any(), any(), anyInt())).thenReturn(List.of());

        // Act：仅改 enabled 与 intervalMillis（PATCH 部分合并）
        JobView view =
                facade.update(
                        "PUSH_RETRY",
                        new JobConfigUpdate(true, 60000L, null, null, "2026-09-22T01:00:00Z"));

        // Assert：写入为合并后的整体文档，返回视图用新值
        verify(configService)
                .write(
                        eq("job.PUSH_RETRY"),
                        eq(updatedJson),
                        eq(Instant.parse("2026-09-22T01:00:00Z")));
        assertThat(view.enabled()).isTrue();
        assertThat(view.intervalMillis()).isEqualTo(60000L);
    }

    @Test
    void update_unknownJob_throws30062() {
        assertThatThrownBy(
                        () ->
                                facade.update(
                                        "NOPE", new JobConfigUpdate(true, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.JOB_NOT_FOUND));
    }

    @Test
    void trigger_delegatesToExecutorWithStartedStatus() {
        // Arrange
        when(jobExecutor.triggerNow(pushRetry)).thenReturn(new JobExecutor.TriggerOutcome(123L));

        // Act
        JobCenterFacade.TriggerResult result = facade.trigger("PUSH_RETRY");

        // Assert：受理即返（executionId=留痕 id，状态 STARTED）
        verify(jobExecutor).triggerNow(pushRetry);
        assertThat(result.executionId()).isEqualTo(123L);
        assertThat(result.status()).isEqualTo("STARTED");
    }

    @Test
    void trigger_running_propagates30063() {
        // Arrange：运行守卫冲突（JobExecutor 抛 30063）
        when(jobExecutor.triggerNow(pushRetry))
                .thenThrow(new BusinessException(ErrorCode.JOB_ALREADY_RUNNING, "运行中"));

        // Act + Assert：防重入错误码透传到接口层
        assertThatThrownBy(() -> facade.trigger("PUSH_RETRY"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.JOB_ALREADY_RUNNING));
    }
}
