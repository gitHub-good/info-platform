// 全市场榜单类型（M21 T181，对齐后端 MarketTopConfigFacade 契约 §4.7.3）。
// 时间字段为 ISO-8601 字符串（UTC）；PATCH 5 字段全量替换保存即热生效——下一轮 18:00 榜单按新参数计算。

/** 漏斗配置（键名与 market.top 文档一致；deepDiveLimit 30~50 为蓝图区间硬校验）。 */
export interface MarketTopConfig {
  poolSize: number;
  deepDiveLimit: number;
  deepDiveCostCapRatio: number;
  diveCostEstimateMicros: number;
  memberCoverageFloor: number;
}

/** GET /market-top/config 响应：5 参数 + updatedAt（下次防呆比对）。 */
export interface MarketTopConfigView extends MarketTopConfig {
  updatedAt: string | null;
}

/** PATCH /market-top/config 请求：5 字段全量整体替换 + 可选并发防呆（不符 → 30065/409；非法 → 30091/400 字段级）。 */
export interface MarketTopConfigUpdate extends MarketTopConfig {
  expectedUpdatedAt?: string;
}

/** Dialog 榜单配置分组编辑的五字段。 */
export type MarketTopConfigField = keyof Omit<MarketTopConfig, never> &
  ('poolSize' | 'deepDiveLimit' | 'deepDiveCostCapRatio' | 'diveCostEstimateMicros' | 'memberCoverageFloor');
