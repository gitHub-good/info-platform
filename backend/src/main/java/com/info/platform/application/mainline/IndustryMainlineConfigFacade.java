package com.info.platform.application.mainline;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 主线/龙头配置读写端口（M27 T243，方案 §4.5）：GET/PATCH {@code /api/v1/industry-mainline/config} 的应用层门面（任务中心
 * INDUSTRY_MAINLINE 编辑 Dialog 数据源，MarketTopConfigFacade 同款先例）。
 *
 * <p>端口在应用层、实现在基础设施层（{@code infrastructure.mainline.IndustryMainlineConfigFacadeImpl}）：实现需读写
 * runtime_config。读侧走 {@link IndustryMainlineSettings}（字段级回退防御）；写侧两键全量替换委托 {@code
 * RuntimeConfigService.write}（校验 30096 + expectedUpdatedAt 30065 + 换快照热生效）。
 */
public interface IndustryMainlineConfigFacade {

    /** 两键配置视图（GET：mainline 13 字段 + leader 7 字段 + 各自 updatedAt 防呆比对；键缺失 = 代码缺省 + null）。 */
    ConfigView view();

    /**
     * 两键全量替换（PATCH 语义：每键字段整体替换，缺失/null → 校验器 30096「必填」拦截，不部分写）。
     *
     * @throws com.info.platform.domain.common.BusinessException 30096 字段级校验失败（原值保留）；30065 并发冲突；
     *     30065/2001 expectedUpdatedAt 非法；50000 写库失败（快照不动，旧值继续生效）
     */
    ConfigView update(ConfigUpdate update);

    /** GET/PATCH 共享视图（字段名与两键文档一致）。 */
    record ConfigView(MainlineView mainline, LeaderView leader) {}

    /** {@code industry.mainline} 13 字段视图。 */
    record MainlineView(
            double wp,
            double wh,
            double we,
            double priceWinDay,
            double priceWinD5,
            double heatH24,
            double heatD7,
            double heatDelta,
            int topN,
            int persistMinDays,
            int persistWindowDays,
            int topThirdRank,
            int divergenceHeatRank,
            String updatedAt) {}

    /** {@code industry.leader} 7 字段视图。 */
    record LeaderView(
            double wa,
            double wv,
            double wq,
            int mentionDays,
            int topN,
            double qDay,
            double qD5,
            String updatedAt) {}

    /**
     * PATCH 请求体：两键字段全量 + 可选并发防呆。字段收 {@link JsonNode} 原样透传（ScoreWeightConfigFacade D3 先例）：类型判定
     * 单一事实源在 {@code IndustryMainlineConfigValidator}。
     */
    record ConfigUpdate(
            JsonNode wp,
            JsonNode wh,
            JsonNode we,
            JsonNode priceWinDay,
            JsonNode priceWinD5,
            JsonNode heatH24,
            JsonNode heatD7,
            JsonNode heatDelta,
            JsonNode topN,
            JsonNode persistMinDays,
            JsonNode persistWindowDays,
            JsonNode topThirdRank,
            JsonNode divergenceHeatRank,
            JsonNode wa,
            JsonNode wv,
            JsonNode wq,
            JsonNode mentionDays,
            JsonNode leaderTopN,
            JsonNode qDay,
            JsonNode qD5,
            String expectedUpdatedAt) {}
}
