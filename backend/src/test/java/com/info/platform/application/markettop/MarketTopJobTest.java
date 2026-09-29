package com.info.platform.application.markettop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.info.platform.application.markettop.MarketTopService.GenerationReport;
import com.info.platform.domain.aggregation.Market;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * MarketTopJob 单测（M21 T183，方案 §4.6）：第 17 ManagedJob 元数据 + tick 入口把 GenerationReport 映射为 JobRunStats
 * （processedCount=topSize、detail 段式留痕）；榜单日取 Asia/Shanghai 当日（守卫对账键）。
 */
@ExtendWith(MockitoExtension.class)
class MarketTopJobTest {

    @Mock private MarketTopService marketTopService;

    @Test
    void jobMetadata_seventeenthManagedJob() {
        MarketTopJob job = new MarketTopJob(marketTopService, Clock.systemUTC());

        assertThat(job.jobKey()).isEqualTo("MARKET_TOP_JOB");
        assertThat(job.jobName()).isEqualTo("MarketTopJob");
        assertThat(job.displayName()).isEqualTo("全市场榜单");
        assertThat(job.description()).contains("18:00");
        assertThat(job.scheduleType().name()).isEqualTo("CRON");
    }

    @Test
    void run_reportsStatsFromGenerationReport() {
        // 固定时钟：UTC 10:00 = 上海 18:00 → 榜单日 2026-09-22
        Clock clock = Clock.fixed(Instant.parse("2026-09-22T10:00:00Z"), ZoneOffset.UTC);
        MarketTopJob job = new MarketTopJob(marketTopService, clock);
        GenerationReport report =
                new GenerationReport(
                        Market.A_SHARE,
                        GenerationReport.STATUS_SUCCESS,
                        "2026-09-22",
                        1,
                        5221,
                        1820,
                        300,
                        40,
                        38,
                        2,
                        0,
                        10,
                        false,
                        null,
                        1,
                        "members=4900/5221",
                        null);
        java.time.LocalDate rankDate = java.time.LocalDate.of(2026, 9, 22);
        when(marketTopService.generate(rankDate, Market.A_SHARE)).thenReturn(report);
        when(marketTopService.generate(rankDate, Market.HK))
                .thenReturn(
                        GenerationReport.skipped(Market.HK, "2026-09-22", "no-pool（HK 活跃标的池为空）"));
        when(marketTopService.generate(rankDate, Market.US))
                .thenReturn(
                        GenerationReport.skipped(
                                Market.US, "2026-09-22", "no-signal（US 无可入池信号标的）"));

        job.run();

        // 三市场顺序执行：A 股成功计入 + 港美跳过留痕不阻断（detail 按 A_SHARE→HK→US 拼装）
        assertThat(job.lastProcessedCount()).isEqualTo(10);
        assertThat(job.lastRunDetail())
                .contains("market=A_SHARE")
                .contains("funnel=5221>300>40>10")
                .contains("dive=ok:38|tpl:2|skip:0")
                .contains("citationDrops=1")
                .contains("backfill[members=4900/5221]")
                .contains("market=HK")
                .contains("reason=no-pool")
                .contains("market=US")
                .contains("reason=no-signal");
    }

    @Test
    void run_skippedReport_leavesZeroCount() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-22T10:00:00Z"), ZoneOffset.UTC);
        MarketTopJob job = new MarketTopJob(marketTopService, clock);
        java.time.LocalDate rankDate = java.time.LocalDate.of(2026, 9, 22);
        when(marketTopService.generate(rankDate, Market.A_SHARE))
                .thenReturn(GenerationReport.skipped("2026-09-22", "2026-09-21"));
        when(marketTopService.generate(rankDate, Market.HK))
                .thenReturn(
                        GenerationReport.skipped(Market.HK, "2026-09-22", "no-pool（HK 活跃标的池为空）"));
        when(marketTopService.generate(rankDate, Market.US))
                .thenReturn(
                        GenerationReport.skipped(Market.US, "2026-09-22", "no-pool（US 活跃标的池为空）"));

        job.run();

        assertThat(job.lastProcessedCount()).isZero();
        assertThat(job.lastRunDetail()).contains("snapshotGuard");
    }
}
