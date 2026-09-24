package com.info.platform.application.common;

import java.util.List;

/**
 * Job 执行日志页码模式视图（{@code GET /api/v1/job-logs?page=}，M9 T61）。
 *
 * <p>{@code items} 列表字段与游标模式 {@link JobLogPage} 逐字段一致（两 record 各自闭合，不与 nextCursor 混装）； {@code
 * total} 为组合过滤精确总数；{@code page/size} 如实回显请求值（越界页 200 + 空列表 + 真实 total）。
 */
public record JobLogPagedView(List<JobLogView> items, long total, int page, int size) {}
