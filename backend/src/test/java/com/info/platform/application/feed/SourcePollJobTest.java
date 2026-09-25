package com.info.platform.application.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.info.platform.application.jobrun.ManagedJob;
import com.info.platform.application.jobrun.ScheduleType;
import org.junit.jupiter.api.Test;

/**
 * SourcePollJob 单测（T103，ADR-0040 第 8 个 ManagedJob）：元数据（jobKey/FIXED_DELAY/displayName）+ run 委托 tick
 * + JobRunStats 明细（dispatched/ok/fail 段式）+ 失败轮统计清零。
 */
class SourcePollJobTest {

    private final SourceSchedulingService schedulingService = mock(SourceSchedulingService.class);

    private SourcePollJob job() {
        return new SourcePollJob(schedulingService);
    }

    @Test
    void metadata_eighthManagedJob_fixedDelay() {
        ManagedJob job = job();

        assertThat(job.jobKey()).isEqualTo("SOURCE_POLL");
        // jobName 默认派生类简单名——Job 日志页筛选联动
        assertThat(job.jobName()).isEqualTo("SourcePollJob");
        assertThat(job.displayName()).isEqualTo("资讯源轮询");
        assertThat(job.description()).contains("资讯源");
        assertThat(job.scheduleType()).isEqualTo(ScheduleType.FIXED_DELAY);
    }

    @Test
    void run_delegatesToTick_reportsDispatchedOkFailDetail() {
        when(schedulingService.tick()).thenReturn(new SourceSchedulingService.TickReport(3, 2, 1));
        SourcePollJob job = job();

        job.run();

        assertThat(job.lastProcessedCount()).isEqualTo(3);
        assertThat(job.lastRunDetail()).isEqualTo("dispatched=3; ok=2; fail=1");
    }

    @Test
    void run_zeroDueTick_reportsZeroes() {
        when(schedulingService.tick()).thenReturn(new SourceSchedulingService.TickReport(0, 0, 0));
        SourcePollJob job = job();

        job.run();

        assertThat(job.lastProcessedCount()).isZero();
        assertThat(job.lastRunDetail()).isEqualTo("dispatched=0; ok=0; fail=0");
    }

    @Test
    void run_failureRound_statsResetToZeroAndExceptionPropagates() {
        when(schedulingService.tick())
                .thenReturn(new SourceSchedulingService.TickReport(1, 1, 0))
                .thenThrow(new IllegalStateException("db down"));
        SourcePollJob job = job();
        job.run();
        assertThat(job.lastProcessedCount()).isEqualTo(1);

        // 失败轮：异常上抛（JobExecutor 记 FAILED），统计不误报上一轮
        assertThatThrownBy(job::run).isInstanceOf(IllegalStateException.class);
        assertThat(job.lastProcessedCount()).isZero();
        assertThat(job.lastRunDetail()).isNull();
    }
}
