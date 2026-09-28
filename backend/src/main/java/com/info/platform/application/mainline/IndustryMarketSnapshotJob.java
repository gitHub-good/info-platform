package com.info.platform.application.mainline;

import com.info.platform.application.jobrun.JobRunStats;
import com.info.platform.application.jobrun.ManagedJob;
import com.info.platform.application.jobrun.ScheduleType;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 行业行情快照 Job（M27 T242，方案 §4.2.4 + ADR-0063 裁决 6）：第 17 个收编任务——{@code INDUSTRY_MARKET_SNAPSHOT}
 * FIXED_DELAY 缺省 30min（15~60min 可配，盘中一轮 1 请求；收盘后首轮即终值、非交易轮 UPSERT 同值幂等——不设独立定格 CRON，方案 §3.3 裁决
 * 3）；{@code job.INDUSTRY_MARKET_SNAPSHOT} 键 enabled/intervalMillis 热改。本类只做 tick 入口与 JobRunStats
 * 上报；双通道编排在 {@link IndustryMarketSnapshotService}。
 *
 * <p>测试 profile 种子 {@code enabled=false} → 调度零注册；采集逻辑由单测直调 run 验证（两通道 Mock 零外呼）。
 */
@Component
public class IndustryMarketSnapshotJob implements ManagedJob, JobRunStats {

    private static final Logger log = LoggerFactory.getLogger(IndustryMarketSnapshotJob.class);

    private final IndustryMarketSnapshotService snapshotService;

    private final Clock clock;

    /** 轮次统计（JobExecutor 同 jobKey CAS 守卫保证串行；轮首重置）。 */
    private int lastProcessedCount;

    private String lastRunDetail;

    public IndustryMarketSnapshotJob(IndustryMarketSnapshotService snapshotService, Clock clock) {
        this.snapshotService = snapshotService;
        this.clock = clock;
    }

    @Override
    public String jobKey() {
        return "INDUSTRY_MARKET_SNAPSHOT";
    }

    @Override
    public String displayName() {
        return "行业行情快照";
    }

    @Override
    public String description() {
        return "盘中每 30min 双通道行业行情采集（主东财 push2 板块 fs=m:90+t:2 → swPrimaryOf 聚合 31 申万行业，失败当轮切"
                + "腾讯板块排行 SW31 直出）→ industry_market_snapshot 当日行幂等 UPSERT（板块行 + 行业行 ~117 行/日）；"
                + "双通道全败沿用旧快照（页面 stale 标注），连续 5 轮失败通知中心告警，M27 方案 §4.2";
    }

    @Override
    public ScheduleType scheduleType() {
        return ScheduleType.FIXED_DELAY;
    }

    /** 定时与手动触发共用入口：快照口径日 = Asia/Shanghai 当日（幂等锚）；轮首重置统计。 */
    @Override
    public void run() {
        lastProcessedCount = 0;
        lastRunDetail = null;
        IndustryMarketSnapshotService.SnapshotReport report = snapshotService.run();
        lastProcessedCount = report.snapshotRows();
        lastRunDetail = report.detail();
        log.info("行业行情快照 tick 完成（Job 留痕摘要）: {}", lastRunDetail);
    }

    @Override
    public int lastProcessedCount() {
        return lastProcessedCount;
    }

    @Override
    public String lastRunDetail() {
        return lastRunDetail;
    }
}
