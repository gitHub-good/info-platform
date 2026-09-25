package com.info.platform.domain.feed;

import java.util.List;

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
}
