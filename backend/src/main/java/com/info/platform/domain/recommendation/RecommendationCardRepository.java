package com.info.platform.domain.recommendation;

import java.util.Optional;

/**
 * 推荐卡片仓储端口（{@code recommendation_card}，M16 方案 §4.1/§4.5）。
 *
 * <p>领域层纯净接口。建卡走 {@code INSERT OR IGNORE}（{@code UNIQUE(user_id, event_id)} 幂等最后防线——同事件重复消费 直返
 * 0）；推送状态迁移为条件 UPDATE（T133 推送闸门承载：{@code WHERE push_status='PENDING'}）。
 */
public interface RecommendationCardRepository {

    /**
     * 幂等建卡（INSERT OR IGNORE）。
     *
     * @return 实插行数（0 = 同用户同事件已有卡——重复消费，调用方不视为错误）
     */
    int insertIgnore(RecommendationCard card);

    /** 按用户 + 事件取卡（幂等冲突观测与 FEED 扫描去重判定用；无卡返回空）。 */
    Optional<RecommendationCard> findByUserAndEvent(long userId, long eventId);
}
