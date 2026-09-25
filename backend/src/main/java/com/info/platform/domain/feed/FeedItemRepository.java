package com.info.platform.domain.feed;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 资讯统一库仓储端口（{@code news_item}，M13 T104，ADR-0039）。
 *
 * <p>写路径为批量 {@code INSERT OR IGNORE}（指纹全局唯一 + 源内 external_id 唯一双保险，调度重入/补抓重拉/并发同稿均收敛）；
 * 读路径默认排除软删源条目（join info_source）。
 */
public interface FeedItemRepository {

    /**
     * 批量幂等落库（INSERT OR IGNORE，两唯一索引同时兜底）。
     *
     * @return 实插行数（调用方以「应插数 − 实插数」得 dup_count）
     */
    int insertIgnoreBatch(List<FeedItem> items);

    /**
     * newest-first 游标分页（id DESC，{@code id < beforeId} 续取）。
     *
     * @param sourceId 源过滤（null = 全部源）；默认排除软删源条目
     * @param beforeId 游标（null/0 = 首页）
     */
    List<FeedItem> findLatest(Long sourceId, Long beforeId, int limit);

    /** 页码模式（同序同过滤，LIMIT/OFFSET）。 */
    List<FeedItem> findPage(Long sourceId, int page, int size);

    /** 页码模式精确计数（与 {@link #findPage} 同过滤口径）。 */
    long countByFilter(Long sourceId);

    /**
     * 感知延迟样本（fetched_at − published_at 毫秒，负值截 0——源侧时钟超前不产生负口径），stats 端点 P50/P90 现算（§4.8）。
     *
     * @param sinceISO 窗口起点（ISO-8601 Instant 文本，含；按 created_at 过滤）
     */
    List<Long> fetchLatencyMillisSince(String sinceISO);

    /**
     * 感知延迟样本（带源维度与入库时刻，M14 T114 大盘「仅增量轮」口径用）：应用层按「排除每源首日回灌 + 排除日粒度源」过滤后现算 P50/P90。
     *
     * @param sinceISO 窗口起点（ISO-8601 Instant 文本，含；按 created_at 过滤）
     */
    List<LatencySample> fetchLatencySamplesSince(String sinceISO);

    /**
     * 各源首次入库时刻（MIN(created_at) GROUP BY source_id）：「排除每源首日」简化口径的首日判定基准（REQ
     * 风险表授权的回灌排除实现，ADR-0045）。无条目的源不出现在结果中。
     */
    Map<Long, Instant> findFirstIngestAt();

    /** 各源累计入库条数（不分软删源——大盘源维度表的「累计条数」列对归档源同样如实展示）。 */
    Map<Long, Long> countGroupedBySource();

    /** 单条感知延迟样本（T114）：源维度 + 入库时刻（首日过滤用）+ 延迟毫秒（负值已截 0）。 */
    record LatencySample(long sourceId, Instant ingestedAt, long latencyMillis) {}
}
