package com.info.platform.application.analysis;

import com.info.platform.application.jobrun.JobRunStats;
import com.info.platform.application.jobrun.ManagedJob;
import com.info.platform.application.jobrun.ScheduleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 行业热度快照 Job（M15 T123，ADR-0046 裁决 4）：第 10 个收编任务——{@code INDUSTRY_HEAT_SNAPSHOT} FIXED_DELAY tick（默认
 * 30min，{@code job.INDUSTRY_HEAT_SNAPSHOT} 可配 10~60min）。 双窗现算 31×2 行 UPSERT（零 LLM——护栏 FUSED 也不停，方案
 * §4.6）。 本类只做 tick 入口与 JobRunStats 上报。
 *
 * <p>测试 profile 种子 {@code enabled=false} → 调度零注册（九 Job 惯例）；快照逻辑由单测直调 snapshotAll 验证。
 */
@Component
public class IndustryHeatSnapshotJob implements ManagedJob, JobRunStats {

    private static final Logger log = LoggerFactory.getLogger(IndustryHeatSnapshotJob.class);

    /** 轮次统计（JobExecutor 同 jobKey CAS 守卫保证串行；轮首重置）。 */
    private int lastProcessedCount;

    private String lastRunDetail;

    private final HeatSnapshotService snapshotService;

    public IndustryHeatSnapshotJob(HeatSnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    @Override
    public String jobKey() {
        return "INDUSTRY_HEAT_SNAPSHOT";
    }

    @Override
    public String displayName() {
        return "行业热度快照";
    }

    @Override
    public String description() {
        return "双窗（24h/7d）现算 31 申万行业热度（条数 × 事件加权 × 时间衰减，K1=10/impCoef 1.0-0.5-0.25/"
                + "半衰期 12h|48h）→ industry_heat_snapshot 62 行 UPSERT + basis 口径串（零 LLM，M15 方案 §4.5）";
    }

    @Override
    public ScheduleType scheduleType() {
        return ScheduleType.FIXED_DELAY;
    }

    /** 定时与手动触发共用入口：委托一轮快照；轮首重置统计。 */
    @Override
    public void run() {
        lastProcessedCount = 0;
        lastRunDetail = null;
        HeatSnapshotService.SnapshotReport report = snapshotService.snapshotAll();
        lastProcessedCount = report.rows();
        lastRunDetail = report.detail();
        log.info("行业热度快照 tick 完成（Job 留痕摘要）: {}", lastRunDetail);
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
