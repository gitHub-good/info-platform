package com.info.platform.infrastructure.push;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;

/**
 * anomaly_event 表的持久化对象（PO）。
 *
 * <p>对齐 V5
 * 迁移：id/subject_id/anomaly_type/change_pct/current_price/trigger_time/detail/pushed/created_at/updated_at。
 * 按 §4.2 DDL 无 version 列（anomaly_event 为追加型事件流水，无并发 UPDATE 竞争）， 故不标 {@code @Version}， {@code
 * OptimisticLockerInnerInterceptor} 仅对带 {@code @Version} 的 PO 生效，本 PO 不受其影响。
 *
 * <p>{@code anomaly_type TINYINT} / {@code pushed TINYINT} 用 {@link Integer} 承载； {@code change_pct
 * DECIMAL(8,4)} / {@code current_price DECIMAL(12,4)} 用 {@link BigDecimal} 避免浮点精度损失； 时间戳存 ISO-8601
 * 文本。
 */
@TableName("anomaly_event")
public class AnomalyRecordPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("subject_id")
    private Long subjectId;

    @TableField("anomaly_type")
    private Integer anomalyType;

    @TableField("change_pct")
    private BigDecimal changePct;

    @TableField("current_price")
    private BigDecimal currentPrice;

    @TableField("trigger_time")
    private String triggerTime;

    @TableField("detail")
    private String detail;

    @TableField("pushed")
    private Integer pushed;

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

    public Long getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(Long subjectId) {
        this.subjectId = subjectId;
    }

    public Integer getAnomalyType() {
        return anomalyType;
    }

    public void setAnomalyType(Integer anomalyType) {
        this.anomalyType = anomalyType;
    }

    public BigDecimal getChangePct() {
        return changePct;
    }

    public void setChangePct(BigDecimal changePct) {
        this.changePct = changePct;
    }

    public BigDecimal getCurrentPrice() {
        return currentPrice;
    }

    public void setCurrentPrice(BigDecimal currentPrice) {
        this.currentPrice = currentPrice;
    }

    public String getTriggerTime() {
        return triggerTime;
    }

    public void setTriggerTime(String triggerTime) {
        this.triggerTime = triggerTime;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Integer getPushed() {
        return pushed;
    }

    public void setPushed(Integer pushed) {
        this.pushed = pushed;
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
