package com.info.platform.application.subscription;

import java.util.List;

/**
 * 订阅列表视图（游标分页，对齐技术方案 §4.4 游标分页 + §4.1.6 GET /api/v1/subscriptions?cursor=）。
 *
 * @param items 当前页订阅视图
 * @param nextCursor 下一页游标（末条 id）；null 表示无下一页
 */
public record SubscriptionListView(List<SubscriptionView> items, Long nextCursor) {}
