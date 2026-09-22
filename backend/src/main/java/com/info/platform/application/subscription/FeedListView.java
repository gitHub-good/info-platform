package com.info.platform.application.subscription;

import java.util.List;

/**
 * 个人信息流列表视图（应用层，对齐技术方案 §4.1.6 游标分页响应）。
 *
 * @param items 信息流条目（订阅命中内容 + 每日推荐，按 publishedAt 倒序，单页 ≤20）
 * @param nextCursor 下一页游标（末条合成 id）；无下一页为 null
 */
public record FeedListView(List<FeedItem> items, Long nextCursor) {

    /** 紧凑构造器：列表不可变。 */
    public FeedListView {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
