package com.info.platform.domain.analysis;

import java.time.Instant;
import java.util.List;

/**
 * 行业热度快照仓储端口（{@code industry_heat_snapshot}，M15 T123，ADR-0046 裁决 1）。 领域层纯净接口：写路径 62 行
 * UPSERT（UNIQUE(industry, window_type)——重跑收敛为当前值）；读路径供榜单（排序视图）、窗口现算取数（join
 * news_analysis/news_item/event_item） 与行业下钻（对账口径与榜单同源）。容器 4 枚举不进榜（实体把守）。
 */
public interface HeatSnapshotRepository {

    /**
     * 批量 UPSERT 快照行（62 行常驻；含 0 分行沉底）。
     *
     * @return 累计受影响行数
     */
    int upsertAll(List<IndustryHeatSnapshot> snapshots);

    /** 榜单读：指定窗口 31 行，heat_score DESC、news_count DESC、industry ASC（0 分沉底稳定序）。 */
    List<IndustryHeatSnapshot> findBoard(HeatWindow window);

    /**
     * 窗口现算取数：[from, to) 内 PASS+DONE 条目（含容器——事件扩散面），LEFT JOIN 其事件（一条至多一事件 v1）。
     *
     * @param publishedFromIso news_item.published_at 下界（含）
     * @param publishedToIso news_item.published_at 上界（不含）
     */
    List<WindowItem> findWindowItems(String publishedFromIso, String publishedToIso);

    /**
     * 行业下钻 news 清单：该行业 DONE 条目（PASS + main=本行业），news_id DESC 游标分页——计数与榜单 news_count 同口径。
     *
     * @param beforeNewsId 游标（news_id &lt; beforeId；null = 首页）
     * @param limit 页大小（1~50 由应用层校验）
     */
    List<IndustryNewsItem> findIndustryNewsItems(
            String industry,
            String publishedFromIso,
            String publishedToIso,
            Long beforeNewsId,
            int limit);

    /** 行业下钻 news 总数（对账口径 = 榜单 news_count）。 */
    long countIndustryNewsItems(String industry, String publishedFromIso, String publishedToIso);

    /** 行业下钻 events 清单：affected_industries ∋ 该行业的事件（含 main == 本行业直接命中——与事件流同口径）， id DESC 游标分页。 */
    List<IndustryEventItem> findIndustryEventItems(
            String industry,
            String publishedFromIso,
            String publishedToIso,
            Long beforeEventId,
            int limit);

    /** 行业下钻 events 总数（对账口径 = 榜单 event_count）。 */
    long countIndustryEventItems(String industry, String publishedFromIso, String publishedToIso);

    /** 窗口现算单行投影（事件字段空 = 无事件；affected 已解析为枚举数组）。 */
    record WindowItem(
            String mainCategory,
            Instant publishedAt,
            Importance eventImportance,
            List<String> affectedIndustries) {}

    /**
     * 下钻 news 卡（含 L2 事件标记——hasEvent；url 为 news_item 原文外链，T162 trace-v1 A 级溯源字段）。
     */
    record IndustryNewsItem(
            long newsId,
            String title,
            String sourceName,
            Instant publishedAt,
            boolean hasEvent,
            String url) {}

    /**
     * 下钻事件卡（核心字段与事件流卡片同源，完整卡片归 T127 /events；newsUrl/quote/sourceName 为 T162
     * trace-v1 溯源字段——join news_item.url + info_source.name，quote 直读 event_item）。
     */
    record IndustryEventItem(
            long eventId,
            long newsId,
            String newsTitle,
            EventType eventType,
            String summary,
            Direction direction,
            Importance importance,
            Instant eventTime,
            String newsUrl,
            String quote,
            String sourceName) {}
}
