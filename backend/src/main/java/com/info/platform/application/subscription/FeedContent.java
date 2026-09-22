package com.info.platform.application.subscription;

import java.time.Instant;
import java.util.List;

/**
 * 信息流命中引擎的统一内容值对象（应用层，T27）。
 *
 * <p>把三类异构内容（公告 {@code Map} / 新闻 {@code Map} / 政策 {@link
 * com.info.platform.domain.policy.PolicyItem}）归一为同一形状，供 {@link FeedMatcher} 按订阅类型统一匹配——
 * 避免匹配器直接耦合各源的异构字段表示（保持匹配逻辑纯净、可单测、可扩展语义/AI 命中）。
 *
 * <p>字段语义：
 *
 * <ul>
 *   <li>{@code type} 内容类型（ANNOUNCE/NEWS/POLICY）；RECOMMENDATION 不走匹配器，由 FeedService 直接合并。
 *   <li>{@code contentId} 内容稳定标识（公告/新闻 externalId、政策 {@code id}），用作排序稳定副键与未来去重。
 *   <li>{@code title}/{@code summary} 标题/摘要——主题订阅（TOPIC）按 {@code contains} 命中关键词。
 *   <li>{@code publishedAt} 发布时间（Instant，可空）——信息流时间倒序排序与游标分页的排序键。
 *   <li>{@code source}/{@code url} 来源标签与详情链接（展示用）。
 *   <li>{@code subjectId}/{@code subjectCode}/{@code subjectName} 标的订阅（SUBJECT）关联的标的——公告/新闻按标的
 *       fetch，故内容天然归属某标的；政策三者均为 null（政策按行业关联，见 {@code relatedIndustries}）。
 *   <li>{@code relatedIndustries} 政策关联行业标签（仅政策；公告/新闻为空）——标的订阅按 {@code subject.industry ∈
 *       relatedIndustries} 命中政策。
 *   <li>{@code category} 公告分类（{@code column_name}，仅公告）——事件类型订阅（EVENT_TYPE）按分类命中。
 * </ul>
 *
 * <p>不可变 record；列表字段防御性拷贝。
 */
public record FeedContent(
        FeedItemType type,
        String contentId,
        String title,
        String summary,
        Instant publishedAt,
        String source,
        String url,
        Long subjectId,
        String subjectCode,
        String subjectName,
        List<String> relatedIndustries,
        String category) {

    /** 紧凑构造器：归一化可空字段与列表不可变。 */
    public FeedContent {
        title = title == null ? "" : title;
        summary = summary == null ? "" : summary;
        relatedIndustries = relatedIndustries == null ? List.of() : List.copyOf(relatedIndustries);
    }
}
