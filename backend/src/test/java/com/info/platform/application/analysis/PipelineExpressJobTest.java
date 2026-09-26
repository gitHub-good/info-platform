package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.info.platform.application.jobrun.ScheduleType;
import org.junit.jupiter.api.Test;

/**
 * PipelineExpressJob 单测（T131，M16 ADR-0051 裁决 1）：jobKey/displayName/scheduleType、tick 委托与
 * JobRunStats 轮次统计。
 */
class PipelineExpressJobTest {

    @Test
    void job_identity_13thManagedJobKey() {
        PipelineExpressService service = mock(PipelineExpressService.class);
        PipelineExpressJob job = new PipelineExpressJob(service);

        assertThat(job.jobKey()).isEqualTo("PIPELINE_EXPRESS");
        assertThat(job.displayName()).isEqualTo("管道快速通道");
        assertThat(job.description()).contains("ADR-0051");
        assertThat(job.scheduleType()).isEqualTo(ScheduleType.FIXED_DELAY);
    }

    @Test
    void run_delegatesTick_andCarriesRunStats() {
        PipelineExpressService service = mock(PipelineExpressService.class);
        org.mockito.Mockito.when(service.tick())
                .thenReturn(
                        new PipelineExpressService.ExpressReport(
                                "express=scan:3; hit:2; l0=2; l1=2; fail:0; l2=1", 5));
        PipelineExpressJob job = new PipelineExpressJob(service);

        job.run();

        verify(service).tick();
        assertThat(job.lastProcessedCount()).isEqualTo(5);
        assertThat(job.lastRunDetail())
                .isEqualTo("express=scan:3; hit:2; l0=2; l1=2; fail:0; l2=1");
    }
}
