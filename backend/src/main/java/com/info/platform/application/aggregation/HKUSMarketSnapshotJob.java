package com.info.platform.application.aggregation;

import com.info.platform.application.jobrun.JobRunStats;
import com.info.platform.application.jobrun.ManagedJob;
import com.info.platform.application.jobrun.ScheduleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 港美股行情快照 Job（M29 T252，ADR-0064 裁决 4）：第 20 个收编任务——{@code HKUS_MARKET_SNAPSHOT} FIXED_DELAY 缺省
 * 60min（30~120min 可配，{@code hkus.market-snapshot.*} yml → {@code job.HKUS_MARKET_SNAPSHOT} 种子热改）。
 * 本类只做 tick 入口与 JobRunStats 上报（二十 Job 惯例）；一职三责编排见 {@link HKUSMarketSnapshotService}。
 *
 * <p>测试 profile 种子 {@code enabled=false} → 调度零注册；快照逻辑由单测直调 service.run 验证（双源 Mock 零外呼）。
 */
@Component
public class HKUSMarketSnapshotJob implements ManagedJob, JobRunStats {

    private static final Logger log = LoggerFactory.getLogger(HKUSMarketSnapshotJob.class);

    private final HKUSMarketSnapshotService snapshotService;

    /** 轮次统计（JobExecutor 同 jobKey CAS 守卫保证串行；轮首重置）。 */
    private int lastProcessedCount;

    private String lastRunDetail;

    public HKUSMarketSnapshotJob(HKUSMarketSnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    @Override
    public String jobKey() {
        return "HKUS_MARKET_SNAPSHOT";
    }

    @Override
    public String displayName() {
        return "港美股行情快照";
    }

    @Override
    public String description() {
        return "港美股一职三责快照轮（60min 可配 30~120）：腾讯主/新浪备批量行情（50/请求 500ms 间隔，整轮失败轮级切备链留痕）"
                + "→ market_daily_snapshot 当日幂等 UPSERT（含市值/币种）+ 个股×行业就地聚合（市值加权，覆盖<80% 回退等权）"
                + "→ industry_market_snapshot 港美股行 + 美股代表集市值收敛（<20 亿 USD status=0 留池，每日首轮宽取维护升降级），"
                + "M29 方案 §4 C10";
    }

    @Override
    public ScheduleType scheduleType() {
        return ScheduleType.FIXED_DELAY;
    }

    /** 定时与手动触发共用入口；轮首重置统计。 */
    @Override
    public void run() {
        lastProcessedCount = 0;
        lastRunDetail = null;
        HKUSMarketSnapshotService.SnapshotReport report = snapshotService.run();
        lastProcessedCount = report.snapshotRows();
        lastRunDetail = report.detail();
        log.info("港美股行情快照 tick 完成（Job 留痕摘要）: {}", lastRunDetail);
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
