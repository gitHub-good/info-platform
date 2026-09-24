package com.info.platform.application.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.info.platform.application.jobrun.ScheduleType;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SubjectSyncJob 单测（T53，技术方案增补 §4.6）：ManagedJob 契约（jobKey/展示名/CRON）+ 委托语义——成功轮 run() 正常返回（INFO
 * 留痕由日志承载）、 部分失败轮 {@link SubjectSyncException} <b>直抛不吞</b>（JobExecutor 落 errorMessage，§3.3 方案 A
 * 双通道）。
 */
class SubjectSyncJobTest {

    @Test
    void managedJobContract_subjectSyncKeyCronAndDisplay() {
        SubjectSyncJob job = new SubjectSyncJob(mock(SubjectSyncService.class));

        assertThat(job.jobKey()).isEqualTo("SUBJECT_SYNC");
        assertThat(job.displayName()).isEqualTo("标的池同步");
        assertThat(job.description()).contains("永不删除");
        assertThat(job.scheduleType()).isEqualTo(ScheduleType.CRON);
        // jobName 默认实现 = 类简单名（job_execution_log.job_name 口径）
        assertThat(job.jobName()).isEqualTo("SubjectSyncJob");
    }

    @Test
    void run_delegatesSyncAll_successReturnsQuietly() {
        SubjectSyncService service = mock(SubjectSyncService.class);
        when(service.syncAll())
                .thenReturn(
                        List.of(
                                new MarketSyncResult(
                                        MarketSyncSpec.A_SHARE_STOCK,
                                        5561,
                                        2,
                                        5000,
                                        0,
                                        1,
                                        5563,
                                        95000),
                                new MarketSyncResult(
                                        MarketSyncSpec.HK_STOCK,
                                        2927,
                                        0,
                                        2900,
                                        0,
                                        0,
                                        2927,
                                        30000)));
        SubjectSyncJob job = new SubjectSyncJob(service);

        // 成功轮：正常返回（计数走 INFO 日志，不落 errorMessage）
        job.run();
    }

    @Test
    void run_syncThrows_propagatesForErrorMessageTrace() {
        SubjectSyncService service = mock(SubjectSyncService.class);
        when(service.syncAll())
                .thenThrow(
                        new SubjectSyncException(
                                List.of(
                                        new MarketSyncResult(
                                                MarketSyncSpec.A_SHARE_STOCK,
                                                5561,
                                                0,
                                                5561,
                                                0,
                                                0,
                                                5561,
                                                95000)),
                                List.of(
                                        "HK_STOCK FAILED (clist 第 12 页拉取失败（重试耗尽）bucket=HK_STOCK)")));
        SubjectSyncJob job = new SubjectSyncJob(service);

        // 部分失败轮：异常携带成功计数与失败摘要上抛（errorMessage 一条文本承载，§4.5 样例格式）
        assertThatThrownBy(job::run)
                .isInstanceOf(SubjectSyncException.class)
                .hasMessageContaining("标的池同步部分失败")
                .hasMessageContaining("A_SHARE_STOCK SUCCESS (inserted=5561")
                .hasMessageContaining("HK_STOCK FAILED")
                .hasMessageContaining("第 12 页");
    }
}
