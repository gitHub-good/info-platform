package com.info.platform.domain.analysis;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 行业周报实体（{@code industry_weekly_report} 表，M17 T145，V29）：UNIQUE(week_start) 每周一锚一行 UPSERT（FAILED
 * 重生成 / SUCCESS 幂等跳过——30085 契约由应用层把守）。周窗 = [week_start 周一 00:00 Asia/Shanghai, 生成时刻]。
 *
 * <p>{@code content} 为五区块 JSON 契约（summary / topRisers+topFallers 热度总览 / eventReview 事件回顾 / policyMoves
 * 政策动向 / nextWeekWatch 下周关注点 / trendJudgement 走向判断）；关键数字全部来自统计与规则层（AI 只写叙述，幻觉防线）； {@code
 * heat_top} 为生成时点周窗热度留存 JSON；{@code basis} 为 trend-v1 + heat + cost 口径串。
 */
public class IndustryWeeklyReport {

    /** week_start 线格式（Asia/Shanghai yyyy-MM-dd，周一锚点）。 */
    static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final Long id;
    private final String weekStart;
    private final ReportStatus status;
    private final String content;
    private final String heatTop;
    private final String errorMessage;
    private final String promptVersion;
    private final String basis;
    private final Instant createdAt;
    private final Instant updatedAt;

    private IndustryWeeklyReport(
            Long id,
            String weekStart,
            ReportStatus status,
            String content,
            String heatTop,
            String errorMessage,
            String promptVersion,
            String basis,
            Instant createdAt,
            Instant updatedAt) {
        if (weekStart == null || !DATE_PATTERN.matcher(weekStart).matches()) {
            throw new IllegalArgumentException("weekStart 须为 yyyy-MM-dd: " + weekStart);
        }
        LocalDate anchor;
        try {
            anchor = LocalDate.parse(weekStart);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("weekStart 须为 yyyy-MM-dd: " + weekStart, e);
        }
        if (anchor.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("weekStart 须为周一（周窗锚点）: " + weekStart);
        }
        this.id = id;
        this.weekStart = weekStart;
        this.status = Objects.requireNonNull(status, "status 必填");
        if (status == ReportStatus.SUCCESS) {
            Objects.requireNonNull(content, "SUCCESS 周报 content 必填");
        }
        this.content = content;
        this.heatTop = heatTop;
        this.errorMessage = errorMessage;
        this.promptVersion = promptVersion;
        this.basis = basis;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 新建成功周报（id/时间戳由仓储回填；errorMessage 可空 = 叙述正常，非空 = LLM 降级留痕）。 */
    public static IndustryWeeklyReport success(
            String weekStart,
            String content,
            String heatTop,
            String errorMessage,
            String promptVersion,
            String basis,
            Instant now) {
        return new IndustryWeeklyReport(
                null, weekStart, ReportStatus.SUCCESS, content, heatTop, errorMessage,
                promptVersion, basis, now, now);
    }

    /** 新建失败周报（聚合阶段失败留痕，retry 端点重生成）。 */
    public static IndustryWeeklyReport failed(String weekStart, String errorMessage, Instant now) {
        return new IndustryWeeklyReport(
                null, weekStart, ReportStatus.FAILED, null, null, errorMessage, null, null, now, now);
    }

    /** 从持久化数据重建（基础设施层回读）。 */
    public static IndustryWeeklyReport reconstruct(
            Long id,
            String weekStart,
            ReportStatus status,
            String content,
            String heatTop,
            String errorMessage,
            String promptVersion,
            String basis,
            Instant createdAt,
            Instant updatedAt) {
        return new IndustryWeeklyReport(
                id, weekStart, status, content, heatTop, errorMessage, promptVersion, basis,
                createdAt, updatedAt);
    }

    public Long getId() {
        return id;
    }

    public String getWeekStart() {
        return weekStart;
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
