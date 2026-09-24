package com.info.platform.application.subscription;

import java.util.List;

/**
 * 个人信息流列表视图（应用层，对齐技术方案 §4.1.6 游标分页响应）。
 *
 * @param items 信息流条目（订阅命中内容 + 每日推荐，按 publishedAt 倒序，单页 ≤20）
 * @param nextCursor 下一页游标（末条合成 id）；无下一页为 null
 * @param recommendationPending 每日推荐未就绪（当日简报未触发/在途生成中，P1-5a 增量字段：feed 只读不等待，
 *     前端可据此渲染「推荐生成中」占位；推荐就绪/兜底/空池均为 false）
 */
public record FeedListView(List<FeedItem> items, Long nextCursor, boolean recommendationPending) {

    /** 紧凑构造器：列表不可变。 */
    public FeedListView {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** 既有 2 参构造（推荐就绪口径，兼容既有调用点与测试）。 */
    public FeedListView(List<FeedItem> items, Long nextCursor) {
        this(items, nextCursor, false);
    }
}
