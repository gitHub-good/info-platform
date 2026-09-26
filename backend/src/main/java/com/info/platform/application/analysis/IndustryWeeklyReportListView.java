package com.info.platform.application.analysis;

import java.util.List;

/** 周报列表视图（M17 T145，{@code GET /api/v1/industry-reports/weekly} 契约）。 */
public record IndustryWeeklyReportListView(List<ItemView> reports, Long nextBeforeId) {

    /** 列表行（周锚/状态/摘要/计数/降级标注）。 */
    public record ItemView(
            long id,
            String weekStart,
            String status,
            String summary,
            long totalNews,
            long totalEvents,
            boolean narrativeDegraded,
            String createdAt,
            String updatedAt) {}
}
