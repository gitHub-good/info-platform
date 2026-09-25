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
}
