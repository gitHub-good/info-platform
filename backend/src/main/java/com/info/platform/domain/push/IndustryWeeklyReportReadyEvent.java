package com.info.platform.domain.push;

/**
 * 行业周报生成完成事件（M17 T145 Should，REQ 条目 11）：{@code WeeklyReportService} 在周报 SUCCESS 落库且叙述非降级后发布，{@code
 * PushService} 以 {@code @Async @EventListener} 消费走通知中心 SSE 广播（沿 {@link IndustryReportReadyEvent}
 * 先例；每周一条天然节流）。
 *
 * @param weekStart 周一锚点（Asia/Shanghai yyyy-MM-dd，幂等键段）
 * @param narrativeDegraded true = 纯统计版（LLM 叙述失败降级，不发布本事件——字段预留对齐日报事件形态）
 * @param occurredAt 发布时刻
 */
public record IndustryWeeklyReportReadyEvent(
        String weekStart, boolean narrativeDegraded, java.time.Instant occurredAt) {

    public IndustryWeeklyReportReadyEvent {
        if (weekStart == null || weekStart.isBlank()) {
            throw new IllegalArgumentException("weekStart 必填（幂等键段）");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt 必填");
        }
    }
}
