package com.info.platform.application.policy;

import java.util.List;

/**
 * 政策列表分页视图（游标模式，{@code GET /api/v1/policies} 无 page 参数路径）。
 *
 * <p>V2.3-M23 T201 数据面换血：policies 为 news 背书条目；nextCursor = 末条 news id（翻页游标语义不变）； basis
 * 版本化口径串随响应返回（policy-scope-v1，追加式非破坏）。
 */
public record PolicyListView(List<PolicyView> policies, Long nextCursor, String basis) {}
