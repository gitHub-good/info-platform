package com.info.platform.application.retention;

import com.info.platform.application.jobrun.JobRunStats;
import com.info.platform.application.jobrun.ManagedJob;
import com.info.platform.application.jobrun.ScheduleType;
import org.springframework.stereotype.Component;

/**
 * 留痕数据清理任务（T71，方案 §4.5）——第 7 个收编 {@link ManagedJob}：注册为无条件 bean 即被任务中心收编 （可视/手动触发
 * 202+executionId/启停热切换），调度中心零改动。
 *
 * <p>调度：CRON 型（种子 {@code job.RETENTION_CLEANUP}，默认 {@code 0 30 3 * * ?} 每日 03:30，避开 06:00 标的池同步与
 * 09:00 每日推荐；测试 profile {@code retention.cleanup.enabled=false} 零注册）。业务执行委托 {@link
 * RetentionCleanupService}，留痕由 JobExecutor 统一驱动；实现 {@link JobRunStats} 上报轮次合计与四段明细 （SUCCESS 行
 * processed_count/error_message，ADR-0036 §2）。
 */
@Component
public class RetentionCleanupJob implements ManagedJob, JobRunStats {

    /** 任务键（{@code job.RETENTION_CLEANUP} 键后缀）。 */
    public static final String JOB_KEY = "RETENTION_CLEANUP";

    private final RetentionCleanupService service;

    /** 轮次统计（JobExecutor 同 jobKey CAS 守卫保证串行，普通字段即可；轮首重置）。 */
    private int lastProcessedCount;

    private String lastRunDetail;

    public RetentionCleanupJob(RetentionCleanupService service) {
        this.service = service;
    }

    @Override
    public String jobKey() {
        return JOB_KEY;
    }

    @Override
    public String displayName() {
        return "留痕数据清理";
    }

    @Override
    public String description() {
        return "按保留窗口清理四张留痕表过期行（任务日志/数据源事件/LLM 调用/阅读行为），窗口可配热生效，误配双重防御";
    }

    @Override
    public ScheduleType scheduleType() {
        return ScheduleType.CRON;
    }

    @Override
    public void run() {
        // 轮首重置：失败轮不误报上一轮计数
        lastProcessedCount = 0;
        lastRunDetail = null;
        RetentionCleanupService.CleanupResult result = service.runOnce();
        lastProcessedCount = Math.toIntExact(result.processedCount());
        lastRunDetail = result.detail();
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
