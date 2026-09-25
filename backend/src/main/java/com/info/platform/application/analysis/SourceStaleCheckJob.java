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
 */
@Component
public class SourceStaleCheckJob implements ManagedJob, JobRunStats {

    private static final Logger log = LoggerFactory.getLogger(SourceStaleCheckJob.class);

    private int lastProcessedCount;

    private String lastRunDetail;

    private final SourceStaleCheckService staleCheckService;

    public SourceStaleCheckJob(SourceStaleCheckService staleCheckService) {
        this.staleCheckService = staleCheckService;
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
                + "（恢复入库自动解除；不自动停用，源管理页/大盘徽章数据面，M15 方案 §4.7 / REQ AMB-01）";
    }

    @Override
    public ScheduleType scheduleType() {
        return ScheduleType.CRON;
    }

    /** 定时与手动触发共用入口：委托一轮全量检查；轮首重置统计。 */
    @Override
    public void run() {
        lastProcessedCount = 0;
        lastRunDetail = null;
        SourceStaleCheckService.StaleCheckReport report = staleCheckService.checkAll();
        lastProcessedCount = report.marked() + report.cleared(); // 本轮写库动作数
        lastRunDetail = report.detail();
        log.info("疑似停更检查完成（Job 留痕摘要）: {}", lastRunDetail);
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
