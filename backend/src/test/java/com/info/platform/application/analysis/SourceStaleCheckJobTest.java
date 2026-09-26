package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.application.jobrun.ScheduleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SourceStaleCheckJob 契约单测（T128，方案 §4.7 / ADR-0046 裁决 4）：第 12 键 jobKey/展示名/调度型（CRON）/run 委托
 * checkAll / JobRunStats 轮首重置与 processed=标记+解除计数。AAA 结构。
 */
class SourceStaleCheckJobTest {

    private SourceStaleCheckService staleCheckService;
    private L2TraceRepair l2TraceRepair;
    private SourceStaleCheckJob job;

    @BeforeEach
    void setUp() {
        staleCheckService = mock(SourceStaleCheckService.class);
        l2TraceRepair = mock(L2TraceRepair.class);
        when(l2TraceRepair.repairOrphanExtractedRows()).thenReturn(0);
        job = new SourceStaleCheckJob(staleCheckService, l2TraceRepair);
    }

    @Test
    void contract_twelfthJobKeyDisplayNameAndCronSchedule() {
        assertThat(job.jobKey()).isEqualTo("SOURCE_STALE_CHECK");
        assertThat(job.displayName()).isEqualTo("疑似停更检查");
        assertThat(job.description()).contains("staleSince").contains("不自动停用");
        assertThat(job.scheduleType()).isEqualTo(ScheduleType.CRON);
        assertThat(job.jobName()).isEqualTo("SourceStaleCheckJob");
    }

    @Test
    void run_delegatesToCheckAll_andCarriesStats() {
        when(staleCheckService.checkAll())
                .thenReturn(
                        new SourceStaleCheckService.StaleCheckReport(
                                13, 2, 1, "checked=13; marked=2; cleared=1"));

        job.run();

        verify(staleCheckService).checkAll();
        assertThat(job.lastProcessedCount()).isEqualTo(3); // 标记 + 解除 = 本轮写库动作数
        assertThat(job.lastRunDetail()).contains("marked=2").contains("cleared=1");
    }

    @Test
    void run_statsResetEachRound() {
        when(staleCheckService.checkAll())
                .thenReturn(new SourceStaleCheckService.StaleCheckReport(13, 2, 1, "detail-a"))
                .thenReturn(new SourceStaleCheckService.StaleCheckReport(13, 0, 0, "detail-b"));

        job.run();
        job.run();

        assertThat(job.lastProcessedCount()).isZero();
        assertThat(job.lastRunDetail()).isEqualTo("detail-b");
    }

    // ---- T147（M17 / GAP-02）：L2TraceRepair 触发面顺挂 ----

    @Test
    void run_triggersL2TraceRepairAndCarriesCount() {
        when(staleCheckService.checkAll())
                .thenReturn(
                        new SourceStaleCheckService.StaleCheckReport(
                                13, 1, 0, "checked=13; marked=1; cleared=0"));
        when(l2TraceRepair.repairOrphanExtractedRows()).thenReturn(1);

        job.run();

        verify(l2TraceRepair).repairOrphanExtractedRows();
        assertThat(job.lastProcessedCount()).isEqualTo(2); // 停更标记 1 + 归位 1
        assertThat(job.lastRunDetail()).contains("l2TraceRepair=1");
    }

    @Test
    void run_l2TraceRepairFailure_doesNotBreakStaleCheck() {
        when(staleCheckService.checkAll())
                .thenReturn(
                        new SourceStaleCheckService.StaleCheckReport(
                                13, 1, 0, "checked=13; marked=1"));
        when(l2TraceRepair.repairOrphanExtractedRows())
                .thenThrow(new RuntimeException("repair boom"));

        job.run();

        assertThat(job.lastProcessedCount()).isEqualTo(1); // 归位段失败不影响停更检查计数
        assertThat(job.lastRunDetail()).contains("marked=1");
    }
}
