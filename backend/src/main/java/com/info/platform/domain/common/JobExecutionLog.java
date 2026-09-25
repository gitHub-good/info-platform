package com.info.platform.domain.common;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Job 执行记录实体（job_execution_log 表）。
 *
 * <p>领域层纯净：仅依赖 JDK 类型，可脱离容器单测、可移植。持久化字段（id/时间戳）由基础设施层 {@code JobExecutionLogRepositoryImpl} 经
 * {@link #reconstruct} 回填。状态翻转由 {@link #markSuccess} / {@link #markFailed} 承载（STARTED →
 * SUCCESS/FAILED，单向终态）。
 *
 * <p>生命周期：{@link com.info.platform.infrastructure.common.JobExecutionRecorder#start} 创建 STARTED
 * 记录（仅 startTime）并落库 → Job 方法 proceed → {@link
 * com.info.platform.infrastructure.common.JobExecutionRecorder#success} / {@link
 * com.info.platform.infrastructure.common.JobExecutionRecorder#failed} 翻转终态（填 endTime/duration）并更新。
 *
 * <p>对齐 V12 DDL：无 version 列（追加型流水；同任务经 JobExecutor 运行守卫串行，无并发 UPDATE 竞争）。
 */
public class JobExecutionLog {

    private Long id;
    private final String jobName;
    private final Instant startTime;
    private Instant endTime;
    private JobExecutionStatus status;
    private Long durationMillis;
    private int processedCount;
    private int errorCount;
    private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;

    private JobExecutionLog(
            Long id,
            String jobName,
            Instant startTime,
            Instant endTime,
            JobExecutionStatus status,
            Long durationMillis,
            int processedCount,
            int errorCount,
            String errorMessage,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.jobName = jobName;
        this.startTime = startTime;
        this.endTime = endTime;
        this.status = status;
        this.durationMillis = durationMillis;
        this.processedCount = processedCount;
        this.errorCount = errorCount;
        this.errorMessage = errorMessage;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * 构建起始记录（STARTED）：id/时间戳留空，落库后回填；仅 startTime，endTime/duration 留空。
     *
     * @param jobName Job 名（派生自类名，如 "PolicyFetchJob"）
     * @param startTime 执行开始时刻（建议整秒 Instant，保证持久化字符串定长可排序）
     */
    public static JobExecutionLog create(String jobName, Instant startTime) {
        Objects.requireNonNull(jobName, "jobName 必填");
        Objects.requireNonNull(startTime, "startTime 必填");
        return new JobExecutionLog(
                null,
                jobName,
                startTime,
                null,
                JobExecutionStatus.STARTED,
                null,
                0,
                0,
                null,
                null,
                null);
    }

    /** 从持久化数据重建实体（基础设施层回读时用）。 */
    public static JobExecutionLog reconstruct(
            Long id,
            String jobName,
            Instant startTime,
            Instant endTime,
            JobExecutionStatus status,
            Long durationMillis,
            int processedCount,
            int errorCount,
            String errorMessage,
            Instant createdAt,
            Instant updatedAt) {
        return new JobExecutionLog(
                id,
                jobName,
                startTime,
                endTime,
                status,
                durationMillis,
                processedCount,
                errorCount,
                errorMessage,
                createdAt,
                updatedAt);
    }

    /**
     * 翻转为成功终态：填 endTime、按 (endTime - startTime) 计 duration、记处理/错误计数（明细为 null——既有语义）。
     */
    public void markSuccess(Instant endTime, int processedCount, int errorCount) {
        markSuccess(endTime, processedCount, errorCount, null);
    }

    /**
     * 翻转为成功终态并携带留痕明细（T71 / ADR-0036 §2）。
     *
     * <p>error_message 列语义由「FAILED 异常摘要」扩展为「终态附加信息」：FAILED = 异常摘要（不变）；SUCCESS = 留痕明细
     * （仅计数型 Job 使用，如留痕清理轮的逐表删除行数）。原三参签名语义不变（委托 detail=null）。
     *
     * @param detail 终态附加信息（null 时列保持 NULL）
     */
    public void markSuccess(Instant endTime, int processedCount, int errorCount, String detail) {
        Objects.requireNonNull(endTime, "endTime 必填");
        this.endTime = endTime;
        this.durationMillis =
                Math.max(0, startTime.until(endTime, java.time.temporal.ChronoUnit.MILLIS));
        this.processedCount = processedCount;
        this.errorCount = errorCount;
        this.status = JobExecutionStatus.SUCCESS;
        this.errorMessage = detail;
    }

    /** 翻转为失败终态：填 endTime、duration、异常摘要、处理/错误计数。 */
    public void markFailed(
            Instant endTime, String errorMessage, int processedCount, int errorCount) {
        Objects.requireNonNull(endTime, "endTime 必填");
        this.endTime = endTime;
        this.durationMillis =
                Math.max(0, startTime.until(endTime, java.time.temporal.ChronoUnit.MILLIS));
        this.processedCount = processedCount;
        this.errorCount = errorCount;
        this.errorMessage = errorMessage;
        this.status = JobExecutionStatus.FAILED;
    }

    public Long getId() {
        return id;
    }

    public String getJobName() {
        return jobName;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Optional<Instant> getEndTime() {
        return Optional.ofNullable(endTime);
    }

    public JobExecutionStatus getStatus() {
        return status;
    }

    public Optional<Long> getDurationMillis() {
        return Optional.ofNullable(durationMillis);
    }

    public int getProcessedCount() {
        return processedCount;
    }

    public int getErrorCount() {
        return errorCount;
    }

    public Optional<String> getErrorMessage() {
        return Optional.ofNullable(errorMessage);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
