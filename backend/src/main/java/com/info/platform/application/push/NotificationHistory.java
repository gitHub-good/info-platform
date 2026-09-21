package com.info.platform.application.push;

import java.util.List;

/**
 * 推送历史分页结果（history 游标分页），应用层 DTO。
 *
 * @param items 当前页推送记录视图
 * @param nextCursor 下一页游标（最后一条 id）；当前页不足 {@code pageSize} 表示已到末页，返回 null
 */
public record NotificationHistory(List<NotificationView> items, Long nextCursor) {}
