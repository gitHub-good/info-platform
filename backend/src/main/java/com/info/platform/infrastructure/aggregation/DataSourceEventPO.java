package com.info.platform.infrastructure.aggregation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * data_source_event 表的持久化对象（PO）。
 *
 * <p>对齐 V7 迁移：id/source_code/event_type/subject_id/detail/created_at/updated_at。 按 §4.2 DDL 无
 * version 列 （追加型事件流水，无并发 UPDATE 竞争），故不标 {@code @Version}， {@code OptimisticLockerInnerInterceptor}
 * 仅对带 {@code @Version} 的 PO 生效，本 PO 不受其影响。
 *
 * <p>{@code event_type TINYINT} 用 {@link Integer} 承载； {@code subject_id} 可空； 时间戳存 ISO-8601 整秒文本（保证
 * created_at 字典序即时间序，{@code idx_dse_source_time} 范围扫描命中索引且结果正确）。
 */
@TableName("data_source_event")
public class DataSourceEventPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("source_code")
    private String sourceCode;

    @TableField("event_type")
    private Integer eventType;

    @TableField("subject_id")
    private Long subjectId;

    @TableField("detail")
    private String detail;

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

    public String getSourceCode() {
        return sourceCode;
    }

    public void setSourceCode(String sourceCode) {
        this.sourceCode = sourceCode;
    }

    public Integer getEventType() {
        return eventType;
    }

    public void setEventType(Integer eventType) {
        this.eventType = eventType;
    }

    public Long getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(Long subjectId) {
        this.subjectId = subjectId;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
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
