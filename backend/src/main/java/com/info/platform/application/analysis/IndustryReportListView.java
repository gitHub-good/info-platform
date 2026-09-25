package com.info.platform.application.analysis;

import java.util.List;

/**
 * 行业日报列表视图（M15 T124，方案 §4.8 {@code GET /api/v1/industry-reports}）：beforeId 游标 + limit（缺省 10），
 * report_date DESC；行含 status/摘要（前端日报 Tab 列表回看 + 失败重试入口数据面）。
 *
 * @param reports 日报行（新在前）
 * @param nextBeforeId 续页游标（null = 无更多页）
 */
public record IndustryReportListView(List<ItemView> reports, Long nextBeforeId) {

    /** 单行摘要视图（列表页卡片数据面）。 */
    public record ItemView(
            long id,
            String reportDate,
            String status,
            String summary,
            long totalNews,
            long totalEvents,
            boolean narrativeDegraded,
            String createdAt,
            String updatedAt) {}
}
