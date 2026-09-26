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
 * IndustryWeeklyReportJob 契约单测（M17 T145，REQ 拍板四-2）：第 15 键 jobKey/展示名/CRON 调度型（周日晚 20:00 由种子
 * job.INDUSTRY_WEEKLY_REPORT 携带）/定时生成当周/armed 手动重试优先消费/JobRunStats 轮首重置。
 */
class IndustryWeeklyReportJobTest {

    private WeeklyReportService reportService;

    private IndustryWeeklyReportJob job;

    @BeforeEach
    void setUp() {
        reportService = mock(WeeklyReportService.class);
        job = new IndustryWeeklyReportJob(reportService);
    }

    @Test
    void contract_fifteenthJobKeyDisplayNameAndCronSchedule() {
        assertThat(job.jobKey()).isEqualTo("INDUSTRY_WEEKLY_REPORT");
        assertThat(job.displayName()).isEqualTo("行业周报");
        assertThat(job.description()).contains("周报").contains("FUSED");
        assertThat(job.scheduleType()).isEqualTo(ScheduleType.CRON);
        assertThat(job.jobName()).isEqualTo("IndustryWeeklyReportJob");
    }

    @Test
    void run_scheduled_delegatesToWindowAndCarriesStats() {
        when(reportService.runScheduledWindow())
                .thenReturn(
                        new WeeklyReportService.WindowReport(
                                1, 0, "weekStart=2026-09-21;status=SUCCESS"));

        job.run();

        assertThat(job.lastProcessedCount()).isEqualTo(1);
        assertThat(job.lastRunDetail()).contains("2026-09-21");
    }

    @Test
    void run_armedRetryWeek_consumesItInsteadOfWindow() {
        when(reportService.generateFor("2026-09-21"))
                .thenReturn(
                        new WeeklyReportService.GenerationOutcome(
                                "2026-09-21", "SUCCESS", false, null, 120, 30, false));

        job.armRetry(LocalDate.parse("2026-09-21"));
        job.run();

        verify(reportService).generateFor("2026-09-21");
        assertThat(job.lastProcessedCount()).isEqualTo(1);
        assertThat(job.lastRunDetail()).contains("retry").contains("2026-09-21");

        // armed 消费即清：下一轮恢复定时语义
        when(reportService.runScheduledWindow())
                .thenReturn(new WeeklyReportService.WindowReport(1, 0, "detail"));
        job.run();
        verify(reportService).runScheduledWindow();
    }
}
