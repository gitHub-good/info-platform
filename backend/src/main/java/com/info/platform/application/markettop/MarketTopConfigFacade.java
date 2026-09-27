package com.info.platform.application.markettop;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 榜单配置读写端口（M21 T181，方案 §4.7.3）：GET/PATCH {@code /api/v1/market-top/config} 的应用层门面（任务中心
 * MARKET_TOP_JOB 编辑 Dialog 数据源，FACTOR_SNAPSHOT 权重 Dialog 同款先例）。
 *
 * <p>端口在应用层、实现在基础设施层（{@code
 * infrastructure.markettop.MarketTopConfigFacadeImpl}，ScoreWeightConfigFacade 同因——实现需读写
 * runtime_config）。读侧走 {@link MarketTopConfigSettings}（字段级回退防御）；写侧拼全量文档委托 {@code
 * RuntimeConfigService.write}（校验 30091 + expectedUpdatedAt 30065 + 换快照热生效）。
 */
public interface MarketTopConfigFacade {

    /** 当前配置视图（GET：5 参数 + updatedAt 下次防呆比对；键缺失 = 代码缺省 + null）。 */
    ConfigView view();

    /**
     * 全量替换配置（PATCH 语义：5 字段整体替换，缺失/null → 校验器 30091「必填」拦截，不部分写）。
     *
     * @throws com.info.platform.domain.common.BusinessException 30091 字段级校验失败（原值保留）；30065 并发冲突；
     *     30065/2001 expectedUpdatedAt 非法；50000 写库失败（快照不动，旧值继续生效）
     */
    ConfigView update(ConfigUpdate update);

    /** GET/PATCH 共享视图（字段名与 {@code market.top} 文档一致）。 */
    record ConfigView(
            int poolSize,
            int deepDiveLimit,
            double deepDiveCostCapRatio,
            long diveCostEstimateMicros,
            double memberCoverageFloor,
            String updatedAt) {}

    /**
     * PATCH 请求体：5 字段全量 + 可选并发防呆。字段收 {@link JsonNode} 原样透传（ScoreWeightConfigFacade D3 先例）：类型判定
     * 单一事实源在 {@code MarketTopConfigValidator}（字符串数字 → 30091 字段级，不被 Jackson 静默转换）。
     */
    record ConfigUpdate(
            JsonNode poolSize,
            JsonNode deepDiveLimit,
            JsonNode deepDiveCostCapRatio,
            JsonNode diveCostEstimateMicros,
            JsonNode memberCoverageFloor,
            String expectedUpdatedAt) {}
}
