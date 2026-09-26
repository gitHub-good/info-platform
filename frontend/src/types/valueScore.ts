// 价值评分类型（M20 T172，对齐后端 ScoreWeightConfigFacade 契约 §4.7.3）。
// 时间字段为 ISO-8601 字符串（UTC）；权重 PATCH 全量替换保存即热生效——下一轮 17:30 快照按新参数计算（ADR-0058 裁决 4）。

/** 五维权重 + 双窗 + 半衰期 + 双饱和常数 + 「有突破」三阈值（键名与 score.weight 文档一致）。 */
export interface ScoreWeights {
  wCatalyst: number;
  wConduction: number;
  wFundamental: number;
  wRisk: number;
  wValuation: number;
  catalystWindowDays: number;
  assocWindowDays: number;
  halfLifeDays: number;
  k1Saturation: number;
  k3Saturation: number;
  btCatalystMin: number;
  btConductionMin: number;
  btRiskMin: number;
}

/** GET /value-scores/weights 响应：13 参数 + basis 派生指纹 + updatedAt（下次防呆比对）。 */
export interface ScoreWeightsView extends ScoreWeights {
  basis: string;
  updatedAt: string | null;
}

/** PATCH /value-scores/weights 请求：13 字段全量整体替换 + 可选并发防呆（不符 → 30065/409）。 */
export interface ScoreWeightsUpdate extends ScoreWeights {
  expectedUpdatedAt?: string;
}

/** Dialog 权重分组编辑的八个可编辑字段（五权重 + 三阈值；窗口/K/半衰期留配置层，不入编辑面）。 */
export type ScoreWeightField = keyof Pick<
  ScoreWeights,
  'wCatalyst' | 'wConduction' | 'wFundamental' | 'wRisk' | 'wValuation' | 'btCatalystMin' | 'btConductionMin' | 'btRiskMin'
>;
