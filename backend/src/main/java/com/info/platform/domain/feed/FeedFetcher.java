package com.info.platform.domain.feed;

import java.util.List;

/**
 * 单源取数端口（M13 T101，方案 §4.3；按 adapterType 路由到 RssFeedFetcher / JsonApiFeedFetcher / PresetFeedAdapter）。
 *
 * <p>返回 {@link FetchResult}（含补抓截断信号，ADR-0041）；newest-first，ID/TIME 游标遇已见条目止。 取数失败抛
 * {@code FeedFetchException}（基础设施层，含源定位信息），由摄取服务捕获走退避。
 */
public interface FeedFetcher {

    /** 单源一轮取数。 */
    FetchResult fetch(InfoSource source, FetchContext context);

    /** 便捷重载：单页取数（正常轮）。 */
    default List<RawFeedItem> fetchFirstPage(InfoSource source, String cursorValue) {
        return fetch(source, FetchContext.firstPage(cursorValue)).items();
    }
}
