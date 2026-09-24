package com.info.platform.application.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.info.platform.application.common.RuntimeConfigEntry;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.application.jobrun.JobCenterFacade;
import com.info.platform.application.jobrun.JobCenterFacade.JobView;
import com.info.platform.application.jobrun.JobCenterFacade.TriggerResult;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.JobExecutionLog;
import com.info.platform.domain.common.JobExecutionLogRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

/**
 * SubjectSyncJob 收编集成测试（T53，技术方案增补 §4.6/§6）：任务中心自动可见（注册即权威，种子 job.SUBJECT_SYNC 在测试 profile 停用不注册调度）
 * + 手动触发全链路（受理 → SubjectSyncJob.run → 留痕 STARTED→SUCCESS，复用 JobExecutor 通道） + 运行中再触发 30063（CAS
 * 防重入复用验证）。
 *
 * <p>{@code SubjectSyncService} 以 {@code @MockBean} 替换——真实实现会经 EastMoneyListClient 外呼
 * push2（测试红线禁止）； 触发链路验证的恰是 Job 壳的委托与留痕，与引擎内部无关。
 */
@SpringBootTest
@ActiveProfiles("test")
class SubjectSyncJobIntegrationTest {

    private static final String JOB_KEY = "SUBJECT_SYNC";

    private static final String JOB_NAME = "SubjectSyncJob";

    private static final Duration DEADLINE = Duration.ofSeconds(10);

    @MockBean private SubjectSyncService subjectSyncService;

    @Autowired private JobCenterFacade facade;

    @Autowired private RuntimeConfigService configService;

    @Autowired private JobExecutionLogRepository logRepository;

    @Autowired private DataSource dataSource;

    @AfterEach
    void cleanExecutionLogs() {
        withLockRetry(
                () -> {
                    try (Connection connection = dataSource.getConnection();
                            PreparedStatement delete =
                                    connection.prepareStatement(
                                            "DELETE FROM job_execution_log WHERE job_name = ?")) {
                        delete.setString(1, JOB_NAME);
                        return delete.executeUpdate();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
    }

    @Test
    void jobCenter_autoVisible_seededDisabledInTestProfileWithProductionCron() {
        // 注册即权威：任务中心列表自动出现「标的池同步」（无前端改动）
        JobView view = viewOf(JOB_KEY);
        assertThat(view.jobName()).isEqualTo(JOB_NAME);
        assertThat(view.name()).isEqualTo("标的池同步");
        assertThat(view.scheduleType()).isEqualTo("CRON");
        assertThat(view.enabled()).as("测试 profile 种子停用（yml subject.sync.enabled=false）").isFalse();
        assertThat(view.nextExecutionTime()).as("停用不注册调度").isNull();

        // 种子形态对照 §4.6：cron 取主 application.yml 默认（0 0 6 * * ?），enabled 由测试 yml 覆盖为 false
        String seedJson =
                configService
                        .read("job." + JOB_KEY)
                        .map(RuntimeConfigEntry::json)
                        .orElseThrow(() -> new IllegalStateException("缺 job.SUBJECT_SYNC 种子"));
        assertThat(seedJson)
                .contains("\"scheduleType\":\"CRON\"")
                .contains("\"cron\":\"0 0 6 * * ?\"")
                .contains("\"enabled\":false");
    }

    @Test
    void triggerFullFlow_runsJobAndPersistsSuccess() {
        when(subjectSyncService.syncAll())
                .thenReturn(
                        List.of(
                                new MarketSyncResult(
                                        MarketSyncSpec.A_SHARE_STOCK,
                                        5561,
                                        2,
                                        5559,
                                        0,
                                        1,
                                        5563,
                                        95000),
                                new MarketSyncResult(
                                        MarketSyncSpec.HK_STOCK,
                                        2927,
                                        0,
                                        2927,
                                        0,
                                        0,
                                        2927,
                                        30000)));

        TriggerResult accepted = facade.trigger(JOB_KEY);

        assertThat(accepted.status()).isEqualTo("STARTED");
        JobExecutionLog finalLog = awaitTerminal(accepted.executionId());
        assertThat(finalLog.getJobName()).isEqualTo(JOB_NAME);
        assertThat(finalLog.getStatus().name()).isEqualTo("SUCCESS");
        assertThat(finalLog.getErrorMessage()).isEmpty();
    }

    @Test
    void triggerWhileRunning_rejected30063_reusesCasGuard() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(subjectSyncService.syncAll())
                .thenAnswer(
                        invocation -> {
                            running.countDown();
                            finish.await();
                            return List.of();
                        });

        TriggerResult accepted = facade.trigger(JOB_KEY);
        assertThat(accepted.status()).isEqualTo("STARTED");
        // 模拟引擎在跑（手动触发为异步受理，等待真正进入 run()）
        assertThat(running.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        // 运行中再触发：CAS 守卫拒绝（防重入红线 30063，复用 JobExecutor 通道——无需 Job 自防）
        assertThatThrownBy(() -> facade.trigger(JOB_KEY))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("SUBJECT_SYNC")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.JOB_ALREADY_RUNNING);

        finish.countDown();
        // 让出写入窗口：异步线程的 success 留痕（毫秒级）先完成，测试线程再开始轮询——
        // 共享缓存锁下读方在写方持锁瞬间的读会让 recorder 落痕失败（内部容错 no-op，行将永远停留 STARTED）
        sleepQuietly(400);
        awaitTerminal(accepted.executionId());
    }

    private JobView viewOf(String jobKey) {
        return facade.view().jobs().stream()
                .filter(j -> j.jobKey().equals(jobKey))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("任务未注册: " + jobKey));
    }

    /** 轮询留痕至终态（SUCCESS/FAILED）或超时失败（异步虚拟线程执行，共享缓存锁瞬时冲突短退避重试）。 */
    private JobExecutionLog awaitTerminal(long executionId) {
        Instant deadline = Instant.now().plus(DEADLINE);
        Optional<JobExecutionLog> latest = Optional.empty();
        while (Instant.now().isBefore(deadline)) {
            latest =
                    withLockRetry(
                            () ->
                                    logRepository.byJobNameCursor(JOB_NAME, null, 20).stream()
                                            .filter(log -> log.getId() == executionId)
                                            .findFirst());
            if (latest.isPresent() && !"STARTED".equals(latest.orElseThrow().getStatus().name())) {
                return latest.orElseThrow();
            }
            sleepQuietly(100);
        }
        throw new IllegalStateException(
                "等待任务留痕终态超时 executionId=" + executionId + " lastSeen=" + latest);
    }

    /**
     * 共享缓存内存库上，异步执行线程与测试线程的跨连接读写可能瞬时碰撞（SQLITE_LOCKED_SHAREDCACHE，busy_timeout 不覆盖共享缓存表锁），
     * 统一短退避重试（写方毫秒级完成，重试必达）——同 {@code JobCenterFlowIntegrationTest} 先例。
     */
    private static <T> T withLockRetry(java.util.function.Supplier<T> action) {
        Instant deadline = Instant.now().plus(DEADLINE);
        RuntimeException last = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                if (!isTransientLock(e)) {
                    throw e;
                }
                last = e;
                sleepQuietly(100);
            }
        }
        throw new IllegalStateException("共享缓存锁重试超时: " + last, last);
    }

    private static boolean isTransientLock(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String message = String.valueOf(t.getMessage());
            if (message.contains("SQLITE_LOCKED") || message.contains("SQLITE_BUSY")) {
                return true;
            }
        }
        return false;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("轮询被中断", e);
        }
    }
}
