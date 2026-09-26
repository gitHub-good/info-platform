package com.info.platform.application.valuation;

import com.info.platform.application.jobrun.JobRunStats;
import com.info.platform.application.jobrun.ManagedJob;
import com.info.platform.application.jobrun.ScheduleType;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 因子快照 Job（M20 T170，方案 §4.6）：第 16 个收编任务——{@code FACTOR_SNAPSHOT} CRON 缺省 {@code 0 30 17 * * ?} （盘后
 * 17:30，需求红线「次日 08:00 前完成」余量充足；{@code job.FACTOR_SNAPSHOT} 键 enabled/cron 热改）。 本类只做 tick 入口与
 * JobRunStats 上报（15 Job 惯例）。
 *
 * <p>测试 profile 种子 {@code enabled=false} → 调度零注册；快照逻辑由单测直调 snapshotAll 验证（行情 Mock 零外呼）。
 */
@Component
public class FactorSnapshotJob implements ManagedJob, JobRunStats {

    private static final Logger log = LoggerFactory.getLogger(FactorSnapshotJob.class);

    private final FactorSnapshotService snapshotService;

    private final Clock clock;

    /** 轮次统计（JobExecutor 同 jobKey CAS 守卫保证串行；轮首重置）。 */
    private int lastProcessedCount;

    private String lastRunDetail;

    public FactorSnapshotJob(FactorSnapshotService snapshotService, Clock clock) {
        this.snapshotService = snapshotService;
        this.clock = clock;
    }

    @Override
    public String jobKey() {
        return "FACTOR_SNAPSHOT";
    }

    @Override
    public String displayName() {
        return "因子快照";
    }

    @Override
    public String description() {
        return "盘后 17:30 全市场五因子计算（事件催化/行业传导/基本面边际/风险安全/估值水平——行业关联绕行"
                + " subject_master.industry 用事件+资讯双路派生）→ subject_factor_snapshot 5221 行/日幂等 UPSERT + "
                + "腾讯批量行情日快照（失败降级不阻塞四维），M20 方案 §4.6";
    }

    @Override
    public ScheduleType scheduleType() {
        return ScheduleType.CRON;
    }

    /** 定时与手动触发共用入口：快照口径日 = Asia/Shanghai 当日（幂等锚）；轮首重置统计。 */
    @Override
    public void run() {
        lastProcessedCount = 0;
        lastRunDetail = null;
        FactorSnapshotService.SnapshotReport report =
                snapshotService.snapshotAll(
                        clock.instant().atZone(FactorSnapshotService.SNAPSHOT_ZONE).toLocalDate());
        lastProcessedCount = report.snapshotRows();
        lastRunDetail = report.detail();
        log.info("因子快照 tick 完成（Job 留痕摘要）: {}", lastRunDetail);
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
