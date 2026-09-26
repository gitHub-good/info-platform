package com.info.platform.application.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.application.jobrun.ScheduleType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * FactorSnapshotJob 契约单测（T170，方案 §4.6 第 16 键）：jobKey/scheduleType CRON/展示名、tick 委托 snapshotAll
 * （快照口径日 = Asia/Shanghai 当日）、JobRunStats 上报（processedCount + 覆盖率明细）。服务 mock 零数据库。
 */
class FactorSnapshotJobTest {

    private FactorSnapshotService service;
    private FactorSnapshotJob job;

    @BeforeEach
    void setUp() {
        service = mock(FactorSnapshotService.class);
        job =
                new FactorSnapshotJob(
                        service,
                        Clock.fixed(Instant.parse("2026-09-22T09:30:00Z"), ZoneOffset.UTC));
    }

    @Test
    void contract_sixteenthManagedJob_cronType() {
        assertThat(job.jobKey()).isEqualTo("FACTOR_SNAPSHOT");
        assertThat(job.scheduleType()).isEqualTo(ScheduleType.CRON);
        assertThat(job.displayName()).isNotBlank();
        assertThat(job.description()).isNotBlank();
        assertThat(job.jobName()).isEqualTo("FactorSnapshotJob"); // 留痕名对齐执行日志
    }

    @Test
    void run_delegatesWithShanghaiSnapshotDate_andReportsStats() {
        when(service.snapshotAll(any(LocalDate.class)))
                .thenReturn(
                        new FactorSnapshotService.SnapshotReport(
                                5221, 5221, 5189, 100.0, Map.of("NO_ASSOC_INDUSTRY", 2901L)));

        job.run();

        // 快照口径日锚定 Asia/Shanghai（UTC 09:30 = 上海 17:30 同日）
        verify(service).snapshotAll(LocalDate.of(2026, 9, 22));
        assertThat(job.lastProcessedCount()).isEqualTo(5221);
        assertThat(job.lastRunDetail())
                .contains("subjects=5221")
                .contains("rows=5221")
                .contains("coverage=100.0")
                .contains("market=5189")
                .contains("NO_ASSOC_INDUSTRY=2901");
    }

    @Test
    void run_resetsStatsEachRound() {
        when(service.snapshotAll(any(LocalDate.class)))
                .thenReturn(new FactorSnapshotService.SnapshotReport(10, 8, 0, 80.0, Map.of()))
                .thenReturn(new FactorSnapshotService.SnapshotReport(20, 20, 20, 100.0, Map.of()));

        job.run();
        assertThat(job.lastProcessedCount()).isEqualTo(8);
        job.run();
        assertThat(job.lastProcessedCount()).isEqualTo(20); // 轮首重置不残留
    }

    @Test
    void run_shanghaiDateCrossUtcMidnight() {
        // UTC 16:30（上海 00:30 次日）→ 口径日取上海日期
        FactorSnapshotJob nightJob =
                new FactorSnapshotJob(
                        service,
                        Clock.fixed(Instant.parse("2026-09-22T16:30:00Z"), ZoneOffset.UTC));
        when(service.snapshotAll(any(LocalDate.class)))
                .thenReturn(new FactorSnapshotService.SnapshotReport(1, 1, 0, 100.0, Map.of()));

        nightJob.run();

        verify(service).snapshotAll(LocalDate.of(2026, 9, 23));
    }
}
