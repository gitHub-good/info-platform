package com.info.platform.domain.recommendation;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 反馈流水仓储端口（{@code recommendation_feedback}，M16 方案 §4.7）。
 *
 * <p>领域层纯净接口。append-only 追加；滚动 30 天升级计数查询（按用户 + 组合键 + DISLIKE）；同卡同动作 1h 幂等窗口查询（沿 reading_event
 * 去重惯例）；每卡最近动作批量查询（操作条已点动作回显）。
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

    /**
     * 同卡同动作幂等窗口查询（T134，方案 §4.7：1h 内重复 → 200 直返不落第二条流水）。
     *
     * @param since 窗口起点（含）
     */
    boolean existsSince(long userId, long cardId, FeedbackAction action, Instant since);

    /**
     * 每卡最近一条反馈动作（卡片流操作条回显，{@code idx_rf_card} 查询面；按 card_id 分组取 id 最大）。
     *
     * @param cardIds 卡片 id 列表（空列表返回空 Map；无流水的卡不落键）
     */
    Map<Long, FeedbackAction> findLatestActionsByCardIds(List<Long> cardIds);
}
