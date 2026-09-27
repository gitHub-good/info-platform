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

// —— 标的价值评分（详情区块，M20 T173，§4.7.1 契约） ——

/** 五维分解条目（weight 按快照行当时 weight_basis 回读；neutral 仅估值维缺数态为 true）。 */
export interface ValueScoreFactor {
  key: 'catalyst' | 'conduction' | 'fundamental' | 'risk' | 'valuation';
  name: string;
  score: number;
  weight: number;
  neutral: boolean;
}

/** 依据事件条目（factor_detail.catalyst/fundamental/risk.entries[] 项；eventId 跳事件流 focus）。 */
export interface ValueScoreEntry {
  eventId: number;
  summary: string;
  eventDate: string;
  direction: string;
  importance: string;
  coef: number;
  decay: number;
}

/** 行业关联条目（factor_detail.conduction.assoc[] 项）。 */
export interface ValueScoreAssoc {
  industry: string;
  heatH24: number;
  heatNorm: number;
  lastSeenAge: number;
  source: string;
}

/** factor_detail 明细（§4.5 契约子集——前端消费面：catalyst 依据事件 + conduction 关联 + valuation 缺数判定）。 */
export interface ValueScoreDetail {
  catalyst?: { raw: number; entries: ValueScoreEntry[] };
  conduction?: { assoc: ValueScoreAssoc[] };
  fundamental?: { raw: number; entries: ValueScoreEntry[] };
  risk?: { stFlag: boolean; eventPenalty: number; entries: ValueScoreEntry[] };
  valuation?: { basis: string | null; pe: number | null; pct: number | null; pb: number | null };
}

/** 增量触发事件（increment.events[] 项——留痕表反查，eventId 跳事件流 focus，trace-v1 下钻）。 */
export interface ValueScoreIncrementEvent {
  eventId: number;
  summary: string | null;
  importance: string | null;
  eventDate: string | null;
}

/** 事件驱动增量覆盖块（M22 T192，时间戳双层语义 §4.2-①：当日行未被增量覆盖时为 null——显示盘后基准时刻无标注）。 */
export interface ValueScoreIncrement {
  updatedAt: string;
  events: ValueScoreIncrementEvent[];
}

/** GET /subjects/{subjectId}/value-score 响应（最新快照 + 查询层百分位 + 分解 + 明细 + 免责 + increment 增量块）。 */
export interface ValueScoreView {
  subjectId: number;
  snapshotDate: string;
  totalScore: number;
  breakthrough: boolean;
  rank: number;
  percentile: number;
  factors: ValueScoreFactor[];
  detail: ValueScoreDetail;
  dataFlags: string[];
  weightBasis: string;
  computedAt: string;
  disclaimer: string;
  increment: ValueScoreIncrement | null;
}
