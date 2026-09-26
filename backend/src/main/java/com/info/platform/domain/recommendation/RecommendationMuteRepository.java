package com.info.platform.domain.recommendation;

import java.util.Optional;

/**
 * 降噪组合仓储端口（{@code recommendation_mute}，M16 方案 §4.7）。
 *
 * <p>领域层纯净接口。UPSERT by {@code UNIQUE(user_id, combo_key)}：新组合 INSERT；已有 LIFTED → reactivate 翻回
 * ACTIVE 续期（沿 Subscription reactivate 模式）。
 */
public interface RecommendationMuteRepository {

    /** 当前生效的降频组合（status='ACTIVE' 且未到期；无返回空——推送闸门降噪拦截判定）。 */
    Optional<RecommendationMute> findActiveByUserAndCombo(long userId, String comboKey);

    /** 按用户 + 组合键取行（任意状态，T134 DISIKE 升级判定用：triggerCount 增量与 LIFTED → reactivate 语义需读现值）。 */
    Optional<RecommendationMute> findByUserAndCombo(long userId, String comboKey);

    /**
     * UPSERT（新建或 reactivate 续期；muteDays/triggerCount/mutedUntil 以入参为准覆盖）。
     *
     * @return 落库后的实体（回填 id/时间戳）
     */
    RecommendationMute upsert(RecommendationMute mute);

    /** 撤销降频（status → LIFTED；条件 UPDATE，已 LIFTED 返回 0）。 */
    int liftByUserAndCombo(long userId, String comboKey);
}
