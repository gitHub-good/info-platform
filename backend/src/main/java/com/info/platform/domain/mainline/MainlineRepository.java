package com.info.platform.domain.mainline;

import com.info.platform.domain.aggregation.Market;
import java.util.List;
import java.util.Optional;

/**
 * 主线榜单两表仓储端口（{@code industry_mainline} / {@code industry_mainline_batch} + 输入投影，M27 T243/T244，V35
 * 表②③；V37 起唯一键含 market 维——M29 T255 分市场版本共存）： (rank_date, version, market) 追加式版本化——同日重算/手动 version+1
 * 不覆盖；读取走「该日期 + 该市场最大 version」（market_top 先例）。
 *
 * <p>领域层纯净接口（JdbcTemplate 实现在 infrastructure.mainline）：dim_detail/leaders/funnel_stats 等 JSON
 * 列以文本透传 （组装/解析在应用层）；输入投影（日报 heat_top / 事件密度）为 mainline 域读优化视图。
 */
public interface MainlineRepository {

    /** 同日同市场已有最大 version（无任何版本返回 0——新版本从 1 起）。 */
    int maxVersion(String rankDate, Market market);

    /**
     * 追加一版本（batch 1 行 + rank N 行同一事务；UNIQUE(rank_date, version, market) 冲突由调用方 version 递增语义避免）。
     *
     * @return 实插 rank 行数
     */
    int insertVersion(MainlineBatchRow batch, List<MainlineRankRow> ranks);

    /** 指定 (rankDate, version, market) 榜单（items 按 rank_no 升序）；不存在返回 empty。 */
    Optional<MainlineVersion> find(String rankDate, int version, Market market);

    /** 指定日期 + 市场最大 version 榜单；该日无任何版本返回 empty。 */
    Optional<MainlineVersion> findLatest(String rankDate, Market market);

    /** 指定市场全库最新有榜单日的最大 version（缺省「最新榜单」读取面）；从未生成过返回 empty——30094 语义。 */
    Optional<MainlineVersion> findLatestAnyDate(Market market);

    /** 指定市场近 N 个有榜日（降序；date 缺省回退读面）。 */
    List<String> listRankDates(int limit, Market market);

    /**
     * 近 N 日日报 heat_top 留存行（report_date 降序；industry_daily_report 每日 31 行全量留存——热度侧持续性历史。<b>A 股
     * 专用投影</b>：该表无 market 维（日报 v1 恒 A 股口径，M29 §五「日报/周报 A 股零回归」），港美股热度历史走 bootstrap）。
     *
     * @param days 回看自然日窗
     */
    List<HeatTopDay> findRecentHeatTop(int days);

    /**
     * 事件密度投影：[fromDate, toDate] 窗内按行业的事件加权和（{@code json_each(affected_industries)} 命中且源条目 {@code
     * l1_market = market}——跨市场重名行业消歧；importance 加权 HIGH×2 / MEDIUM×1 / LOW×0——SQL 可复算对账面，方案 §4.3.2
     * / §6.1）。
     */
    List<EventWeightRow> sumEventWeightByIndustry(String fromDate, String toDate, Market market);

    // ---- 龙头识别输入投影（M27 T244，方案 §4.4——成员集/提及/事件关联/价值评分/行情两窗） ----

    /** 活跃 A 股成员投影（industry 为板块原文，SW 映射在读侧 swPrimaryOf——M(I) 成员集原料）。 */
    List<MemberRow> findActiveMembers();

    /**
     * 资讯提及计数（近窗，L1 归类到本行业且回联该标的——§4.4.2 对账 SQL 的批量化形态：json_each 展开计数，与 EXISTS 单标的口径等值）。
     *
     * @param industry 申万行业（main_category）
     * @param fromIso news_item.created_at 下界（含，ISO-8601）
     * @param toIso 上界（不含）
     */
    List<MentionCountRow> countMentionsByIndustry(String industry, String fromIso, String toIso);

    /**
     * 标的事件关联行（该行业事件 ∩ subjects 回联，近窗——A 维事件加权 + riskEvents + 依据 eventIds 原料）。
     *
     * @param fromDate event_date 下界（含，yyyy-MM-dd）
     */
    List<SubjectEventLinkRow> findSubjectEventLinks(
            String industry, String fromDate, String toDate);

    /** 最新因子快照日（subject_factor_snapshot——V 维原料锚；无任何快照返回 empty）。 */
    Optional<String> latestFactorSnapshotDate();

    /** 指定快照日的因子评分行（V 维原料——total_score + data_flags JSON 透传）。 */
    List<FactorScoreRow> findFactorScores(String snapshotDate);

    /** 指定日期集的行情涨跌幅行（Q 维两窗原料——market_daily_snapshot.pct_change）。 */
    List<MarketQuoteRow> findMarketPctChangeForDates(List<String> snapshotDates);

    /** 近 N 个 distinct 行情快照日（降序——Q 维两窗交易日集，market_daily_snapshot 口径）。 */
    List<String> recentMarketQuoteDates(int limit);

    /** 活跃成员行（subject_master：code/name + industry 板块原文）。 */
    record MemberRow(long subjectId, String code, String name, String industry) {}

    /** 单标的提及计数行。 */
    record MentionCountRow(String code, int mentions) {}

    /** 标的事件关联行（direction/importance 为枚举原文；eventId 供依据回溯）。 */
    record SubjectEventLinkRow(String code, long eventId, String importance, String direction) {}

    /** 因子评分行（dataFlagsJson 透传）。 */
    record FactorScoreRow(long subjectId, double totalScore, String dataFlagsJson) {}

    /** 行情涨跌幅行（snapshot_date yyyy-MM-dd + pct_change %）。 */
    record MarketQuoteRow(String code, String snapshotDate, Double pctChange) {}

    /** 日报 heat_top 单日行（report_date + JSON 透传——解析归应用层）。 */
    record HeatTopDay(String reportDate, String heatTopJson) {}

    /** 行业事件加权和行。 */
    record EventWeightRow(String industry, double weightedCount) {}

    /** rank 行（写载荷——两表列直映射；market V37 起唯一键组成）。 */
    record MainlineRankRow(
            Market market,
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
            String computedAt) {

        /** 兼容构造（M27 既有 A 股调用面：market 恒 'A_SHARE'——存量测试与 A 股路径行为不变）。 */
        public MainlineRankRow(
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
                String computedAt) {
            this(
                    Market.A_SHARE,
                    rankDate,
                    version,
                    rankNo,
                    industry,
                    mainScore,
                    dimDetailJson,
                    persistentDays,
                    heatRank,
                    divergence,
                    leadersJson,
                    basis,
                    computedAt);
        }
    }

    /** batch 行（写载荷——漏斗计数/降级态留痕；market V37 起唯一键组成）。 */
    record MainlineBatchRow(
            Market market,
            String rankDate,
            int version,
            String triggerSource,
            String snapshotDate,
            String funnelStatsJson,
            boolean degraded,
            String degradedReason,
            String basis,
            String createdAt) {

        /** 兼容构造（M27 既有 A 股调用面：market 恒 'A_SHARE'）。 */
        public MainlineBatchRow(
                String rankDate,
                int version,
                String triggerSource,
                String snapshotDate,
                String funnelStatsJson,
                boolean degraded,
                String degradedReason,
                String basis,
                String createdAt) {
            this(
                    Market.A_SHARE,
                    rankDate,
                    version,
                    triggerSource,
                    snapshotDate,
                    funnelStatsJson,
                    degraded,
                    degradedReason,
                    basis,
                    createdAt);
        }
    }

    /** 一版本完整读取面（batch 字段 + rank 行）。 */
    record MainlineVersion(MainlineBatchRow batch, List<MainlineRankRow> items) {}
}
