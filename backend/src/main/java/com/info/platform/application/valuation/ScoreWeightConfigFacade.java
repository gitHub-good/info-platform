package com.info.platform.application.valuation;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 评分权重读写端口（M20 T172，方案 §4.7.3）：GET/PATCH {@code /api/v1/value-scores/weights} 的应用层门面（任务中心
 * FACTOR_SNAPSHOT 编辑 Dialog 数据源，retention 窗口先例）。
 *
 * <p>端口在应用层、实现在基础设施层（{@code infrastructure.valuation.ScoreWeightConfigFacadeImpl}）：实现需读写
 * runtime_config（RuntimeConfigService 换快照与事件发布，RetentionConfigFacadeImpl 同因）。读侧走 {@link
 * ValuationSettings}（字段级回退防御）+ basis 派生；写侧拼全量文档委托 {@code RuntimeConfigService.write}（校验 30087 +
 * expectedUpdatedAt 30065 + 换快照热生效——下一轮 FACTOR_SNAPSHOT 按新参数与 basis 计算）。
 */
public interface ScoreWeightConfigFacade {

    /** 当前权重视图（GET：13 参数 + basis 派生指纹 + updatedAt 下次防呆比对；键缺失 = 代码缺省 + null）。 */
    WeightsView view();

    /**
     * 全量替换权重与阈值（PATCH 语义：13 字段整体替换，缺失/null → 校验器 30087「必填」拦截，不部分写）。
     *
     * @throws com.info.platform.domain.common.BusinessException 30087 字段级校验失败（原值保留）； 30065
     *     并发冲突；30065/2001 expectedUpdatedAt 非法；50000 写库失败（快照不动，旧值继续生效）
     */
    WeightsView update(WeightsUpdate update);

    /** GET/PATCH 共享视图（字段名与 {@code score.weight} 文档一致；basis 由参数代码拼装非用户输入，快照行直落同串）。 */
    record WeightsView(
            double wCatalyst,
            double wConduction,
            double wFundamental,
            double wRisk,
            double wValuation,
            int catalystWindowDays,
            int assocWindowDays,
            double halfLifeDays,
            double k1Saturation,
            double k3Saturation,
            int btCatalystMin,
            int btConductionMin,
            int btRiskMin,
            String basis,
            String updatedAt) {}

    /**
     * PATCH 请求体：13 字段全量 + 可选并发防呆。字段收 {@link JsonNode} 原样透传（RetentionConfigFacade D3 先例）： 类型判定单一事实源在
     * {@code ValuationConfigValidator}（字符串数字/浮点阈值 → 30087 字段级，不被 Jackson 静默转换）。
     */
    record WeightsUpdate(
            JsonNode wCatalyst,
            JsonNode wConduction,
            JsonNode wFundamental,
            JsonNode wRisk,
            JsonNode wValuation,
            JsonNode catalystWindowDays,
            JsonNode assocWindowDays,
            JsonNode halfLifeDays,
            JsonNode k1Saturation,
            JsonNode k3Saturation,
            JsonNode btCatalystMin,
            JsonNode btConductionMin,
            JsonNode btRiskMin,
            String expectedUpdatedAt) {}
}
