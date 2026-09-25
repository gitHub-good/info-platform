package com.info.platform.application.feed;

import java.util.List;

/**
 * 抓取统计数据面视图（M13 T105，方案 §4.5 GET /info-sources/stats，页面 UI 归 M14）： 逐源逐日 rollup + 全局感知延迟
 * P50/P90（news_item 现算，§4.8 口径）。
 *
 * @param days 统计窗口天数（1~30）
 * @param fromDate 窗口起始日 yyyy-MM-dd（Asia/Shanghai，含）
 * @param sources 各源摘要（今日计数 + 运行态退避面）
 * @param daily 逐源逐日 rollup（窗口内，按 sourceId/statDate 升序）
 * @param latency 全局感知延迟（fetched_at − published_at；无样本时 p50/p90 为 null）
 */
public record SourceStatsView(
        int days,
        String fromDate,
        List<SourceStatsSummary> sources,
        List<DailyRollup> daily,
        Latency latency) {

    /** 源级摘要。 */
    public record SourceStatsSummary(
            long sourceId,
            String sourceCode,
            String name,
            InfoSourceCardView.TodayCountersView today,
            InfoSourceCardView.PollStateSummaryView state) {}

    /** 单源单日 rollup。 */
    public record DailyRollup(
            long sourceId,
            String sourceCode,
            String statDate,
            long pollCount,
            long failCount,
            long newCount,
            long dupCount) {}

    /** 感知延迟分布（毫秒；负样本截 0——源侧时钟超前不产生负延迟口径）。 */
    public record Latency(Long p50Millis, Long p90Millis, long sampleCount) {}
}
