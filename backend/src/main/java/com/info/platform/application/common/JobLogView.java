package com.info.platform.application.common;

import com.info.platform.domain.common.JobExecutionLog;
import java.time.Instant;

/**
 * Job 执行日志视图（{@code GET /api/v1/job-logs} 返回项），应用层 DTO。
 *
 * <p>从 {@link JobExecutionLog} 投影：{@code status} 用枚举名（STARTED/SUCCESS/FAILED，对齐前端徽章染色）；
 * 可空字段（endTime/durationMillis/errorMessage）以可空类型直传，前端按存在性渲染。
 */
public record JobLogView(
        Long id,
        String jobName,
        Instant startTime,
        Instant endTime,
        String status,
        Long durationMillis,
        int processedCount,
        int errorCount,
        String errorMessage) {

    public static JobLogView from(JobExecutionLog log) {
        return new JobLogView(
                log.getId(),
                log.getJobName(),
                log.getStartTime(),
                log.getEndTime().orElse(null),
                log.getStatus() == null ? null : log.getStatus().name(),
                log.getDurationMillis().orElse(null),
                log.getProcessedCount(),
                log.getErrorCount(),
                log.getErrorMessage().orElse(null));
    }
}
