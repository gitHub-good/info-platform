package com.info.platform.application.subscription;

import java.util.List;

/**
 * 命中内容 + 命中原因（应用层值对象，T27；T43 增补 {@code keywords}）。
 *
 * <p>{@link FeedMatcher} 输出：某条 {@link FeedContent} 命中了用户某条订阅，附命中原因串（订阅类型 + key，如
 * 「主题订阅:半导体国产替代」/「标的订阅:贵州茅台」/「事件类型订阅:公告」/「政策主题订阅:货币政策」）与命中关键词列表。 FeedService 据此映射为响应
 * FeedItem（{@code matchReason}/{@code keywords} 字段）。
 *
 * <p>{@code keywords} 与 {@code matchReason} 同源（首次命中的那条订阅）：主题/政策主题为 subKey（文本命中，标题或摘要必现）； 标的为标的展示名（按
 * id/行业命中，文本未必出现，前端仅出现时高亮）；事件类型按类型命中无文本关键词，为空列表。多订阅同命中的聚合原因留 M3 优化。
 *
 * @param content 命中的统一内容
 * @param matchReason 命中原因（订阅类型 + key），非空
 * @param keywords 命中关键词（可高亮文本词；无文本命中词为空列表，不可为 null）
 */
public record MatchedFeedContent(FeedContent content, String matchReason, List<String> keywords) {

    /** 紧凑构造器：命中原因不可空；关键词不可 null（统一空列表）。 */
    public MatchedFeedContent {
        if (matchReason == null || matchReason.isBlank()) {
            throw new IllegalArgumentException("matchReason 不能为空");
        }
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
    }

    /** 便捷构造：无文本命中词（事件类型等按类型命中）。 */
    public MatchedFeedContent(FeedContent content, String matchReason) {
        this(content, matchReason, List.of());
    }
}
