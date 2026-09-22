package com.info.platform.application.subscription;

/**
 * 命中内容 + 命中原因（应用层值对象，T27）。
 *
 * <p>{@link FeedMatcher} 输出：某条 {@link FeedContent} 命中了用户某条订阅，附命中原因串（订阅类型 + key，如
 * 「主题订阅:半导体国产替代」/「标的订阅:贵州茅台」/「事件类型订阅:公告」/「政策主题订阅:货币政策」）。 FeedService 据此映射为响应 FeedItem（{@code
 * matchReason} 字段）。
 *
 * @param content 命中的统一内容
 * @param matchReason 命中原因（订阅类型 + key），非空
 */
public record MatchedFeedContent(FeedContent content, String matchReason) {

    /** 紧凑构造器：命中原因不可空。 */
    public MatchedFeedContent {
        if (matchReason == null || matchReason.isBlank()) {
            throw new IllegalArgumentException("matchReason 不能为空");
        }
    }
}
