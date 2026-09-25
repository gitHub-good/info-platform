package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.info.platform.application.jobrun.ScheduleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * NewsPipelineJob 契约单测（T121，方案 §4.7 / ADR-0046 裁决 4）：jobKey/展示名/调度型（FIXED_DELAY）/run 委托 tick /
 * JobRunStats 轮首重置。AAA 结构。
 */
class NewsPipelineJobTest {

    private NewsPipelineService pipelineService;
    private NewsPipelineJob job;

    @BeforeEach
    void setUp() {
        pipelineService = mock(NewsPipelineService.class);
        job = new NewsPipelineJob(pipelineService);
    }

    @Test
    void contract_jobKeyDisplayNameAndScheduleType() {
        assertThat(job.jobKey()).isEqualTo("NEWS_PIPELINE");
        assertThat(job.displayName()).isEqualTo("AI 归类管道");
        assertThat(job.description()).contains("L0").contains("L1");
        assertThat(job.scheduleType()).isEqualTo(ScheduleType.FIXED_DELAY);
        assertThat(job.jobName()).isEqualTo("NewsPipelineJob"); // job_execution_log.jobName 口径
    }

    @Test
    void run_delegatesToTick_andCarriesStats() {
        when(pipelineService.tick())
                .thenReturn(
                        new NewsPipelineService.TickReport(
                                "l0=pass:1; noise:0; near_dup:0; l1=done:1; fail:0; pending=1", 2));

        job.run();

        assertThat(job.lastProcessedCount()).isEqualTo(2);
        assertThat(job.lastRunDetail())
                .isEqualTo("l0=pass:1; noise:0; near_dup:0; l1=done:1; fail:0; pending=1");
    }

    @Test
    void run_statsResetEachRound() {
        when(pipelineService.tick())
                .thenReturn(new NewsPipelineService.TickReport("detail-a", 5))
                .thenReturn(new NewsPipelineService.TickReport("detail-b", 0));

        job.run();
        job.run();

        // 失败/空轮不误报上一轮（轮首重置）
        assertThat(job.lastProcessedCount()).isZero();
        assertThat(job.lastRunDetail()).isEqualTo("detail-b");
    }
}
