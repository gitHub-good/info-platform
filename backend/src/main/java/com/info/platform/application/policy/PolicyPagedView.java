package com.info.platform.application.policy;

import java.util.List;

/**
 * 政策列表页码模式视图（{@code GET /api/v1/policies?page=}，M9 T60）。
 *
 * <p>{@code policies} 列表字段与游标模式 {@link PolicyListView} 逐字段一致（两 record 各自闭合，不与 nextCursor 混装）；{@code
 * total} 为组合过滤精确总数；{@code page/size} 如实回显请求值（越界页 200 + 空列表 + 真实 total）。
 */
public record PolicyPagedView(List<PolicyView> policies, long total, int page, int size) {}
