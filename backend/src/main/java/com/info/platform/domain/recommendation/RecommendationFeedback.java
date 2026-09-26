package com.info.platform.domain.recommendation;

import java.time.Instant;

/**
 * 反馈流水实体（{@code recommendation_feedback}，M16 方案 §4.1/§4.7）：append-only——同卡可先 DISLIKE 后 UNDO_MUTE 再
 * DISLIKE，降噪状态由 {@link RecommendationMute} 表承载；滚动 30 天升级计数以本表为查询源。
 *
 * <p>领域层纯净（仅 JDK）。
 */
public class RecommendationFeedback {

    private final Long id;
    private final long userId;
    private final long cardId;
    private final FeedbackAction action;
    private final String comboKey;
    private final Instant createdAt;
    private final Instant updatedAt;

    private RecommendationFeedback(
            Long id,
            long userId,
            long cardId,
            FeedbackAction action,
            String comboKey,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.userId = userId;
        this.cardId = cardId;
        this.action = java.util.Objects.requireNonNull(action, "action 必填");
        this.comboKey = comboKey;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * 追加一条反馈（append-only；id/时间戳由仓储回填）。
     *
     * @param comboKey DISLIKE/UNDO_MUTE 必填（升级计数滚动窗查询键）；USEFUL/ADD_WATCHLIST 可空
     */
    public static RecommendationFeedback append(
            long userId, long cardId, FeedbackAction action, String comboKey) {
        boolean comboRequired =
                action == FeedbackAction.DISLIKE || action == FeedbackAction.UNDO_MUTE;
        if (comboRequired && (comboKey == null || comboKey.isBlank())) {
            throw new IllegalArgumentException("DISLIKE/UNDO_MUTE 反馈必须携带 comboKey: " + action);
        }
        return new RecommendationFeedback(null, userId, cardId, action, comboKey, null, null);
    }

    /** 从持久化数据重建（基础设施层回读）。 */
    public static RecommendationFeedback reconstruct(
            Long id,
            long userId,
            long cardId,
            FeedbackAction action,
            String comboKey,
            Instant createdAt,
            Instant updatedAt) {
        return new RecommendationFeedback(
                id, userId, cardId, action, comboKey, createdAt, updatedAt);
    }

    public Long getId() {
        return id;
    }

    public long getUserId() {
        return userId;
    }

    public long getCardId() {
        return cardId;
    }

    public FeedbackAction getAction() {
        return action;
    }

    public String getComboKey() {
        return comboKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
