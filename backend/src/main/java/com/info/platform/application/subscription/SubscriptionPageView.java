package com.info.platform.application.subscription;

import java.util.List;

/**
 * 订阅列表页码模式视图（M26 T227，V3.0 前端改版 REQ-20260928-21 #9，沿 T220 EventStreamPageView 范式）：
 * {@code page} 参数出现即页码模式（M9 PageQuery 语义，offset 分页），与游标模式 {@link SubscriptionListView}
 * 契约各自闭合（无 nextCursor 字段）；条目字段面复用 {@code SubscriptionView}。
 *
 * @param total 当前用户订阅总数（与游标模式行集同口径——同一 WHERE user_id=? [AND sub_type=?]）
 * @param items 当前页订阅（id ASC，同游标模式排序）
 * @param page 页码（1 起，如实回显）
 * @param size 页大小（缺省 20，上限 50 由 PageQuery 校验）
 */
public record SubscriptionPageView(
        long total, List<SubscriptionView> items, int page, int size) {}
