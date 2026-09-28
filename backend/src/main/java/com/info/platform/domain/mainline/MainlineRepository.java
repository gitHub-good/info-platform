package com.info.platform.domain.mainline;

import java.util.List;
import java.util.Optional;

/**
 * 主线榜单两表仓储端口（{@code industry_mainline} / {@code industry_mainline_batch} + 输入投影，M27 T243/T244，V35
 * 表②③）： (rank_date, version) 追加式版本化——同日重算/手动 version+1 不覆盖；读取走「该日期最大 version」（market_top 先例）。
 *
 * <p>领域层纯净接口（JdbcTemplate 实现在 infrastructure.mainline）：dim_detail/leaders/funnel_stats 等 JSON
 * 列以文本透传 （组装/解析在应用层）；输入投影（日报 heat_top / 事件密度）为 mainline 域读优化视图。
 */
public interface MainlineRepository {

    /** 同日已有最大 version（无任何版本返回 0——新版本从 1 起）。 */
    int maxVersion(String rankDate);

    /**
     * 追加一版本（batch 1 行 + rank N 行同一事务；UNIQUE(rank_date, version) 冲突由调用方 version 递增语义避免）。
     *
     * @return 实插 rank 行数
     */
    int insertVersion(MainlineBatchRow batch, List<MainlineRankRow> ranks);

    /** 指定 (rankDate, version) 榜单（items 按 rank_no 升序）；不存在返回 empty。 */
    Optional<MainlineVersion> find(String rankDate, int version);

    /** 指定日期最大 version 榜单；该日无任何版本返回 empty。 */
    Optional<MainlineVersion> findLatest(String rankDate);

    /** 全库最新有榜单日的最大 version（缺省「最新榜单」读取面）；从未生成过返回 empty——30094 语义。 */
    Optional<MainlineVersion> findLatestAnyDate();

    /** 近 N 个有榜日（降序；date 缺省回退读面）。 */
    List<String> listRankDates(int limit);

    /**
     * 近 N 日日报 heat_top 留存行（report_date 降序；industry_daily_report 每日 31 行全量留存——热度侧持续性历史）。
     *
     * @param days 回看自然日窗
     */
    List<HeatTopDay> findRecentHeatTop(int days);

    /**
     * 事件密度投影：[fromDate, toDate] 窗内按行业的事件加权和（{@code json_each(affected_industries)} 命中且 importance
     * 加权 HIGH×2 / MEDIUM×1 / LOW×0——SQL 可复算对账面，方案 §4.3.2）。
     */
    List<EventWeightRow> sumEventWeightByIndustry(String fromDate, String toDate);

    /** 日报 heat_top 单日行（report_date + JSON 透传——解析归应用层）。 */
    record HeatTopDay(String reportDate, String heatTopJson) {}

    /** 行业事件加权和行。 */
    record EventWeightRow(String industry, double weightedCount) {}

    /** rank 行（写载荷——两表列直映射）。 */
    record MainlineRankRow(
            String rankDate,
            int version,
            int rankNo,
            String industry,
            double mainScore,
            String dimDetailJson,
            int persistentDays,
            Integer heatRank,
            String divergence,
            String leadersJson,
            String basis,
            String computedAt) {}

    /** batch 行（写载荷——漏斗计数/降级态留痕）。 */
    record MainlineBatchRow(
            String rankDate,
            int version,
            String triggerSource,
            String snapshotDate,
            String funnelStatsJson,
            boolean degraded,
            String degradedReason,
            String basis,
            String createdAt) {}

    /** 一版本完整读取面（batch 字段 + rank 行）。 */
    record MainlineVersion(MainlineBatchRow batch, List<MainlineRankRow> items) {}
}
