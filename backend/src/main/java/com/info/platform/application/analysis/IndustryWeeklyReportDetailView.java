package com.info.platform.application.analysis;

import com.fasterxml.jackson.databind.JsonNode;

/** 周报详情视图（M17 T145，{@code GET /api/v1/industry-reports/weekly/{weekStart}} 契约：content/heatTop JSON 全量）。 */
public record IndustryWeeklyReportDetailView(
        long id,
        String weekStart,
        String status,
        JsonNode content,
        JsonNode heatTop,
        String errorMessage,
        String promptVersion,
        String basis,
        String createdAt,
        String updatedAt) {}
