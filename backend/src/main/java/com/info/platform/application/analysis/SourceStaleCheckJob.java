package com.info.platform.application.analysis;

import com.info.platform.application.jobrun.JobRunStats;
import com.info.platform.application.jobrun.ManagedJob;
import com.info.platform.application.jobrun.ScheduleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 疑似停更检查 Job（M15 T128，ADR-0046 裁决 4）：第 12 个收编任务——{@code SOURCE_STALE_CHECK} CRON（缺省 04:10 {@code 0
 * 10 4 * * ?}，避开 03:30 留痕清理与 06:00 标的池同步）。 独立小 Job 而非搭车 RETENTION_CLEANUP——手动触发验收与故障 隔离语义干净（裁决 4
 * 被否方案③）。测试 profile 种子 {@code enabled=false} → 调度零注册（十一 Job 惯例）。
 *
 * <p>M17 T147 / GAP-02：末段顺挂 {@link L2TraceRepair} 一轮孤儿 EXTRACTED 留痕行归位（幂等——无孤儿返回 0）——OBS-04
 * 归位动作获得每日触发面（此前仅手动工具语义无触发面），失败段式容错不阻断停更检查主链。
 */
@Component
public class SourceStaleCheckJob implements ManagedJob, JobRunStats {

    private static final Logger log = LoggerFactory.getLogger(SourceStaleCheckJob.class);

    private int lastProcessedCount;

    private String lastRunDetail;

    private final SourceStaleCheckService staleCheckService;

    private final L2TraceRepair l2TraceRepair;

    public SourceStaleCheckJob(
            SourceStaleCheckService staleCheckService, L2TraceRepair l2TraceRepair) {
        this.staleCheckService = staleCheckService;
        this.l2TraceRepair = l2TraceRepair;
    }

    @Override
    public String jobKey() {
        return "SOURCE_STALE_CHECK";
    }

    @Override
    public String displayName() {
        return "疑似停更检查";
    }

    @Override
    public String description() {
        return "各启用源滚动窗（缺省 7 天 / 月频源 35 天）净入库 = 0 → info_source.config.staleSince 疑似停更标记"
                + "（恢复入库自动解除；不自动停用，源管理页/大盘徽章数据面，M15 方案 §4.7 / REQ AMB-01）"
                + "；末段顺挂 L2TraceRepair 孤儿 EXTRACTED 留痕行归位（OBS-04 对账闭合，M17 T147 / GAP-02）";
    }

    @Override
    public ScheduleType scheduleType() {
        return ScheduleType.CRON;
    }

    /** 定时与手动触发共用入口：委托一轮全量检查 + 末段留痕归位；轮首重置统计。 */
    @Override
    public void run() {
        lastProcessedCount = 0;
        lastRunDetail = null;
        SourceStaleCheckService.StaleCheckReport report = staleCheckService.checkAll();
        lastProcessedCount = report.marked() + report.cleared(); // 本轮写库动作数
        lastRunDetail = report.detail();
        log.info("疑似停更检查完成（Job 留痕摘要）: {}", lastRunDetail);
        repairOrphanTraces();
    }

    /** GAP-02 触发面：孤儿 EXTRACTED 留痕行归位（失败不阻断停更检查——段式容错）。 */
    private void repairOrphanTraces() {
        try {
            int repaired = l2TraceRepair.repairOrphanExtractedRows();
            if (repaired > 0) {
                lastProcessedCount += repaired;
                lastRunDetail =
                        (lastRunDetail == null ? "" : lastRunDetail + "; ")
                                + "l2TraceRepair="
                                + repaired;
            }
        } catch (RuntimeException e) {
            log.warn("L2TraceRepair 归位段失败（不阻断停更检查）: {}", e.toString());
        }
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
