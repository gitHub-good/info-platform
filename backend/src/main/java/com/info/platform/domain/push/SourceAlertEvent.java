package com.info.platform.domain.push;

import java.time.Instant;

/**
 * 源异常/恢复推送事件（M14 T115，REQ-20260925-11 故事 3）：feed 域 {@code SourceAlertService} 在阈值命中/恢复时发布， {@code
 * PushService} 以 {@code @Async @EventListener} 消费走通知中心链路（对齐 AnomalyDetectedEvent 先例）。
 *
 * @param kind 事件类：ALERT 阈值告警 / RECOVERED 恢复通知
 * @param sourceCode 源稳定代码
 * @param sourceName 源展示名（人读文案用）
 * @param consecutiveFailures 告警时连续失败数（恢复时为恢复前最近一次告警记录的失败数）
 * @param failingSince 连续失败起点（最近一次成功时刻；无成功基线为 null——恢复事件恒 null）
 * @param errorSummary 最近失败摘要（恢复事件为 null）
 * @param episodeKey 幂等键段（告警=触发失败轮 lastAttemptAt；恢复=恢复轮成功时刻）——同一告警 episodes 间唯一， 防 push_record
 *     幂等键跨episode误判去重
 */
public record SourceAlertEvent(
        Kind kind,
        String sourceCode,
        String sourceName,
        int consecutiveFailures,
        Instant failingSince,
        String errorSummary,
        Instant episodeKey) {

    /** 事件类。 */
    public enum Kind {
        ALERT,
        RECOVERED
    }

    public SourceAlertEvent {
        if (kind == null || sourceCode == null || sourceCode.isBlank()) {
            throw new IllegalArgumentException("kind/sourceCode 必填");
        }
        if (episodeKey == null) {
            throw new IllegalArgumentException("episodeKey 必填（幂等键段）");
        }
    }
}
