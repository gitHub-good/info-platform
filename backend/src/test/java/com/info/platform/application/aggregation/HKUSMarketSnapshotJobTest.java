package com.info.platform.application.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.info.platform.application.aggregation.HKUSMarketSnapshotService.SnapshotReport;
import org.junit.jupiter.api.Test;

/**
 * HKUSMarketSnapshotJob 单测（M29 T252，ADR-0064 裁决 4）：Job 键/调度类型/展示契约（第 20 键收编面）与 tick → service.run
 * 委托 + JobRunStats 上报（二十 Job 惯例，NewsPulseJob 同款）。快照逻辑由 HKUSMarketSnapshotServiceTest 覆盖（双源 Mock
 * 零外呼）。
 */
class HKUSMarketSnapshotJobTest {

    @Test
    void jobContract_twentiethKeyFixedDelay() {
        HKUSMarketSnapshotService service = mock(HKUSMarketSnapshotService.class);
        HKUSMarketSnapshotJob job = new HKUSMarketSnapshotJob(service);

        assertThat(job.jobKey()).isEqualTo("HKUS_MARKET_SNAPSHOT");
        assertThat(job.jobName()).isEqualTo("HKUSMarketSnapshotJob");
        assertThat(job.scheduleType().name()).isEqualTo("FIXED_DELAY");
        assertThat(job.displayName()).isEqualTo("港美股行情快照");
        assertThat(job.description()).contains("一职三责").contains("市值收敛");
    }

    @Test
    void run_delegatesAndReportsStats() {
        HKUSMarketSnapshotService service = mock(HKUSMarketSnapshotService.class);
        when(service.run())
                .thenReturn(
                        new SnapshotReport(
                                11736,
                                71,
                                210,
                                2759,
                                "hk[rows=5768;src=tencent] us[rows=5968;promoted=210;demoted=2759]"));
        HKUSMarketSnapshotJob job = new HKUSMarketSnapshotJob(service);

        job.run();

        assertThat(job.lastProcessedCount()).isEqualTo(11736);
        assertThat(job.lastRunDetail()).contains("src=tencent").contains("demoted=2759");
    }
}
