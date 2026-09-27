package com.info.platform.application.policy;

import java.util.List;

/**
 * 政策列表页码模式视图（{@code GET /api/v1/policies?page=}，M9 T60 模式沿用）。
 *
 * <p>V2.3-M23 T201 数据面切换：policies 为 news 背书条目；{@code total} 为组合过滤精确总数；page/size 如实回显请求值 （越界页 200 +
 * 空列表 + 真实 total，ADR-0035）；basis 版本化口径串（policy-scope-v1，§4.1 契约）。
 */
public record PolicyPagedView(
        List<PolicyView> policies, long total, int page, int size, String basis) {}
