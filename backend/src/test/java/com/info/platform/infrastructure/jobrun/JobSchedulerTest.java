package com.info.platform.infrastructure.jobrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigChangedEvent;
import com.info.platform.application.common.RuntimeConfigEntry;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.application.jobrun.JobRegistry;
import com.info.platform.application.jobrun.ManagedJob;
import com.info.platform.application.jobrun.ScheduleType;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * JobScheduler 单测（T37，方案 §4.5 / ADR-0017 热切换语义）：停用零注册 / 启停热切换（配置事件取消·重注册，下一周期 生效）/ 下次执行推算 / cron
 * 型注册 / 非法参数兜底不注册。AAA 结构。
 *
 * <p>使用真实 {@code ThreadPoolTaskScheduler}（轻量、daemon 线程）+ mock 配置服务与执行器，不依赖 Spring 上下文； FIXED_DELAY
 * 注册首跑立即（对齐原 @Scheduled fixedDelay initialDelay=0 语义），runScheduled 为 mock 无副作用。
 */
class JobSchedulerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuntimeConfigService configService = mock(RuntimeConfigService.class);
    private final JobExecutor jobExecutor = mock(JobExecutor.class);
    private JobScheduler scheduler;
    private ManagedJob fixedDelayJob;
    private ManagedJob cronJob;

    @BeforeEach
    void setUp() {
        fixedDelayJob = stubJob("POLICY_FETCH", ScheduleType.FIXED_DELAY);
        cronJob = stubJob("DAILY_RECOMMEND", ScheduleType.CRON);
        scheduler =
                new JobScheduler(
                        new JobRegistry(List.of(fixedDelayJob, cronJob)),
                        jobExecutor,
                        configService,
                        Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        // 触发 @PreDestroy 等价清理，停掉调度线程
        scheduler.shutdown();
    }

    private static ManagedJob stubJob(String jobKey, ScheduleType type) {
        return new ManagedJob() {
            @Override
            public String jobKey() {
                return jobKey;
            }

            @Override
            public String displayName() {
                return jobKey;
            }

            @Override
            public String description() {
                return jobKey;
            }

            @Override
            public ScheduleType scheduleType() {
                return type;
            }

            @Override
            public void run() {}
        };
    }

    private void stubDoc(String jobKey, String json) {
        try {
            when(configService.read("job." + jobKey))
                    .thenReturn(
                            Optional.of(
                                    new RuntimeConfigEntry(
                                            "job." + jobKey,
                                            json,
                                            objectMapper.readTree(json),
                                            null,
                                            null)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void ready_allDisabled_registersNothing() {
        // Arrange：测试 profile 语义——种子全部 enabled=false
        stubDoc(
                "POLICY_FETCH",
                "{\"enabled\":false,\"scheduleType\":\"FIXED_DELAY\",\"intervalMillis\":3600000}");
        stubDoc(
                "DAILY_RECOMMEND",
                "{\"enabled\":false,\"scheduleType\":\"CRON\",\"cron\":\"0 0 9 * * ?\",\"userIds\":\"\"}");

        // Act
        scheduler.onApplicationReady();

        // Assert：零注册（@Scheduled 时代测试隔离语义等价平移）
        assertThat(scheduler.nextExecutionTime("POLICY_FETCH")).isEmpty();
        assertThat(scheduler.nextExecutionTime("DAILY_RECOMMEND")).isEmpty();
    }

    @Test
    void ready_enabledJobs_haveNextExecutionTime() {
        // Arrange：间隔型启用（首跑立即）+ cron 型启用
        stubDoc(
                "POLICY_FETCH",
                "{\"enabled\":true,\"scheduleType\":\"FIXED_DELAY\",\"intervalMillis\":3600000}");
        stubDoc(
                "DAILY_RECOMMEND",
                "{\"enabled\":true,\"scheduleType\":\"CRON\",\"cron\":\"0 0 9 * * ?\",\"userIds\":\"\"}");
        Instant before = Instant.now();

        // Act
        scheduler.onApplicationReady();

        // Assert：下次执行时间可推算（间隔型 = 首跑后 1h；cron 型 = 下一个 09:00），不早于注册时刻
        assertThat(scheduler.nextExecutionTime("POLICY_FETCH"))
                .isPresent()
                .get()
                .satisfies(t -> assertThat(t.isAfter(before.minusSeconds(1))).isTrue());
        assertThat(scheduler.nextExecutionTime("DAILY_RECOMMEND")).isPresent();
    }

    @Test
    void configChanged_disable_cancelsRegistration() {
        // Arrange：先启用注册
        stubDoc(
                "POLICY_FETCH",
                "{\"enabled\":true,\"scheduleType\":\"FIXED_DELAY\",\"intervalMillis\":3600000}");
        scheduler.onApplicationReady();
        assertThat(scheduler.nextExecutionTime("POLICY_FETCH")).isPresent();

        // Act：页面停用（配置事件热切换——启停立即生效）
        stubDoc(
                "POLICY_FETCH",
                "{\"enabled\":false,\"scheduleType\":\"FIXED_DELAY\",\"intervalMillis\":3600000}");
        scheduler.onRuntimeConfigChanged(
                new RuntimeConfigChangedEvent("job.POLICY_FETCH", Instant.now()));

        // Assert：调度已取消（不再有下次执行时间）
        assertThat(scheduler.nextExecutionTime("POLICY_FETCH")).isEmpty();
    }

    @Test
    void configChanged_enableAfterDisabled_registers() {
        // Arrange：启动时停用（零注册）
        stubDoc(
                "POLICY_FETCH",
                "{\"enabled\":false,\"scheduleType\":\"FIXED_DELAY\",\"intervalMillis\":60000}");
        scheduler.onApplicationReady();
        assertThat(scheduler.nextExecutionTime("POLICY_FETCH")).isEmpty();

        // Act：页面启用 → 事件重注册
        stubDoc(
                "POLICY_FETCH",
                "{\"enabled\":true,\"scheduleType\":\"FIXED_DELAY\",\"intervalMillis\":60000}");
        scheduler.onRuntimeConfigChanged(
                new RuntimeConfigChangedEvent("job.POLICY_FETCH", Instant.now()));

        // Assert：已注册（热启用不重启）
        assertThat(scheduler.nextExecutionTime("POLICY_FETCH")).isPresent();
    }

    @Test
    void configChanged_nonJobKey_ignored() {
        // Arrange：启用注册
        stubDoc(
                "POLICY_FETCH",
                "{\"enabled\":true,\"scheduleType\":\"FIXED_DELAY\",\"intervalMillis\":3600000}");
        scheduler.onApplicationReady();

        // Act：非 job 域键变更不影响
        stubDoc(
                "POLICY_FETCH",
                "{\"enabled\":false,\"scheduleType\":\"FIXED_DELAY\",\"intervalMillis\":3600000}");
        scheduler.onRuntimeConfigChanged(
                new RuntimeConfigChangedEvent("llm.global", Instant.now()));

        // Assert：仍注册
        assertThat(scheduler.nextExecutionTime("POLICY_FETCH")).isPresent();
    }

    @Test
    void reschedule_corruptedInterval_skipsRegistration() {
        // Arrange：存量种子损坏（间隔非正）——写路径已拦，此处兜底
        stubDoc(
                "POLICY_FETCH",
                "{\"enabled\":true,\"scheduleType\":\"FIXED_DELAY\",\"intervalMillis\":0}");

        // Act：不应抛
        scheduler.onApplicationReady();

        // Assert：不注册
        assertThat(scheduler.nextExecutionTime("POLICY_FETCH")).isEmpty();
    }

    @Test
    void reschedule_missingKey_treatedAsDisabled() {
        // Arrange：键缺失（种子前）
        when(configService.read("job.POLICY_FETCH")).thenReturn(Optional.empty());

        // Act
        scheduler.onApplicationReady();

        // Assert：不注册（宁可不调度不带病运行）
        assertThat(scheduler.nextExecutionTime("POLICY_FETCH")).isEmpty();
    }
}
