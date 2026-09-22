package com.info.platform.infrastructure.common;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * job_execution_log 表的持久化对象（PO）。
 *
 * <p>对齐 V12
 * 迁移：id/job_name/start_time/end_time/status/duration_millis/processed_count/error_count/error_message/created_at/updated_at。
 * 无 version 列（追加型流水，无并发 UPDATE 竞争），不标 {@code @Version}，{@code OptimisticLockerInnerInterceptor} 仅对带
 * {@code @Version} 的 PO 生效，本 PO 不受其影响。
 *
 * <p>{@code status TEXT} 存枚举名（STARTED/SUCCESS/FAILED）；时间戳存 ISO-8601 整秒文本； {@code duration_millis} /
 * {@code processed_count} / {@code error_count} 用 {@link Integer} 承载（SQLite INTEGER 亲和）。
 */
@TableName("job_execution_log")
public class JobExecutionLogPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("job_name")
    private String jobName;

    @TableField("start_time")
    private String startTime;

    @TableField("end_time")
    private String endTime;

    @TableField("status")
    private String status;

    @TableField("duration_millis")
    private Integer durationMillis;

    @TableField("processed_count")
    private Integer processedCount;

    @TableField("error_count")
    private Integer errorCount;

    @TableField("error_message")
    private String errorMessage;

    @TableField("created_at")
    private String createdAt;

    @TableField("updated_at")
    private String updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getDurationMillis() {
        return durationMillis;
    }

    public void setDurationMillis(Integer durationMillis) {
        this.durationMillis = durationMillis;
    }

    public Integer getProcessedCount() {
        return processedCount;
    }

    public void setProcessedCount(Integer processedCount) {
        this.processedCount = processedCount;
    }

    public Integer getErrorCount() {
        return errorCount;
    }

    public void setErrorCount(Integer errorCount) {
        this.errorCount = errorCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}
