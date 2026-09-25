package com.info.platform.domain.analysis;

/**
 * 行业日报状态（{@code industry_daily_report.status}，M15 T124，方案 §4.5）：SUCCESS（含 LLM 失败降级的纯统计版——
 * 日报当日可用性优先，narrative 降级在 content JSON 如实标注）/ FAILED（统计阶段失败，可手动 retry 重生成）。
 *
 * <p>领域层纯净枚举（仅 JDK），与持久化 TEXT 互转。
 */
public enum ReportStatus {
    /** 生成成功（content JSON 完整落库；LLM 叙述失败时为纯统计版，narrativeDegraded=true 如实标注）。 */
    SUCCESS,
    /** 生成失败（error_message 留痕；retry 端点重生成，UNIQUE(report_date) 替换）。 */
    FAILED;

    /** 持久化文本 → 枚举（未知值抛 {@code IllegalArgumentException}——存量损坏 fail-fast）。 */
    public static ReportStatus fromName(String name) {
        for (ReportStatus status : values()) {
            if (status.name().equalsIgnoreCase(name)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知 reportStatus: " + name);
    }
}
