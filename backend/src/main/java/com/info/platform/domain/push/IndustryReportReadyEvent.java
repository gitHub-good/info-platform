package com.info.platform.domain.push;

/**
 * 行业日报生成完成事件（M15 T124，方案 §4.5 步骤 5）：{@code DailyReportService} 在日报 SUCCESS 落库后发布，{@code
 * PushService} 以 {@code @Async @EventListener} 消费走通知中心 SSE 广播（Should——对齐 SourceAlertEvent /
 * PipelineFusedEvent 先例；每日 08:00 一条天然节流，无需 episode 去重）。
 *
 * @param reportDate 日报覆盖日（Asia/Shanghai yyyy-MM-dd，幂等键段）
 * @param narrativeDegraded true = 纯统计版（LLM 叙述失败降级，通知文案如实标注）
 * @param occurredAt 发布时刻
 */
public record IndustryReportReadyEvent(
        String reportDate, boolean narrativeDegraded, java.time.Instant occurredAt) {

    public IndustryReportReadyEvent {
        if (reportDate == null || reportDate.isBlank()) {
            throw new IllegalArgumentException("reportDate 必填（幂等键段）");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt 必填");
        }
    }
}
