package com.info.platform.domain.recommendation;

/**
 * 卡片推送状态（{@code recommendation_card.push_status}，M16 方案 §4.1/§4.6）：PENDING → PUSHED / SKIPPED_QUOTA
 * / SKIPPED_MUTED 单向迁移（方案库 10 裁剪——不建独立迁移表，条件 UPDATE 承载）。
 */
public enum CardPushStatus {
    /** 已生成未过闸门（降噪/配额闸门前）。 */
    PENDING,
    /** 已 SSE 推送（占当日配额）。 */
    PUSHED,
    /** 超日上限静默（入中心与通知历史 SILENT，不弹 SSE）。 */
    SKIPPED_QUOTA,
    /** 降频/静默组合拦截（入中心与通知历史 SILENT，不弹 SSE）。 */
    SKIPPED_MUTED;

    /** 从持久化文本反查（未知值抛 {@code IllegalArgumentException}）。 */
    public static CardPushStatus fromName(String name) {
        if (name != null) {
            for (CardPushStatus status : values()) {
                if (status.name().equals(name)) {
                    return status;
                }
            }
        }
        throw new IllegalArgumentException("未知 card push_status: " + name);
    }
}
