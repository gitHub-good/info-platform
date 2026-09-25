package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.info.platform.application.jobrun.ScheduleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * IndustryHeatSnapshotJob 契约单测（T123，方案 §4.7 / ADR-0046 裁决 4）：第 10 键 jobKey/展示名/调度型（FIXED_DELAY）/run
 * 委托 snapshotAll / JobRunStats 轮首重置。AAA 结构。
 */
class IndustryHeatSnapshotJobTest {

    private HeatSnapshotService snapshotService;
    private IndustryHeatSnapshotJob job;

    @BeforeEach
    void setUp() {
        snapshotService = mock(HeatSnapshotService.class);
        job = new IndustryHeatSnapshotJob(snapshotService);
    }

    @Test
    void contract_tenthJobKeyDisplayNameAndScheduleType() {
        assertThat(job.jobKey()).isEqualTo("INDUSTRY_HEAT_SNAPSHOT");
        assertThat(job.displayName()).isEqualTo("行业热度快照");
        assertThat(job.description()).contains("31").contains("K1=10");
        assertThat(job.scheduleType()).isEqualTo(ScheduleType.FIXED_DELAY);
        assertThat(job.jobName()).isEqualTo("IndustryHeatSnapshotJob");
    }

    @Test
    void run_delegatesToSnapshotAll_andCarriesStats() {
        when(snapshotService.snapshotAll())
                .thenReturn(new HeatSnapshotService.SnapshotReport(2, 62, "heat=h24:31; d7:31"));

        job.run();

        assertThat(job.lastProcessedCount()).isEqualTo(62);
        assertThat(job.lastRunDetail()).isEqualTo("heat=h24:31; d7:31");
    }

    @Test
    void run_statsResetEachRound() {
        when(snapshotService.snapshotAll())
                .thenReturn(new HeatSnapshotService.SnapshotReport(2, 62, "detail-a"))
                .thenReturn(new HeatSnapshotService.SnapshotReport(2, 62, "detail-b"));

        job.run();
        job.run();

        assertThat(job.lastRunDetail()).isEqualTo("detail-b");
    }
}
