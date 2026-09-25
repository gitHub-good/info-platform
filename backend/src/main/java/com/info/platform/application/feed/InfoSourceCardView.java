package com.info.platform.application.feed;

import com.info.platform.domain.feed.InfoSource;

/**
 * 源管理页卡片视图（M13 T105，方案 §4.5 GET /info-sources）：源静态配置 + 今日计数 + 轮询运行态摘要， 供卡片五态徽章矩阵、指标行与归档区渲染。
 *
 * @param adapterType 线格式（rss / json_api / preset / html_template）
 */
public record InfoSourceCardView(
        Long id,
        String sourceCode,
        String name,
        String category,
        String adapterType,
        String adapterRef,
        String endpoint,
        SourceConfigView config,
        int intervalMinutes,
        boolean enabled,
        boolean preset,
        boolean deleted,
        TodayCountersView today,
        PollStateSummaryView state,
        String createdAt,
        String updatedAt) {

    /** 今日计数（source_daily_stats 当日行，无行全 0）。 */
    public record TodayCountersView(long pollCount, long failCount, long newCount, long dupCount) {

        /** 无当日行的空计数。 */
        public static final TodayCountersView EMPTY = new TodayCountersView(0, 0, 0, 0);
    }

    /** 轮询运行态摘要（新源无状态行时各时刻为 null、失败 0）。 */
    public record PollStateSummaryView(
            String lastAttemptAt,
            String lastSuccessAt,
            String nextDueAt,
            String cursorValue,
            int consecutiveFailures,
            String backoffUntil,
            Long lastDurationMillis,
            String lastRoundDetail,
            String lastError) {

        /** 无状态行的空摘要。 */
        public static final PollStateSummaryView EMPTY =
                new PollStateSummaryView(null, null, null, null, 0, null, null, null, null);
    }

    /** 领域对象 + 计数 + 运行态 → 卡片视图。 */
    public static InfoSourceCardView of(
            InfoSource source, TodayCountersView today, PollStateSummaryView state) {
        return new InfoSourceCardView(
                source.getId(),
                source.getSourceCode(),
                source.getName(),
                source.getCategory(),
                source.getAdapterType().wireCode(),
                source.getAdapterRef(),
                source.getEndpoint(),
                SourceConfigView.from(source.getConfig()),
                source.getIntervalMinutes(),
                source.isEnabled(),
                source.isPreset(),
                source.isDeleted(),
                today,
                state,
                source.getCreatedAt() == null ? null : source.getCreatedAt().toString(),
                source.getUpdatedAt() == null ? null : source.getUpdatedAt().toString());
    }
}
