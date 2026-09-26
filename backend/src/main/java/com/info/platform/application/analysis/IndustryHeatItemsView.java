package com.info.platform.application.analysis;

import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.EventType;
import com.info.platform.domain.analysis.HeatSnapshotRepository;
import com.info.platform.domain.analysis.Importance;
import java.time.Instant;
import java.util.List;

/**
 * 行业热度下钻视图（M15 T123，方案 §4.8 {@code GET /api/v1/industry-heat/{industry}/items}）：该行业 L1 条目分页（news，含
 * L2 事件标记）/ 影响该行业的事件分页（events，与事件流同口径）；total 与榜单 news_count/event_count 对账相等（§4.10）。
 *
 * @param industry 行业名（申万枚举）
 * @param window 窗口线格式
 * @param type 清单类型（news / events）
 * @param total 窗口内总数（对账口径 = 榜单计数）
 * @param items 当前页条目（news 或 events 卡）
 * @param nextBeforeId 下页游标（尾页 null）
 */
public record IndustryHeatItemsView(
        String industry,
        String window,
        String type,
        long total,
        List<ItemView> items,
        Long nextBeforeId) {

    /**
     * 下钻条目卡（news 行与 events 行共用外形容器，未用字段 null）。 T162 trace-v1 溯源增量：news 行 url（A 级原文外链）； events 行
     * newsUrl（A 级）+ quote/sourceName（B 级兜底面）。
     */
    public record ItemView(
            Long newsId,
            Long eventId,
            String title,
            String sourceName,
            Instant publishedAt,
            Boolean hasEvent,
            EventType eventType,
            String summary,
            Direction direction,
            Importance importance,
            Instant eventTime,
            String url,
            String newsUrl,
            String quote) {

        static ItemView ofNews(HeatSnapshotRepository.IndustryNewsItem item) {
            return new ItemView(
                    item.newsId(),
                    null,
                    item.title(),
                    item.sourceName(),
                    item.publishedAt(),
                    item.hasEvent(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    item.url(),
                    null,
                    null);
        }

        static ItemView ofEvent(HeatSnapshotRepository.IndustryEventItem item) {
            return new ItemView(
                    item.newsId(),
                    item.eventId(),
                    item.newsTitle(),
                    item.sourceName(),
                    null,
                    null,
                    item.eventType(),
                    item.summary(),
                    item.direction(),
                    item.importance(),
                    item.eventTime(),
                    null,
                    item.newsUrl(),
                    item.quote());
        }
    }
}
