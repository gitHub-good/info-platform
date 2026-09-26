package com.info.platform.domain.recommendation;

/**
 * 反馈流水仓储端口（{@code recommendation_feedback}，M16 方案 §4.7）。
 *
 * <p>领域层纯净接口。append-only 追加；滚动 30 天升级计数查询（按用户 + 组合键 + DISLIKE）。
 */
public interface RecommendationFeedbackRepository {

    /** 追加一条反馈（id/时间戳由仓储回填）。 */
    void append(RecommendationFeedback feedback);

    /**
     * 滚动窗内该组合的 DISLIKE 次数（升级判定：≥escalateThreshold → 静默 30 天）。
     *
     * @param sinceIso created_at 下界（含，ISO-8601 文本 = now − escalateWindowDays）
     */
    long countDislikeByUserAndComboSince(long userId, String comboKey, String sinceIso);
}
