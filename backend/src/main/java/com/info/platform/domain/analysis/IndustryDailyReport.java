package com.info.platform.domain.analysis;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 行业日报实体（{@code industry_daily_report} 表，M15 T124，方案 §4.5）：UNIQUE(report_date) 每日一行 UPSERT（FAILED
 * 重生成 / SUCCESS 幂等跳过——手动 retry 已 SUCCESS 走 30077 契约由应用层把守）。
 *
 * <p>关键数字全部来自统计 SQL（AI 只写叙述，幻觉防线）；{@code content} 为 JSON 契约（summary / topIndustries /
 * industryCounts / containerCounts / totalNews / totalEvents / events / watchPoints / disclaimer /
 * narrativeDegraded）； {@code heatTop} 为生成时点热度快照留存 JSON（对账与趋势，不随快照滚动丢失）；{@code basis} 为 heat + cost
 * 双口径串。
 */
public class IndustryDailyReport {

    /** report_date 线格式（Asia/Shanghai yyyy-MM-dd，运营心智本地日）。 */
    static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final Long id;
    private final String reportDate;
    private final ReportStatus status;
    private final String content;
    private final String heatTop;
    private final String errorMessage;
    private final String promptVersion;
    private final String basis;
    private final Instant createdAt;
    private final Instant updatedAt;

    private IndustryDailyReport(
            Long id,
            String reportDate,
            ReportStatus status,
            String content,
            String heatTop,
            String errorMessage,
            String promptVersion,
            String basis,
            Instant createdAt,
            Instant updatedAt) {
        if (reportDate == null || !DATE_PATTERN.matcher(reportDate).matches()) {
            throw new IllegalArgumentException("reportDate 须为 yyyy-MM-dd: " + reportDate);
        }
        this.id = id;
        this.reportDate = reportDate;
        this.status = Objects.requireNonNull(status, "status 必填");
        if (status == ReportStatus.SUCCESS) {
            Objects.requireNonNull(content, "SUCCESS 日报 content 必填");
        }
        this.content = content;
        this.heatTop = heatTop;
        this.errorMessage = errorMessage;
        this.promptVersion = promptVersion;
        this.basis = basis;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 新建成功日报（id/时间戳由仓储回填；errorMessage 可空 = 叙述正常，非空 = LLM 降级留痕）。 */
    public static IndustryDailyReport success(
            String reportDate,
            String content,
            String heatTop,
            String errorMessage,
            String promptVersion,
            String basis,
            Instant now) {
        return new IndustryDailyReport(
                null,
                reportDate,
                ReportStatus.SUCCESS,
                content,
                heatTop,
                errorMessage,
                promptVersion,
                basis,
                now,
                now);
    }

    /** 新建失败日报（统计阶段失败留痕，retry 端点重生成）。 */
    public static IndustryDailyReport failed(String reportDate, String errorMessage, Instant now) {
        return new IndustryDailyReport(
                null,
                reportDate,
                ReportStatus.FAILED,
                null,
                null,
                errorMessage,
                null,
                null,
                now,
                now);
    }

    /** 从持久化数据重建（基础设施层回读）。 */
    public static IndustryDailyReport reconstruct(
            Long id,
            String reportDate,
            ReportStatus status,
            String content,
            String heatTop,
            String errorMessage,
            String promptVersion,
            String basis,
            Instant createdAt,
            Instant updatedAt) {
        return new IndustryDailyReport(
                id,
                reportDate,
                status,
                content,
                heatTop,
                errorMessage,
                promptVersion,
                basis,
                createdAt,
                updatedAt);
    }

    public Long getId() {
        return id;
    }

    public String getReportDate() {
        return reportDate;
    }

    public ReportStatus getStatus() {
        return status;
    }

    public String getContent() {
        return content;
    }

    public String getHeatTop() {
        return heatTop;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public String getBasis() {
        return basis;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
