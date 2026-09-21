package com.info.platform.domain.push;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 异动记录实体（anomaly_event 表）。
 *
 * <p>领域层纯净：仅依赖 JDK 类型，可脱离容器单测、可移植。持久化字段（id/时间戳）由基础设施层 {@code AnomalyRepositoryImpl} 经 {@link
 * #reconstruct} 回填。
 *
 * <p>生命周期：{@link AnomalyDetectionJob} 命中阈值时 {@link #create} 新建（pushed=false）并落库； {@link
 * #markPushed} 由 T14 {@code PushService} 消费 {@link AnomalyDetectedEvent} 推送成功后置
 * pushed=true。本批（T13）仅负责发事件、不置位 pushed。
 *
 * <p>对齐技术方案 §4.2 anomaly_event DDL：无 version 列（追加型事件流水，无并发 UPDATE 竞争）。
 */
public class AnomalyRecord {

    private Long id;
    private final Long subjectId;
    private final AnomalyType anomalyType;
    private final BigDecimal changePct;
    private final BigDecimal currentPrice;
    private final Instant triggerTime;
    private final String detail;
    private boolean pushed;
    private Instant createdAt;
    private Instant updatedAt;

    private AnomalyRecord(
            Long id,
            Long subjectId,
            AnomalyType anomalyType,
            BigDecimal changePct,
            BigDecimal currentPrice,
            Instant triggerTime,
            String detail,
            boolean pushed,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.subjectId = subjectId;
        this.anomalyType = anomalyType;
        this.changePct = changePct;
        this.currentPrice = currentPrice;
        this.triggerTime = triggerTime;
        this.detail = detail;
        this.pushed = pushed;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * 构建新异动记录（id/时间戳留空，落库后回填；pushed=false）。
     *
     * @param subjectId 标的内部主键
     * @param anomalyType 异动类型
     * @param changePct 触发时的涨跌幅（%），允许为空（非涨跌幅类型时）
     * @param currentPrice 触发时现价，允许为空
     * @param triggerTime 触发时刻（建议整秒 Instant，保证持久化字符串定长可排序）
     * @param detail 人读详情，允许为空
     */
    public static AnomalyRecord create(
            Long subjectId,
            AnomalyType anomalyType,
            BigDecimal changePct,
            BigDecimal currentPrice,
            Instant triggerTime,
            String detail) {
        Objects.requireNonNull(subjectId, "subjectId 必填");
        Objects.requireNonNull(anomalyType, "anomalyType 必填");
        Objects.requireNonNull(triggerTime, "triggerTime 必填");
        return new AnomalyRecord(
                null,
                subjectId,
                anomalyType,
                changePct,
                currentPrice,
                triggerTime,
                detail,
                false,
                null,
                null);
    }

    /** 从持久化数据重建实体（基础设施层回读时用）。 */
    public static AnomalyRecord reconstruct(
            Long id,
            Long subjectId,
            AnomalyType anomalyType,
            BigDecimal changePct,
            BigDecimal currentPrice,
            Instant triggerTime,
            String detail,
            boolean pushed,
            Instant createdAt,
            Instant updatedAt) {
        return new AnomalyRecord(
                id,
                subjectId,
                anomalyType,
                changePct,
                currentPrice,
                triggerTime,
                detail,
                pushed,
                createdAt,
                updatedAt);
    }

    /** T14 推送成功后置位 pushed（幂等：重复调用安全）。 */
    public void markPushed() {
        this.pushed = true;
    }

    public Long getId() {
        return id;
    }

    public Long getSubjectId() {
        return subjectId;
    }

    public AnomalyType getAnomalyType() {
        return anomalyType;
    }

    public Optional<BigDecimal> getChangePct() {
        return Optional.ofNullable(changePct);
    }

    public Optional<BigDecimal> getCurrentPrice() {
        return Optional.ofNullable(currentPrice);
    }

    public Instant getTriggerTime() {
        return triggerTime;
    }

    public Optional<String> getDetail() {
        return Optional.ofNullable(detail);
    }

    public boolean isPushed() {
        return pushed;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
