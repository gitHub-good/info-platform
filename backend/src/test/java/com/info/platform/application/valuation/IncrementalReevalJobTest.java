package com.info.platform.application.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.application.jobrun.ScheduleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * IncrementalReevalJob 单测（M22 T190，第 18 个 ManagedJob）：jobKey/调度类型/显示面契约 + tick 委托与 JobRunStats
 * 上报（M16 RecommendationFeedJob 同款惯例——本类只做入口与留痕，链路逻辑归 Service 单测）。
 */
class IncrementalReevalJobTest {

    private IncrementalReevalService service;

    private IncrementalReevalJob job;

    @BeforeEach
    void setUp() {
        service = mock(IncrementalReevalService.class);
        job = new IncrementalReevalJob(service);
    }

    @Test
    void jobContract_eighteenthManagedJobFixedDelay() {
        assertThat(job.jobKey()).isEqualTo("INCREMENTAL_REEVAL");
        assertThat(job.scheduleType()).isEqualTo(ScheduleType.FIXED_DELAY);
        assertThat(job.displayName()).contains("增量");
        assertThat(job.description()).isNotBlank();
    }

    @Test
    void run_delegatesTickAndReportsStats() {
        when(service.tick())
                .thenReturn(
                        new IncrementalReevalService.ReevalReport(
                                "incr=scan:1;recompute=rows:2;judge=pass", 3));

        job.run();

        verify(service).tick();
        assertThat(job.lastProcessedCount()).isEqualTo(3);
        assertThat(job.lastRunDetail()).contains("scan:1");
    }
}
