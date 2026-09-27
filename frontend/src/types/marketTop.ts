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

// —— 榜单读取面（M21 T184，对齐 MarketTopQueryService 契约 §4.7.1；30089 无榜单 / 30090 参数非法或版本不存在） ——

/** 五维分解条目（快照行 f 列 + weight_basis 当时权重回读——与价值评分端点同构）。 */
export interface MarketTopFactor {
  key: string;
  name: string;
  score: number;
  weight: number;
}

/** 深析引用（EVENT → 事件流 focus 下钻 / NEWS → 资讯库）。 */
export interface MarketTopCitation {
  type: 'EVENT' | 'NEWS';
  id: number;
}

/** 深析条目（亮点/风险——text + 引用集）。 */
export interface MarketTopDiveEntry {
  text: string;
  citations: MarketTopCitation[];
}

/** dive_detail 结构（generation=FULL 齐备；FACTOR_ONLY / 快照清理后为空对象——字段均可缺省）。 */
export interface MarketTopDiveDetail {
  thesis?: string;
  highlights?: MarketTopDiveEntry[];
  risks?: MarketTopDiveEntry[];
  citations?: MarketTopCitation[];
}

/** 深析产出方式（LLM / TEMPLATE / FACTOR_ONLY 降级未深析）。 */
export type MarketTopDiveMethod = 'LLM' | 'TEMPLATE' | 'FACTOR_ONLY';

/** 榜单卡（items 元素；generation=FULL 有深析、FACTOR_ONLY 按因子分排序）。 */
export interface MarketTopItem {
  rankNo: number;
  subjectId: number;
  subjectCode: string;
  subjectName: string;
  totalScore: number;
  finalScore: number;
  percentile: number | null;
  breakthrough: boolean;
  factors: MarketTopFactor[];
  generation: 'FULL' | 'FACTOR_ONLY';
  diveMethod: MarketTopDiveMethod | null;
  diveSummary: string | null;
  diveDetail: MarketTopDiveDetail | null;
  evidenceCount: number;
  lastEventDate: string | null;
  prevRank: number | null;
  changeType: 'NEW' | 'UP' | 'DOWN' | 'SAME';
  computedAt: string;
}

/** 漏斗计数（funnel_stats：全量 → 粗筛池 → 深析候选 → Top10 各层留痕）。 */
export interface MarketTopFunnelStats {
  snapshotRows: number;
  eligible: number;
  excluded: { st: number; noSignal: number };
  poolSize: number;
  divePlanned: number;
  diveDone: number;
  diveTemplate: number;
  diveSkipped: number;
  topSize: number;
}

/** 跌出名单（dropped 元素：昨日入榜今日出榜的标的留痕）。 */
export interface MarketTopDropped {
  code: string;
  name: string;
  prevRank: number;
}

/** batch 视图（生成信息/漏斗/降级/跌出——页面数据面）。 */
export interface MarketTopBatch {
  snapshotDate: string;
  computedAt: string;
  degraded: boolean;
  degradedReason: 'COST_CAP' | 'LLM_FAILURE' | null;
  funnelStats: Partial<MarketTopFunnelStats>;
  dropped: MarketTopDropped[];
  lastEvent: string | null;
}

/** EVENT 版本触发事件（trace-v1 下钻——eventId 跳事件流 focus）。 */
export interface MarketTopTriggerEvent {
  eventId: number;
  summary: string | null;
  importance: string | null;
}

/** 页头「最近增量重评」（M22 T192 §4.2-②：当日最新 EVENT 版本摘要——无则 null，双层口径数据源）。 */
export interface MarketTopRecentIncrement {
  version: number;
  computedAt: string;
  triggerEvents: MarketTopTriggerEvent[];
}

/** GET /market-top 响应（缺省最新有榜单日最大版本；recentIncrement 无 EVENT 版本时为 null）。 */
export interface MarketTopRankView {
  rankDate: string;
  version: number;
  triggerSource: string;
  batch: MarketTopBatch;
  items: MarketTopItem[];
  disclaimer: string;
  recentIncrement: MarketTopRecentIncrement | null;
}

/** GET /market-top/versions 列表项（日期降序、版本降序）。 */
export interface MarketTopVersionSummary {
  rankDate: string;
  version: number;
  triggerSource: string;
  degraded: boolean;
  degradedReason: string | null;
  snapshotDate: string;
  topSize: number;
  computedAt: string;
}


// —— 历史命中统计（M22 T193 §4.2-③，hits-v1 惰性回算——零新表零新 Job） ——

/** 单榜单日统计（N/10 样本标注：pricedSamples/excluded——停牌/无价剔除计数不隐藏）。 */
export interface MarketTopHitDay {
  rankDate: string;
  topSize: number;
  pricedSamples: number;
  excluded: number;
  upRatio: number | null;
  medianPctChg: number | null;
}

/** 单窗聚合（OK / INSUFFICIENT——样本日 < 5 如实标注，样本积累中）。 */
export interface MarketTopHitAgg {
  days: number;
  status: 'OK' | 'INSUFFICIENT';
  upRatio: number | null;
  medianPct: number | null;
}

/** 观察窗（T+1 / T+5 / T+20；days 升序）。 */
export interface MarketTopHitWindow {
  window: string;
  days: MarketTopHitDay[];
  agg: MarketTopHitAgg;
}

/** GET /market-top/hit-stats 响应（basis 口径留档 + asOf + 免责常驻 + 三窗）。 */
export interface MarketTopHitStatsView {
  basis: string;
  asOf: string | null;
  disclaimer: string;
  windows: MarketTopHitWindow[];
}
