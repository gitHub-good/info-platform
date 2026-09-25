package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.application.jobrun.ScheduleType;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * IndustryDailyReportJob 契约单测（T124，方案 §4.7 / ADR-0046 裁决 4）：第 11 键 jobKey/展示名/调度型（CRON）/定时委托
 * 补跑窗口/armed 手动重试优先消费/JobRunStats 轮首重置。AAA 结构。
 */
class IndustryDailyReportJobTest {

    private DailyReportService reportService;
    private IndustryDailyReportJob job;

    @BeforeEach
    void setUp() {
        reportService = mock(DailyReportService.class);
        job = new IndustryDailyReportJob(reportService);
    }

    @Test
    void contract_eleventhJobKeyDisplayNameAndCronSchedule() {
        assertThat(job.jobKey()).isEqualTo("INDUSTRY_DAILY_REPORT");
        assertThat(job.displayName()).isEqualTo("行业日报");
        assertThat(job.description()).contains("统计").contains("FUSED");
        assertThat(job.scheduleType()).isEqualTo(ScheduleType.CRON);
        assertThat(job.jobName()).isEqualTo("IndustryDailyReportJob");
    }

    @Test
    void run_scheduled_delegatesToWindowAndCarriesStats() {
        when(reportService.runScheduledWindow())
                .thenReturn(
                        new DailyReportService.WindowReport(
                                2, 0, "date=2026-09-22;status=SUCCESS"));

        job.run();

        assertThat(job.lastProcessedCount()).isEqualTo(2);
        assertThat(job.lastRunDetail()).contains("2026-09-22");
    }

    @Test
    void run_armedRetryDate_consumesItInsteadOfWindow() {
        // 手动 retry：armed 日优先，消费即清（下一轮恢复定时语义）
        when(reportService.generateFor("2026-09-20"))
                .thenReturn(
                        new DailyReportService.GenerationOutcome(
                                "2026-09-20", "SUCCESS", false, null, 30, 4, false));

        job.armRetry(LocalDate.parse("2026-09-20"));
        job.run();

        verify(reportService).generateFor("2026-09-20");
        org.mockito.Mockito.verify(reportService, org.mockito.Mockito.never()).runScheduledWindow();

        // armed 已消费：再次 run 走定时窗口
        when(reportService.runScheduledWindow())
                .thenReturn(new DailyReportService.WindowReport(1, 1, "detail"));
        job.run();
        verify(reportService).runScheduledWindow();
    }

    @Test
    void run_statsResetEachRound() {
        when(reportService.runScheduledWindow())
                .thenReturn(new DailyReportService.WindowReport(1, 0, "detail-a"))
                .thenReturn(new DailyReportService.WindowReport(2, 0, "detail-b"));

        job.run();
        job.run();

        assertThat(job.lastProcessedCount()).isEqualTo(2);
        assertThat(job.lastRunDetail()).isEqualTo("detail-b");
    }
}
