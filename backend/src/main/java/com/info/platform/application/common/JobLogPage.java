package com.info.platform.application.common;

import java.util.List;

/**
 * Job 执行日志分页结果（游标分页），应用层 DTO。
 *
 * @param items 当前页日志视图
 * @param nextCursor 下一页游标（当前页末条 id）；页未满 {@code pageSize} 表示已到末页，返回 null
 */
public record JobLogPage(List<JobLogView> items, Long nextCursor) {}
