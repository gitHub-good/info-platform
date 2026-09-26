// 北极星指标类型（M18 T158，对齐后端 NorthStarView——GET /api/v1/north-star 契约，ns-v1）。

/** 指标判定状态：MET 达标 / NOT_MET 可判定未达标 / INSUFFICIENT 样本不足（首跑校准条款）。 */
export type NorthStarStatus = 'MET' | 'NOT_MET' | 'INSUFFICIENT';

/** 感知延迟卡（与大盘 global.latency 同源同值，增量轮 v1 口径）。 */
export interface NorthStarLatency {
  p50Millis: number | null;
  p90Millis: number | null;
  sampleCount: number;
  status: NorthStarStatus;
  basis: string;
}

/** 行业覆盖率卡（当日 L1 归类分布：申万 31 命中比例，容器不计分子）。 */
export interface NorthStarCoverage {
  coverageRatio: number | null;
  hitIndustries: number;
  totalIndustries: number;
  classifiedToday: number;
  status: NorthStarStatus;
}

/** 稳定源卡（7 天窗成功率 ≥95% 的启用源数）。 */
export interface NorthStarStableSources {
  stableCount: number;
  enabledCount: number;
  windowDays: number;
  status: NorthStarStatus;
}

/** 日净入库卡（今日值 + 含当日 7 天日均，去重后口径同大盘）。 */
export interface NorthStarDailyIntake {
  todayNew: number;
  avg7d: number;
  status: NorthStarStatus;
}

/** 采纳率卡（当日 adopt-v1；exposure = 推送送达 + 视口曝光）。 */
export interface NorthStarAdoptRate {
  adoptRate: number | null;
  exposure: number;
  adopted: number;
  status: NorthStarStatus;
  basis: string;
}

/** 成本护栏两线卡（占比 ≤60% + 单条 ≤0.02 元；回灌日触线走两级降级语义）。 */
export interface NorthStarCostGuard {
  usageRatio: number;
  todayCostMicros: number;
  budgetMicros: number;
  perItemMicros: number | null;
  costBasis: string;
  status: NorthStarStatus;
}

/** 北极星六指标视图（六指标卡一端点聚合 + 7 天入库迷你趋势）。 */
export interface NorthStarView {
  basis: string;
  generatedAt: string;
  latency: NorthStarLatency;
  coverage: NorthStarCoverage;
  stableSources: NorthStarStableSources;
  dailyIntake: NorthStarDailyIntake;
  adoptRate: NorthStarAdoptRate;
  costGuard: NorthStarCostGuard;
  intakeTrend: { date: string; count: number }[];
}
