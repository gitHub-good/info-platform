package com.info.platform.application.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.application.jobrun.ManagedJob;
import com.info.platform.application.jobrun.ScheduleType;
import org.junit.jupiter.api.Test;

/**
 * RetentionCleanupJob 单测（T71，方案 §4.5）：第 7 个收编任务的元数据（jobKey/jobName 派生/displayName/CRON）+ run 委托
 * service + JobRunStats 轮次统计上报（轮首重置，失败轮清零）。AAA 结构。
 */
class RetentionCleanupJobTest {

    private final RetentionCleanupService service = mock(RetentionCleanupService.class);

    private RetentionCleanupJob job() {
        return new RetentionCleanupJob(service);
    }

    @Test
    void metadata_seventhManagedJob_cronType() {
        ManagedJob job = job();

        assertThat(job.jobKey()).isEqualTo("RETENTION_CLEANUP");
        // jobName 走默认派生（类简单名）——Job 日志页筛选与任务中心「最近执行」自动联动
        assertThat(job.jobName()).isEqualTo("RetentionCleanupJob");
        assertThat(job.displayName()).isEqualTo("留痕数据清理");
        assertThat(job.description()).contains("留痕表");
        assertThat(job.scheduleType()).isEqualTo(ScheduleType.CRON);
    }

    @Test
    void run_delegatesToService_reportsCountsAndDetail() {
        // Arrange
        when(service.runOnce())
                .thenReturn(
                        new RetentionCleanupService.CleanupResult(
                                6L,
                                "job_execution_log=2; data_source_event=0; llm_call_log=1;"
                                        + " reading_event=3"));
        RetentionCleanupJob job = job();

        // Act
        job.run();

        // Assert：JobRunStats 上报（JobExecutor 成功路径读取后写入 SUCCESS 留痕）
        verify(service).runOnce();
        assertThat(job.lastProcessedCount()).isEqualTo(6);
        assertThat(job.lastRunDetail())
                .isEqualTo(
                        "job_execution_log=2; data_source_event=0; llm_call_log=1; reading_event=3");
    }

    @Test
    void run_resetsStatsAtRoundStart_failedRoundReportsZero() {
        // Arrange：上一轮成功（stats 有值），本轮 service 抛出（→ 通道记 FAILED）
        when(service.runOnce())
                .thenReturn(new RetentionCleanupService.CleanupResult(5L, "job_execution_log=5"))
                .thenThrow(new RetentionCleanupException("llm_call_log: boom"));
        RetentionCleanupJob job = job();
        job.run();
        assertThat(job.lastProcessedCount()).isEqualTo(5);

        // Act + Assert：轮首重置——失败轮统计为 0/null 且异常上抛（不误报上一轮计数）
        assertThatThrownBy(job::run).isInstanceOf(RetentionCleanupException.class);
        assertThat(job.lastProcessedCount()).isZero();
        assertThat(job.lastRunDetail()).isNull();
    }
}
