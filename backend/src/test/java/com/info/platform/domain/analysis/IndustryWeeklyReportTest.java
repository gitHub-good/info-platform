package com.info.platform.domain.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 行业周报实体单测（M17 T145，V29 {@code industry_weekly_report}）：weekStart 须为周一（周窗锚点语义）、UNIQUE(week_start) 幂等
 * 语义由仓储把守、SUCCESS content 必填、FAILED 留痕重建。
 */
class IndustryWeeklyReportTest {

    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");

    /** 2026-09-21 为周一。 */
    private static final String MONDAY = "2026-09-21";

    @Test
    @DisplayName("成功周报：周一锚点 + 五区块 content + basis 口径串")
    void success_mondayAnchored() {
        IndustryWeeklyReport report =
                IndustryWeeklyReport.success(
                        MONDAY, "{\"summary\":\"..\"}", "[]", null, "v1.0", "trend-v1|heat-v1", NOW);

        assertThat(report.getWeekStart()).isEqualTo(MONDAY);
        assertThat(report.getStatus()).isEqualTo(ReportStatus.SUCCESS);
        assertThat(report.getContent()).isNotBlank();
        assertThat(report.getBasis()).contains("trend-v1");
    }

    @Test
    @DisplayName("非周一日期拒（周窗锚点唯一性把守）")
    void nonMonday_rejected() {
        assertThatThrownBy(
                        () ->
                                IndustryWeeklyReport.success(
                                        "2026-09-22", "{}", "[]", null, "v1.0", "b", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("周一");
        assertThatThrownBy(
                        () ->
                                IndustryWeeklyReport.failed(
                                        "2026/09/21", "error", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("SUCCESS content 必填；FAILED 留痕可重建")
    void contractGuards() {
        assertThatThrownBy(
                        () -> IndustryWeeklyReport.success(MONDAY, null, "[]", null, "v1.0", "b", NOW))
                .isInstanceOf(NullPointerException.class);

        IndustryWeeklyReport failed = IndustryWeeklyReport.failed(MONDAY, "统计失败", NOW);
        assertThat(failed.getStatus()).isEqualTo(ReportStatus.FAILED);
        assertThat(failed.getErrorMessage()).isEqualTo("统计失败");

        IndustryWeeklyReport rebuilt =
                IndustryWeeklyReport.reconstruct(
                        1L, MONDAY, ReportStatus.SUCCESS, "{}", "[]", null, "v1.0", "b", NOW, NOW);
        assertThat(rebuilt.getId()).isEqualTo(1L);
        assertThat(rebuilt.getWeekStart()).isEqualTo(MONDAY);
    }
}
