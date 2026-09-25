package com.info.platform.domain.feed;

import java.util.List;

/**
 * 抓取统计日 rollup 仓储端口（{@code source_daily_stats}，M13 T100，方案 §3.5）。
 *
 * <p>唯一写入面是 {@link #increment}（每轮原子自增当日行，UNIQUE(source_id, stat_date) 幂等锚点）； 读取面供大盘数据面（T105 stats
 * 端点）按日窗聚合。
 */
public interface SourceDailyStatsRepository {

    /**
     * 当日行原子自增（UPSERT：INSERT OR IGNORE + UPDATE x = x + delta 同事务语义）。
     *
     * @param statDate yyyy-MM-dd（Asia/Shanghai）
     */
    void increment(
            long sourceId,
            String statDate,
            long pollDelta,
            long failDelta,
            long newDelta,
            long dupDelta);

    /** 起始日（含）之后的全部源逐日 rollup（大盘 stats 端点用，按 source_id/stat_date 升序）。 */
    List<SourceDailyStats> findSince(String fromDate);
}
