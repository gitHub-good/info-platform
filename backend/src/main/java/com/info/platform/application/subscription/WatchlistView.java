package com.info.platform.application.subscription;

import com.info.platform.domain.subscription.Watchlist;
import com.info.platform.domain.subscription.WatchlistStatus;
import java.util.List;

/**
 * 自选清单视图（应用层返回值，供接口层包装为 {@code Result}）。
 *
 * <p>对齐技术方案 §4.1.2：列表/单查均含清单项（items）。 不含内部 userId（前端即当前用户）/version/时间戳。
 */
public record WatchlistView(
        Long id, String name, String remark, int status, List<WatchlistItemView> items) {

    /** 领域聚合根 → 视图（状态映射为 code；清单项映射为视图列表）。 */
    static WatchlistView from(Watchlist watchlist) {
        WatchlistStatus status =
                watchlist.getStatus() == null ? WatchlistStatus.ENABLED : watchlist.getStatus();
        List<WatchlistItemView> items =
                watchlist.getItems().stream().map(WatchlistItemView::from).toList();
        return new WatchlistView(
                watchlist.getId(),
                watchlist.getName(),
                watchlist.getRemark(),
                status.code(),
                items);
    }
}
