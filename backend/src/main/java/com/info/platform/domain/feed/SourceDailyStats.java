package com.info.platform.domain.feed;

import java.time.Instant;

/**
 * 抓取统计日 rollup 值对象（{@code source_daily_stats} 表，M13 T100，方案 §3.5）。
 *
 * <p>每轮抓取结束原子自增当日行（UNIQUE(source_id, stat_date) 幂等锚点）；成功率/抓取量/去重率等大盘口径直接由本表出。
 *
 * @param statDate yyyy-MM-dd（Asia/Shanghai，运营心智本地日）
 */
public record SourceDailyStats(
        Long id,
        long sourceId,
        String statDate,
        long pollCount,
        long failCount,
        long newCount,
        long dupCount,
        Instant createdAt,
        Instant updatedAt) {}
