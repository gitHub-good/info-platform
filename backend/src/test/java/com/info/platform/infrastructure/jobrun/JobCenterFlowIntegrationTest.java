package com.info.platform.infrastructure.jobrun;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.application.common.RuntimeConfigEntry;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.application.jobrun.JobCenterFacade;
import com.info.platform.application.jobrun.JobCenterFacade.JobConfigUpdate;
import com.info.platform.application.jobrun.JobCenterFacade.JobView;
import com.info.platform.domain.common.JobExecutionLog;
import com.info.platform.domain.common.JobExecutionLogRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 任务中心端到端集成测试（T37，方案 §6 测试要点「任务中心」）：手动触发主路径（受理 → job_execution_log STARTED→SUCCESS 落痕闭环，PRD 故事 4 场景
 * 2）+ 启停热切换（停用后不再调度、启用即注册，场景 6 / ADR-0017）。
 *
 * <p>不使用 {@code @Transactional}（手动触发的业务执行在虚拟线程异步落痕，测试事务对其它连接不可见）；因此自管清理： 还原 {@code job.PUSH_RETRY}
 * 配置原文（经服务写回，顺带触发调度中心取消注册）并删除本用例落痕行，不污染共享内存库。
 *
 * <p>共享缓存内存库上，异步执行线程与测试线程的跨连接读写可能瞬时碰撞（SQLITE_LOCKED_SHAREDCACHE，busy_timeout 不覆盖共享缓存表锁），统一经 {@link
 * #withLockRetry} 短退避重试（写方毫秒级完成，重试必达）。
 */
@SpringBootTest
@ActiveProfiles("test")
class JobCenterFlowIntegrationTest {

    /** 选取补推任务做触发样本：空表下整轮即时空跑（无外呼、无 LLM），闭环验证留痕链路即可。 */
    private static final String JOB_KEY = "PUSH_RETRY";

    private static final String JOB_NAME = "PushRetryJob";

    private static final Duration DEADLINE = Duration.ofSeconds(10);

    @Autowired private JobCenterFacade facade;
    @Autowired private JobExecutionLogRepository logRepository;
    @Autowired private RuntimeConfigService configService;
    @Autowired private DataSource dataSource;

    private String originalJson;

    @AfterEach
    void restoreState() {
        if (originalJson != null) {
            // 经服务写回原文：恢复 enabled=false 种子语义，并触发调度中心取消注册
            withLockRetry(() -> configService.write("job." + JOB_KEY, originalJson, null));
            originalJson = null;
        }
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
    void triggerFullFlow_persistsStartedThenSuccessInJobExecutionLog() {
        // Arrange：记录当前配置原文（清理还原用）
        originalJson =
                withLockRetry(
                        () ->
                                configService
                                        .read("job." + JOB_KEY)
                                        .map(RuntimeConfigEntry::json)
                                        .orElse(null));

        // Act：手动触发 → 同步受理返回 executionId（job_execution_log.id）
        JobCenterFacade.TriggerResult accepted = withLockRetry(() -> facade.trigger(JOB_KEY));
        assertThat(accepted.status()).isEqualTo("STARTED");
        assertThat(accepted.executionId()).isPositive();

        // Assert：异步执行完成后留痕翻转为 SUCCESS（触发→效果→日志三处闭环，PRD 场景 4.2/4.3）
        JobExecutionLog finalLog = awaitTerminal(accepted.executionId());
        assertThat(finalLog.getJobName()).isEqualTo(JOB_NAME);
        assertThat(finalLog.getStatus().name()).isEqualTo("SUCCESS");
        assertThat(finalLog.getEndTime()).isPresent();
        assertThat(finalLog.getDurationMillis()).isPresent();

        // 总览视图同步可见：lastExecution 为该次执行
        JobView view = viewOf(JOB_KEY);
        assertThat(view.lastExecution()).isNotNull();
        assertThat(view.lastExecution().status()).isEqualTo("SUCCESS");
    }

    @Test
    void toggleHotSwitch_registersAndCancelsScheduling() {
        // Arrange：记录原文；初始（测试种子）为停用 → 无下次执行时间
        originalJson =
                withLockRetry(
                        () ->
                                configService
                                        .read("job." + JOB_KEY)
                                        .map(RuntimeConfigEntry::json)
                                        .orElse(null));
        assertThat(viewOf(JOB_KEY).nextExecutionTime()).as("种子 enabled=false 不注册").isNull();

        // Act 1：页面启用（PATCH 合并写 → 事件热重注册，不重启）
        withLockRetry(
                () -> facade.update(JOB_KEY, new JobConfigUpdate(true, 300000L, null, null, null)));

        // Assert 1：已注册（下次执行时间可推算；间隔型首跑立即，随后 5 分钟一轮）
        assertThat(viewOf(JOB_KEY).nextExecutionTime()).isNotNull();
        assertThat(viewOf(JOB_KEY).enabled()).isTrue();

        // Act 2：页面停用（启停立即生效）
        withLockRetry(
                () -> facade.update(JOB_KEY, new JobConfigUpdate(false, null, null, null, null)));

        // Assert 2：调度已取消（停用后不再调度，ADR-0017「启停立即」）
        assertThat(viewOf(JOB_KEY).nextExecutionTime()).isNull();
        assertThat(viewOf(JOB_KEY).enabled()).isFalse();
    }

    private JobView viewOf(String jobKey) {
        return withLockRetry(
                () ->
                        facade.view().jobs().stream()
                                .filter(j -> j.jobKey().equals(jobKey))
                                .findFirst()
                                .orElseThrow(() -> new IllegalStateException("任务未注册: " + jobKey)));
    }

    /** 轮询留痕至终态（SUCCESS/FAILED）或超时失败。 */
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
            if (latest.isPresent()) {
                String status = latest.orElseThrow().getStatus().name();
                if (!status.equals("STARTED")) {
                    return latest.orElseThrow();
                }
            }
            sleepQuietly(100);
        }
        throw new IllegalStateException(
                "等待任务留痕终态超时 executionId=" + executionId + " lastSeen=" + latest);
    }

    /** 共享缓存表锁（SQLITE_LOCKED*）/数据库忙（SQLITE_BUSY）为瞬时冲突：短退避重试至期限，其余异常直接上抛。 */
    private static <T> T withLockRetry(Supplier<T> action) {
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
