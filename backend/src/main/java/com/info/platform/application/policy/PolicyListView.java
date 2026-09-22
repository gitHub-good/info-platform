package com.info.platform.application.policy;

import java.util.List;

/**
 * 政策列表分页视图（GET /api/v1/policies）。
 *
 * <p>policies 为当前页条目；nextCursor 为下一页游标（上一页末条 id），null 表示无下一页（首页 cursor=null/0）。
 */
public record PolicyListView(List<PolicyView> policies, Long nextCursor) {}
