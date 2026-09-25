package com.info.platform.domain.push;

import java.time.Instant;

/**
 * 管道熔断告警事件（M15 T125，方案 §4.6）：{@code PipelineGuardService} 派生进入 FUSED 态时发布，{@code PushService} 以
 * {@code @Async @EventListener} 消费走通知中心 SSE 广播（对齐 SourceAlertEvent 先例；告警态内存节流——恢复前不重复，重启重告一次可容忍）。
 *
 * @param costMicros 触发时当日管道成本（微元）
 * @param budgetMicros 当日预算（微元）
 * @param occurredAt 触发时刻（幂等键段：当日上海日期 + 时刻）
 */
public record PipelineFusedEvent(long costMicros, long budgetMicros, Instant occurredAt) {

    public PipelineFusedEvent {
        if (budgetMicros <= 0) {
            throw new IllegalArgumentException("budgetMicros 须为正: " + budgetMicros);
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt 必填（幂等键段）");
        }
    }
}
