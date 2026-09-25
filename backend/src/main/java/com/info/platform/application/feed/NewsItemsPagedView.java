package com.info.platform.application.feed;

import java.util.List;

/** 资讯流页码模式视图（M9 PageQuery 模式：items + total + page/size 回显）。 */
public record NewsItemsPagedView(List<NewsItemView> items, long total, int page, int size) {}
