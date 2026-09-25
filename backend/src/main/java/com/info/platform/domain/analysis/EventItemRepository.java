package com.info.platform.domain.analysis;

import java.util.List;

/**
 * 结构化事件仓储端口（{@code event_item}，M15 T122，ADR-0046 裁决 1）。
 *
 * <p>领域层纯净接口。写路径 UPSERT by {@code UNIQUE(news_id)}（拆批重试与补跑重入幂等）；读路径供事件流（T127）与 热度聚合（T123 经
 * HeatSnapshotRepository 的窗口视图）消费。
 */
public interface EventItemRepository {

    /**
     * UPSERT 单条事件（by news_id：存在即整体更新为本次产物——重试幂等）。
     *
     * @return 落库后的实体（回填 id/时间戳）
     */
    EventItem upsert(EventItem item);

    /**
     * 批量按 id 取事件（事件流卡片组装 news 标题等 join 数据面由查询服务承担，此处仅实体回读）。
     *
     * @param ids event_item.id 清单
     */
    List<EventItem> findByIds(List<Long> ids);

    /**
     * 事件流分页（M15 T127，方案 §4.8）：四维可空筛选 + beforeId 游标（id &lt; beforeId）+ id DESC； 与行业下钻 events
     * 清单同口径（affected ∋ 行业引号定界 LIKE），卡片 news 标题/链接 join news_item。
     *
     * @param filter 四维筛选（null 维度 = 不过滤）
     * @param beforeId 游标（null = 首页）
     * @param limit 页大小（1~50 由应用层校验）
     */
    List<EventStreamItem> findStreamItems(EventStreamFilter filter, Long beforeId, int limit);

    /** 事件流筛选总数（与 findStreamItems 同口径；无筛选 = event_item 全量，§4.10 对账）。 */
    long countStreamItems(EventStreamFilter filter);

    /** 事件流四维筛选（null 维度 = 不过滤；行业 = 申万枚举名，affected_industries ∋ 该行业）。 */
    record EventStreamFilter(
            EventType eventType, String industry, Importance importance, Direction direction) {

        /** 无筛选全量视图（§4.10 对账口径基准）。 */
        public static EventStreamFilter unfiltered() {
            return new EventStreamFilter(null, null, null, null);
        }
    }

    /** 事件流卡片行（event_item 全字段 + news 标题/链接 join 数据面）。 */
    record EventStreamItem(EventItem event, String newsTitle, String newsUrl) {}
}
