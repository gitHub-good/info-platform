package com.info.platform.application.analysis;

import java.util.List;

/**
 * 事件流页码模式视图（M25 T220，V3.0 前端改版 REQ-20260928-21 拍板四）：page 参数出现即页码模式（M9 PageQuery 语义，offset 分页），
 * 与游标模式 {@link EventStreamView} 契约各自闭合（无 nextBeforeId 字段）；卡片字段面复用 {@code EventCardView}。
 *
 * @param total 当前筛选总数（与游标模式同源 countStreamItems——两模式计数一致）
 * @param items 当前页事件卡（id DESC，同游标模式排序）
 * @param page 页码（1 起，如实回显）
 * @param size 页大小（缺省 20，上限 50 由 PageQuery 校验）
 */
public record EventStreamPageView(
        long total, List<EventStreamView.EventCardView> items, int page, int size) {}
