package com.info.platform.domain.feed;

import com.info.platform.domain.analysis.L0Result;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 资讯统一库仓储端口（{@code news_item}，M13 T104，ADR-0039；T160 增资讯库读模型）。
 *
 * <p>写路径为批量 {@code INSERT OR IGNORE}（指纹全局唯一 + 源内 external_id 唯一双保险，调度重入/补抓重拉/并发同稿均收敛）；
 * 读路径默认排除软删源条目（join info_source）。T160（M19 V2.1，REQ-20260926-16 拍板一）读路径升级为<b>资讯库读模型</b>： news_item
 * 为主锚 LEFT JOIN news_analysis（1:1，UNIQUE(news_id)）——无 analysis 行的滞留条目（保留期清理错位等）按 PASS 兜底返回、分类为
 * null（「未分类」灰态由前端呈现）。
 */
public interface FeedItemRepository {

    /**
     * 批量幂等落库（INSERT OR IGNORE，两唯一索引同时兜底）。
     *
     * @return 实插行数（调用方以「应插数 − 实插数」得 dup_count）
     */
    int insertIgnoreBatch(List<FeedItem> items);

    /**
     * newest-first 游标分页（id DESC，{@code id < beforeId} 续取）；带 analysis join 字段（T160 起增量追加，既有消费方零破坏）。
     *
     * @param sourceId 源过滤（null = 全部源）；默认排除软删源条目
     * @param beforeId 游标（null/0 = 首页）
     */
    List<LibraryRow> findLatest(Long sourceId, Long beforeId, int limit);

    /**
     * 资讯库页码模式（同序，LIMIT/OFFSET）：sourceId + q 关键词（title/summary LIKE，实现层转义） + l0 状态 + l1 主分类 任意组合、全
     * AND 语义。
     */
    List<LibraryRow> findPage(LibraryFilter filter, int page, int size);

    /** 页码模式精确计数（与 {@link #findPage} 同过滤口径——页数据与计数单点组装）。 */
    long countByFilter(LibraryFilter filter);

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

    /**
     * 资讯库组合过滤条件（T160 页码模式，纯 JDK record）：sourceId + q 关键词 + l0 状态 + l1 主分类任意组合、全 AND 语义； {@link
     * #findPage}/{@link #countByFilter} 同一 filter 保证页数据与计数同口径。
     *
     * @param sourceId 源过滤；null = 全部源
     * @param keyword 标题/摘要关键词；null = 不过滤（接口层已校验长度 2~64 并 trim；LIKE 转义见实现层）
     * @param l0 L0 状态过滤；null = 不过滤（API 层 l0=ALL 或游标路径）；PASS 含「无 analysis 行」兜底条目
     * @param mainCategory 主分类过滤（35 枚举，接口层已校验）；null = 不过滤；l1_status≠DONE 条目自然不含
     */
    record LibraryFilter(
            Long sourceId,
            String keyword,
            L0Result l0,
            String mainCategory,
            String publishedFrom,
            String publishedTo) {}

    /** publishedFrom/To：发布时间窗（ISO yyyy-MM-dd，上海日界含端点；null 不过滤）。 */

    /**
     * 资讯库条目行（T160 读模型投影）：news_item 条目 + news_analysis 归类产物 + 近重复主条引用（na.near_dup_of → 主条
     * news_item.url 直查，主条被清理时 url 为 null——前端隐藏「主条」链接）。
     *
     * @param item news_item 条目本体
     * @param l0Result L0 状态（无 analysis 行由实现层兜底 PASS）
     * @param l0Detail 诊断（NOISE 命中规则名 / NEAR_DUP 海明距离+编辑距离）
     * @param mainCategory 主分类（仅 L1 DONE 有值；null = 未分类）
     * @param confidence L1 置信度 0~1
     * @param lowConfidence 是否低置信兜底（confidence&lt;floor 或模型输出非法枚举，已兜底「市场·其他」）
     * @param nearDupMasterId 近重复主条 news_id（na.near_dup_of；非 NEAR_DUP 为 null）
     * @param nearDupMasterUrl 主条原文 url（主条已清理为 null）
     */
    record LibraryRow(
            FeedItem item,
            L0Result l0Result,
            String l0Detail,
            String mainCategory,
            Double confidence,
            boolean lowConfidence,
            Long nearDupMasterId,
            String nearDupMasterUrl) {}
}
