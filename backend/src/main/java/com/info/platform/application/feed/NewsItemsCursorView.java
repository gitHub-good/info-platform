package com.info.platform.application.feed;

import java.util.List;

/** 资讯流游标模式视图（items + nextBeforeId；末页 nextBeforeId=null）。 */
public record NewsItemsCursorView(List<NewsItemView> items, Long nextBeforeId) {}
