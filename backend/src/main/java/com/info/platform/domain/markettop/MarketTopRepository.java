package com.info.platform.domain.markettop;

import com.info.platform.domain.markettop.RankDiffer.PrevSubject;
import java.util.List;
import java.util.Optional;

/**
 * Top10 榜单两表仓储端口（{@code market_top_rank} / {@code market_top_batch}，M21 T183，V31 表 + ADR-0059 裁决
 * 6）： (rank_date, version) 追加式版本化——同日重跑 version+1 不覆盖；读取走「该日期最大 version」。
 *
 * <p>领域层纯净接口（JdbcTemplate 实现在 infrastructure.markettop——批 1 IndustryMemberStore 同款分层裁量，ADR-0060
 * 对接说明）； dive_detail 等 JSON 列以文本透传（组装/解析在应用层，领域不依赖 JSON 库）。
 */
public interface MarketTopRepository {

    /** 同日已有最大 version（无任何版本返回 0——新版本从 1 起）。 */
    int maxVersion(String rankDate);

    /**
     * 追加一版本（batch 1 行 + rank N 行同一事务；UNIQUE(rank_date, version) 冲突由调用方 version 递增语义避免）。
     *
     * @return 实插 rank 行数
     */
    int insertVersion(MarketTopBatchRow batch, List<MarketTopRankRow> ranks);

    /** 指定 (rankDate, version) 榜单（items 按 rank_no 升序）；不存在返回 empty。 */
    Optional<MarketTopVersion> find(String rankDate, int version);

    /** 指定日期最大 version 榜单；该日无任何版本返回 empty。 */
    Optional<MarketTopVersion> findLatest(String rankDate);

    /** 全库最新有榜单日的最大 version（缺省「最新榜单」读取面）；从未生成过返回 empty。 */
    Optional<MarketTopVersion> findLatestAnyDate();

    /** 昨日榜单 top 行（rank_date &lt; 指定日、≤7 天窗内最近有榜单日、该日最大 version；rank_no 升序）——RankDiffer 原料；无返回空。 */
    List<PrevSubject> findPreviousTop(String rankDate);

    /** 历史版本列表（日期降序、版本降序；rankDate 非空时按日过滤；limit 上限护栏）。 */
    List<VersionSummary> listVersions(String rankDate, int limit);

    /**
     * 指定日最新 EVENT 版本摘要（M22 T192，页头「最近增量重评」§4.2-②：version/createdAt + trigger_events JSON 透传； 该日无
     * EVENT 版本返回 empty——解析归应用层）。
     */
    Optional<EventVersion> findLatestEventVersion(String rankDate);

    /**
     * 各榜单日最大 version 的 Top 行（M22 T193，hits-v1 回算原料——日终语义取最大 version；rank_date 降序、rank_no 升序确定性）。
     */
    List<RankedSubject> listTopByMaxVersion();

    /** EVENT 版本摘要（trigger_events JSON 以文本透传——领域不依赖 JSON 库）。 */
    record EventVersion(int version, String createdAtIso, String triggerEventsJson) {}

    /** 榜单日最大 version 的 Top 成员（命中统计回算元素）。 */
    record RankedSubject(String rankDate, int rankNo, long subjectId) {}

    /** rank 行（写载荷——两表列直映射）。 */
    record MarketTopRankRow(
            String rankDate,
            int version,
            int rankNo,
            long subjectId,
            String subjectCode,
            String subjectName,
            double totalScore,
            double finalScore,
            Double percentile,
            boolean breakthrough,
            String generation,
            String diveMethod,
            String diveSummary,
            String diveDetailJson,
            int evidenceCount,
            String lastEventDate,
            Integer prevRank,
            String changeType,
            String basis,
            String computedAt) {}

    /**
     * batch 行（写载荷——漏斗计数/降级态/跌出留痕/深析成本；createdAt 由落库时回填，读取面即 batch.computedAt）。
     *
     * @param triggerEvents EVENT 版本归因 JSON {@code [{eventId,summary,importance}]}（V33 列；DAILY 版本为
     *     null——M22 T191）
     */
    record MarketTopBatchRow(
            String rankDate,
            int version,
            String triggerSource,
            String snapshotDate,
            String funnelStatsJson,
            boolean degraded,
            String degradedReason,
            String droppedSubjectsJson,
            long diveCostMicros,
            int diveLlmCalls,
            String promptVersion,
            String basis,
            String triggerEventsJson,
            String createdAt) {}

    /** 一版本完整读取面（batch 字段 + rank 行）。 */
    record MarketTopVersion(MarketTopBatchRow batch, List<MarketTopRankRow> items) {}

    /** 版本摘要（/versions 列表项）。 */
    record VersionSummary(
            String rankDate,
            int version,
            String triggerSource,
            boolean degraded,
            String degradedReason,
            String snapshotDate,
            int topSize,
            String computedAt) {}
}
