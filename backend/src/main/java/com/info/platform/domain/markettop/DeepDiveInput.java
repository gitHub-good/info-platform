package com.info.platform.domain.markettop;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 深析输入契约（M21 T182，方案 §4.4.2）：标的 + 五维分解 + Top 依据事件 + 关联/行业资讯 + 行情快照——<b>引用白名单的唯一合法来源</b> （{@link
 * #citationWhitelist()} 单源派生，对账与提示词共享同一输入对象，防两处组装漂移）。
 *
 * <p>领域纯净 record（零框架依赖）；由应用层 {@code DeepDiveService} 消费方组装（快照行/资讯回联/行情快照投影）。
 *
 * @param subject 标的三元组（industry 为申万一级行业——swPrimaryOf 映射输出，未归属为 null）
 * @param factors 五维分解（key/name/score/weight，权重按快照行 weight_basis 回读）
 * @param totalScore 因子总分（快照原值）
 * @param percentile 全市场百分位（0~100）
 * @param breakthrough 「有突破」候选标记
 * @param topEvents Top 依据事件（factor_detail catalyst+risk 按贡献降序，cap 6）
 * @param relatedNews 关联资讯（matched 回联窗内最近，cap 8）
 * @param industryNews 行业资讯（main_category=标的 SW 行业 7 日最近，cap 3）
 * @param marketSnapshot 当日行情快照（缺数键省略）
 * @param eventWindowDays 事件窗天数（模板兜底「近 N 日」口径来源）
 * @param totalEventCount 依据事件总数（三维护据条目合计——不受 topEvents cap 6 截断）
 * @param industryHeatRank 所属行业 24h 热度排名（1 起；未上榜/未归属为 0）
 */
public record DeepDiveInput(
        SubjectRef subject,
        List<FactorDim> factors,
        double totalScore,
        long percentile,
        boolean breakthrough,
        List<EventFact> topEvents,
        List<NewsFact> relatedNews,
        List<NewsFact> industryNews,
        Map<String, Double> marketSnapshot,
        int eventWindowDays,
        int totalEventCount,
        int industryHeatRank) {

    public DeepDiveInput {
        subject = subject == null ? new SubjectRef("", "", null) : subject;
        factors = factors == null ? List.of() : List.copyOf(factors);
        topEvents = topEvents == null ? List.of() : List.copyOf(topEvents);
        relatedNews = relatedNews == null ? List.of() : List.copyOf(relatedNews);
        industryNews = industryNews == null ? List.of() : List.copyOf(industryNews);
        marketSnapshot = marketSnapshot == null ? Map.of() : Map.copyOf(marketSnapshot);
    }

    /** 标的三元组。 */
    public record SubjectRef(String code, String name, String industry) {}

    /** 五维分解条目。 */
    public record FactorDim(String key, String name, double score, double weight) {}

    /** 依据事件（factor_detail 条目投影；coef = 贡献系数）。 */
    public record EventFact(
            long eventId,
            String summary,
            String direction,
            String importance,
            String eventDate,
            double coef) {}

    /** 关联/行业资讯（sourceName 可空）。 */
    public record NewsFact(long newsId, String title, String publishedAt, String sourceName) {}

    /**
     * 引用白名单（§4.4.2 冻结口径）：{EVENT: topEvents[].eventId} ∪ {NEWS: relatedNews[].newsId ∪
     * industryNews[].newsId}——深析输出 citations ⊆ 本集合即零幻觉（T187 验收断言面）。
     */
    public Set<Citation> citationWhitelist() {
        Set<Citation> whitelist = new LinkedHashSet<>();
        for (EventFact event : topEvents) {
            whitelist.add(Citation.event(event.eventId()));
        }
        for (NewsFact news : relatedNews) {
            whitelist.add(Citation.news(news.newsId()));
        }
        for (NewsFact news : industryNews) {
            whitelist.add(Citation.news(news.newsId()));
        }
        return whitelist;
    }
}
