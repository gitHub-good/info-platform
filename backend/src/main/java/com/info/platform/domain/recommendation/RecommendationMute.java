package com.info.platform.domain.recommendation;

import java.time.Instant;

/**
 * 降噪组合实体（{@code recommendation_mute}，M16 方案 §4.1/§4.7）：常驻状态表（行量有界 ~几十行，不入 retention 清理—— ACTIVE
 * 行是有效状态，清理会静默恢复推送）。
 *
 * <p>查询语义：{@code status='ACTIVE' 且 now < muted_until} 即拦截该组合推送；升级判定以 feedback 流水滚动 30 天 DISLIKE
 * 计数为准（trigger_count 仅留痕展示）。领域层纯净（仅 JDK）。
 */
public class RecommendationMute {

    /** 降频缺省天数（REQ 拍板六：DISLIKE → 7 天）。 */
    public static final int MUTE_DAYS = 7;

    /** 升级静默天数（Should 条款：滚动 30 天 ≥3 次 → 30 天）。 */
    public static final int ESCALATED_MUTE_DAYS = 30;

    private final Long id;
    private final long userId;
    private final String comboKey;
    private final int muteDays;
    private final Instant mutedUntil;
    private final int triggerCount;
    private final Instant lastDislikedAt;
    private final MuteStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;

    private RecommendationMute(
            Long id,
            long userId,
            String comboKey,
            int muteDays,
            Instant mutedUntil,
            int triggerCount,
            Instant lastDislikedAt,
            MuteStatus status,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.userId = userId;
        if (comboKey == null || comboKey.isBlank()) {
            throw new IllegalArgumentException("comboKey 必填");
        }
        this.comboKey = comboKey;
        this.muteDays = muteDays;
        this.mutedUntil = java.util.Objects.requireNonNull(mutedUntil, "mutedUntil 必填");
        this.triggerCount = triggerCount;
        this.lastDislikedAt = java.util.Objects.requireNonNull(lastDislikedAt, "lastDislikedAt 必填");
        this.status = java.util.Objects.requireNonNull(status, "status 必填");
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 新建降频组合（首次 DISLIKE：ACTIVE，muted_until = now + muteDays）。 */
    public static RecommendationMute create(
            long userId, String comboKey, int muteDays, Instant now) {
        return new RecommendationMute(
                null,
                userId,
                comboKey,
                muteDays,
                now.plus(java.time.Duration.ofDays(muteDays)),
                1,
                now,
                MuteStatus.ACTIVE,
                null,
                null);
    }

    /** 从持久化数据重建（基础设施层回读）。 */
    public static RecommendationMute reconstruct(
            Long id,
            long userId,
            String comboKey,
            int muteDays,
            Instant mutedUntil,
            int triggerCount,
            Instant lastDislikedAt,
            MuteStatus status,
            Instant createdAt,
            Instant updatedAt) {
        return new RecommendationMute(
                id,
                userId,
                comboKey,
                muteDays,
                mutedUntil,
                triggerCount,
                lastDislikedAt,
                status,
                createdAt,
                updatedAt);
    }

    /** 到期判定（ACTIVE 且 now &lt; muted_until 才拦截；LIFTED/到期不拦截）。 */
    public boolean isMuting(Instant now) {
        return status == MuteStatus.ACTIVE && now.isBefore(mutedUntil);
    }

    public Long getId() {
        return id;
    }

    public long getUserId() {
        return userId;
    }

    public String getComboKey() {
        return comboKey;
    }

    public int getMuteDays() {
        return muteDays;
    }

    public Instant getMutedUntil() {
        return mutedUntil;
    }

    public int getTriggerCount() {
        return triggerCount;
    }

    public Instant getLastDislikedAt() {
        return lastDislikedAt;
    }

    public MuteStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
