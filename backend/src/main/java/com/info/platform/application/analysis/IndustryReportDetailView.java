package com.info.platform.application.analysis;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 行业日报详情视图（M15 T124，方案 §4.8 {@code GET /api/v1/industry-reports/{reportDate}}）：content / heatTop 为
 * 解析后的 JSON 文档全量（前端详情回看 + 对账数据面）。
 *
 * @param content 日报内容
 *     JSON（summary/topIndustries/industryCounts/containerCounts/totalNews/totalEvents/events/
 *     watchPoints/disclaimer/narrativeDegraded）
 * @param heatTop 生成时点热度快照留存 JSON（31 行 H24 榜）
 */
public record IndustryReportDetailView(
        long id,
        String reportDate,
        String status,
        JsonNode content,
        JsonNode heatTop,
        String errorMessage,
        String promptVersion,
        String basis,
        String createdAt,
        String updatedAt) {}
