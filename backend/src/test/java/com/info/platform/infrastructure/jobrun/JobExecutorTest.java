package com.info.platform.infrastructure.jobrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.application.jobrun.ManagedJob;
import com.info.platform.application.jobrun.ScheduleType;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.JobExecutionLog;
import com.info.platform.domain.common.JobExecutionStatus;
import com.info.platform.infrastructure.common.JobExecutionRecorder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * JobExecutor 单测（T37，方案 §4.5 防重入 Must 红线 + 留痕语义平移）：手动触发受理 / 同任务运行中再次触发 409/30063 / 定时触发跳过 /
 * STARTED→SUCCESS·FAILED 留痕顺序 / 守卫释放。AAA 结构。
 *
 * <p>受控执行器（捕获 Runnable 不立即执行）模拟长任务持有守卫，确定性验证防重入；同步执行器（提交即跑）验证主路径与留痕。
 */
class JobExecutorTest {

    private static final String JOB_NAME = "TestJob";

    private JobExecutionRecorder recorder;
    private List<Runnable> submitted;
    private JobExecutor executor;

    @BeforeEach
    void setUp() {
        recorder = mock(JobExecutionRecorder.class);
        submitted = new ArrayList<>();
    }

    /** 手动触发提交进受控队列（不立即执行），守卫保持持有——防重入测试的确定性时序。 */
    private JobExecutor deferredExecutor() {
        return new JobExecutor(recorder, submitted::add);
    }

    /** 手动触发同步执行（提交即跑），验证受理主路径与留痕。 */
    private JobExecutor directExecutor() {
        return new JobExecutor(recorder, Runnable::run);
    }

    /** 测试任务（jobKey=PUSH_RETRY / jobName=TestJob，run 行为可注入）。 */
    private static ManagedJob job(Runnable action) {
        return new ManagedJob() {
            @Override
            public String jobKey() {
                return "PUSH_RETRY";
            }

            @Override
            public String jobName() {
                return JOB_NAME;
            }

            @Override
            public String displayName() {
                return "推送补推";
            }

            @Override
            public String description() {
                return "测试任务";
            }

            @Override
            public ScheduleType scheduleType() {
                return ScheduleType.FIXED_DELAY;
            }

            @Override
            public void run() {
                action.run();
            }
        };
    }

    /** 构造落库回填后的 STARTED 记录（含 id，模拟 recorder.start 成功返回）。 */
    private static JobExecutionLog startedLog(long id) {
        return JobExecutionLog.reconstruct(
                id,
                JOB_NAME,
                Instant.parse("2026-09-22T01:00:00Z"),
                null,
                JobExecutionStatus.STARTED,
                null,
                0,
                0,
                null,
                null,
                null);
    }

    @Test
    void triggerNow_acceptsAndReturnsExecutionId() {
        // Arrange：start 落痕成功（回填 id=123）
        JobExecutionLog started = startedLog(123L);
        when(recorder.start(JOB_NAME)).thenReturn(started);
        executor = directExecutor();

        // Act
        JobExecutor.TriggerOutcome outcome = executor.triggerNow(job(() -> {}));

        // Assert：同步返回受理（executionId=留痕 id），执行完成记 SUCCESS(0,0)
        assertThat(outcome.executionId()).isEqualTo(123L);
        verify(recorder).start(JOB_NAME);
        verify(recorder).success(eq(started), eq(0), eq(0));
        verify(recorder, never()).failed(any(), any(), anyInt(), anyInt());
    }

    @Test
    void triggerNow_running_rejectsWith30063() {
        // Arrange：受控执行器——触发后任务未执行，守卫保持持有
        when(recorder.start(JOB_NAME)).thenReturn(startedLog(1L));
        executor = deferredExecutor();
        executor.triggerNow(job(() -> {}));

        // Act + Assert：运行中再次触发 → 409/30063（防重入红线），只受理一次
        assertThatThrownBy(() -> executor.triggerNow(job(() -> {})))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.JOB_ALREADY_RUNNING));
        verify(recorder, times(1)).start(JOB_NAME);
    }

    @Test
    void triggerNow_afterCompletion_acceptsAgain() {
        // Arrange：受控执行器，手动放行提交的任务 → 守卫释放 → 可再次受理
        when(recorder.start(JOB_NAME)).thenReturn(startedLog(1L));
        executor = deferredExecutor();
        executor.triggerNow(job(() -> {}));
        submitted.forEach(Runnable::run);

        // Act：再次触发不抛
        executor.triggerNow(job(() -> {}));
        submitted.forEach(Runnable::run);

        // Assert：两次均受理留痕
        verify(recorder, times(2)).start(JOB_NAME);
    }

    @Test
    void triggerNow_startPersistFails_rejectsWithServerErrorAndReleasesGuard() {
        // Arrange：留痕写入失败（recorder 返回 null）
        when(recorder.start(JOB_NAME)).thenReturn(null);
        executor = directExecutor();

        // Act + Assert：不受理（50000），且守卫已释放（再次触发同样因留痕失败被拒，而非 30063）
        assertThatThrownBy(() -> executor.triggerNow(job(() -> {})))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.SERVER_ERROR));
        assertThatThrownBy(() -> executor.triggerNow(job(() -> {})))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.SERVER_ERROR));
    }

    @Test
    void runScheduled_recordsStartedThenSuccess() {
        // Arrange
        JobExecutionLog started = startedLog(9L);
        when(recorder.start(JOB_NAME)).thenReturn(started);
        List<String> trace = new ArrayList<>();
        executor = directExecutor();

        // Act
        executor.runScheduled(job(() -> trace.add("run")));

        // Assert：start → run → success 顺序（留痕语义与退役 AOP 一致）
        InOrder inOrder = inOrder(recorder);
        inOrder.verify(recorder).start(JOB_NAME);
        inOrder.verify(recorder).success(eq(started), eq(0), eq(0));
        assertThat(trace).containsExactly("run");
    }

    @Test
    void runScheduled_throwingJob_recordsFailedAndSwallows() {
        // Arrange：任务抛异常
        when(recorder.start(JOB_NAME)).thenReturn(startedLog(9L));
        executor = directExecutor();

        // Act：定时链路无调用方，异常不上抛（记 ERROR + FAILED 留痕）
        executor.runScheduled(
                job(
                        () -> {
                            throw new IllegalStateException("sample-failure");
                        }));

        // Assert：FAILED 带异常摘要（errorMessage 语义与 AOP 时代一致），守卫已释放
        verify(recorder).failed(any(), contains("sample-failure"), eq(0), eq(0));
        verify(recorder, never()).success(any(), anyInt(), anyInt());
        assertThat(executor.isRunning("PUSH_RETRY")).isFalse();
    }

    @Test
    void runScheduled_whileManualRunRunning_skipsRoundWithoutRecording() throws Exception {
        // Arrange：手动触发占住守卫（后台线程挂起直至放行）
        when(recorder.start(JOB_NAME)).thenReturn(startedLog(1L));
        CountDownLatch release = new CountDownLatch(1);
        executor =
                new JobExecutor(
                        recorder,
                        task -> {
                            Thread worker =
                                    new Thread(
                                            () -> {
                                                try {
                                                    release.await();
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                }
                                                task.run();
                                            });
                            worker.start();
                        });
        executor.triggerNow(job(() -> {}));

        // Act：定时触发遇运行守卫 → 跳过本轮（不新增留痕）
        executor.runScheduled(job(() -> {}));

        // Assert：仍只有手动触发那一条 start
        verify(recorder, times(1)).start(JOB_NAME);
        release.countDown();
    }

    @Test
    void isRunning_reflectsGuardState() {
        when(recorder.start(JOB_NAME)).thenReturn(startedLog(1L));
        executor = deferredExecutor();
        assertThat(executor.isRunning("PUSH_RETRY")).isFalse();

        executor.triggerNow(job(() -> {}));
        assertThat(executor.isRunning("PUSH_RETRY")).isTrue();

        submitted.forEach(Runnable::run);
        assertThat(executor.isRunning("PUSH_RETRY")).isFalse();
    }
}
