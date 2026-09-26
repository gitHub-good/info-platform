package com.info.platform.domain.recommendation;

import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.EventItem;
import com.info.platform.domain.analysis.EventType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 推荐卡片仓储端口（{@code recommendation_card}，M16 方案 §4.1/§4.5/§4.6/§4.8）。
 *
 * <p>领域层纯净接口。建卡走 {@code INSERT OR IGNORE}（{@code UNIQUE(user_id, event_id)} 幂等最后防线——同事件重复消费 直返
 * 0）；推送状态迁移为条件 UPDATE（T133 推送闸门承载：{@code WHERE push_status='PENDING'} 单向迁移）；FEED 消费扫描为 {@code
 * event_item LEFT JOIN recommendation_card}（user 维去重 + 20s 缓冲 + 24h 补跑窗 + news 标题 join，方案 §3.2）。
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

    /**
     * FEED 消费扫描（T133，方案 §3.2 裁决 2）：该用户未消费的事件（{@code event_item e LEFT JOIN recommendation_card c ON
     * c.event_id = e.id AND c.user_id = ? WHERE c.id IS NULL}）+ 落库缓冲/补跑窗 + news 标题 join，按 e.id 升序。
     *
     * @param userId 消费用户（LEFT JOIN user 维——他人已卡不阻断本人消费）
     * @param createdBefore 落库缓冲上界（now−20s，防 L2 落库事务竞态；含）
     * @param createdSince 补跑窗下界（now−24h，重启/降级恢复后照常处理；含）
     * @param limit 单轮扫描上限（防御性）
     */
    List<FeedEvent> findUnconsumedEvents(
            long userId, Instant createdBefore, Instant createdSince, int limit);

    /** 推送闸门扫描（方案 §4.6 步骤 1：本 tick 新卡 + 当日遗留 PENDING；按 id 升序）。 */
    List<RecommendationCard> findPendingByUser(long userId);

    /** 当日已推计数（{@code push_status='PUSHED' AND pushed_at >= since}——含 SSE 态，日上限口径；SILENT 态不占）。 */
    long countPushedSince(long userId, Instant since);

    /** 条件迁移 PUSHED（{@code WHERE push_status='PENDING'}；返回 0 = 已迁移/终态，幂等防线）。 */
    int markPushed(long cardId, Instant pushedAt);

    /** 条件迁移 SKIPPED_QUOTA / SKIPPED_MUTED（{@code WHERE push_status='PENDING'} 单向）。 */
    int markSkipped(long cardId, CardPushStatus target);

    /** 卡片流分页（§4.8：四维可空筛选 + beforeId 游标 + id DESC）。 */
    List<RecommendationCard> findByUserCursor(
            long userId, CardFilter filter, Long beforeId, int limit);

    /** 卡片流筛选总数（与 findByUserCursor 同口径；无筛选 = 该用户全量卡，对账基准）。 */
    long countByUser(long userId, CardFilter filter);

    /** 按主键取卡（详情端点；owner 校验在应用层）。 */
    Optional<RecommendationCard> findById(long cardId);

    /**
     * 条件置采纳（T134，方案 §4.7：{@code UPDATE SET adopted=1 WHERE id=? AND adopted=0}）。
     *
     * @return 1 = 本次首置成功（调用方同点落 ACT 埋点）；0 = 已采纳（幂等防线——ACT 不重复落，对账恒等）
     */
    int markAdopted(long cardId);

    /** 条件置已读（T134：{@code WHERE read=0}；返回 0 = 已读，幂等 200 直返语义）。 */
    int markRead(long cardId);

    /**
     * 刷新标的区快照（T134 ADD_WATCHLIST：inWatchlist 翻 true 后回写，操作条即时态与回看一致）。
     *
     * @return 实更新行数
     */
    int updateSubjects(long cardId, List<RecommendationCard.CardSubject> subjects);

    /** FEED 消费事件行（结构化事件 + news 标题 join——P3 主题命中面与数字白名单来源）。 */
    record FeedEvent(EventItem event, String newsTitle) {}

    /** 卡片流四维筛选（null 维度 = 不过滤；read 为 Boolean 三态）。 */
    record CardFilter(RecLevel level, EventType eventType, Direction direction, Boolean read) {

        /** 无筛选全量视图。 */
        public static CardFilter unfiltered() {
            return new CardFilter(null, null, null, null);
        }
    }
}
