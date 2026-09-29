package com.info.platform.application.subscription;

import java.util.List;

/**
 * 清单项分页视图（M9 页码契约：{total, items, page, size} + 排序回显）。
 *
 * <p>total 为该清单全部启用项数（与排序无关）；items 为当前页行（行情内联）。sort/dir 回显供前端 表头箭头对账：sort ∈ {addedAt, price,
 * changePct}，dir ∈ {asc, desc}。
 */
public record WatchlistItemsPagedView(
        long total,
        List<WatchlistItemPagedRow> items,
        int page,
        int size,
        String sort,
        String dir) {

    /** 空页视图（清单无项）。 */
    static WatchlistItemsPagedView empty(int page, int size, String sort, String dir) {
        return new WatchlistItemsPagedView(0L, List.of(), page, size, sort, dir);
    }
}
