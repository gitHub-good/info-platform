package com.info.platform.domain.push;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 异动已检测领域事件（AnomalyDetectionJob 命中阈值并落库后发布）。
 *
 * <p><b>纯 POJO，不继承 Spring {@code ApplicationEvent}</b>——领域层保持纯净（ADR-0007，禁 import 框架类型， 由 {@code
 * LayeredArchitectureTest} 守护）。应用层 {@code AnomalyDetectionJob} 经 Spring {@code
 * ApplicationEventPublisher.publishEvent(Object)} 发布本对象（自 Spring 4.2 起接受任意 POJO），T14 {@code
 * PushService} 用 {@code @EventListener} 监听本类型消费。这样既走 Spring 事件驱动机制（ADR-0006），又不污染领域层。
 *
 * <p>跨域协作：本事件在 push 域发布、push 域消费（同域），不跨域 import 内部类；订阅配置在 T14 落地时由 push 应用层经 subscription 端口查询。
 *
 * <p>不可变。承载推送所需的最小信息（T14 据此查订阅、写 push_record、SSE 推送）。
 */
public final class AnomalyDetectedEvent {

    private final Long subjectId;
    private final AnomalyType anomalyType;
    private final BigDecimal changePct;
    private final BigDecimal currentPrice;
    private final Instant triggerTime;

    public AnomalyDetectedEvent(
            Long subjectId,
            AnomalyType anomalyType,
            BigDecimal changePct,
            BigDecimal currentPrice,
            Instant triggerTime) {
        this.subjectId = Objects.requireNonNull(subjectId, "subjectId 必填");
        this.anomalyType = Objects.requireNonNull(anomalyType, "anomalyType 必填");
        this.changePct = changePct;
        this.currentPrice = currentPrice;
        this.triggerTime = Objects.requireNonNull(triggerTime, "triggerTime 必填");
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
}
