package com.info.platform.application.feed;

import java.util.List;

/**
 * 抓取大盘视图（M14 T114，GET /api/v1/feed-dashboard）：三区块一端点——全局统计 / 源维度表 / 近期失败列表（REQ 故事 2）。
 *
 * <p>对账口径：{@code global.todayNewCount} = {@code sources[]} 各行 {@code todayNewCount} 之和（同
 * source_daily_stats 当日行，与源管理页 /info-sources 今日计数同表同数）。
 *
 * @param global 全局统计卡（今日入库/活跃源/失败源/感知延迟）
 * @param sources 源维度表行（异常置顶，其余今日新增降序；含停用与归档源行，前端默认折叠切换）
 * @param failures 近期失败列表（时间倒序，旁路事件 + 运行态 last_error 组装；T211 恢复过滤——仅当前 runState ∈ {fail, backoff}
 *     的源展示，已恢复/停用/归档/已删源零展示，事件表留痕零删除）
 * @param failuresHiddenRecovered 被恢复过滤隐藏的失败记录条数（旁路事件 + 现态合并口径；留痕在库可经 Job 日志/事件留痕面追溯）
 */
public record FeedDashboardView(
        GlobalView global,
        List<SourceRowView> sources,
        List<FailureView> failures,
        long failuresHiddenRecovered) {

    /** 感知延迟口径版本（ADR-0045）：仅增量轮 = 排除每源首日回灌 + 排除日粒度源（published_at 口径失真）。 */
    public static final String LATENCY_BASIS =
            "incremental-only-v1:exclude-first-day+daily-sources";

    /** 全局统计卡四指标 + 去重拦截并列展示（REQ 场景 1）。 */
    public record GlobalView(
            long todayNewCount,
            long todayDupCount,
            int activeSourceCount,
            int failedSourceCount,
            LatencyView latency) {}

    /**
     * 感知延迟分布（毫秒，最近邻秩法；无样本 p50/p90 为 null 与 0 可区分）。
     *
     * @param basis 口径版本串（前端明示「仅增量轮」防误读）
     * @param excludedSourceCodes 被口径排除的日粒度源代码（published_at 仅日粒度、感知延迟失真）
     */
    public record LatencyView(
            Long p50Millis,
            Long p90Millis,
            long sampleCount,
            String basis,
            List<String> excludedSourceCodes) {}

    /** 源维度表行。 */
    public record SourceRowView(
            long sourceId,
            String sourceCode,
            String name,
            String category,
            String adapterType,
            int intervalMinutes,
            boolean enabled,
            boolean preset,
            boolean deleted,
            String staleSince,
            long todayPollCount,
            long todayNewCount,
            long todayFailCount,
            long todayDupCount,
            long totalCount,
            String lastAttemptAt,
            String lastSuccessAt,
            String nextDueAt,
            String backoffUntil,
            int consecutiveFailures,
            String lastError,
            String lastRoundDetail,
            String runState,
            boolean abnormal) {}

    /** 近期失败列表行（origin：event=data_source_event 旁路事件 / state=运行态 last_error 现态）。 */
    public record FailureView(
            String sourceCode,
            String sourceName,
            String occurredAt,
            String errorSummary,
            String origin) {}
}
