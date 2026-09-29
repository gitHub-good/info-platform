// 行业主线域视图类型（M27 T245，对齐后端 IndustryMainlineController / IndustryMainlineQueryService 契约 §4.5）。
// leaders / dimDetail / leaderStock 在后端为 JsonNode 透传（§4.4.3 JSON 契约），前端按结构化类型收敛。

/** 领涨股（快照行内嵌 JSON，DDL 注释契约 {code,name,pct}；通道 B 直出）。 */
export interface HeatLeaderStock {
  code: string;
  name: string;
  pct: number | null;
}

/** 热力图单行业格（IndustryCell）。 */
export interface IndustryHeatMapCell {
  industry: string;
  pctDay: number | null;
  pctD5: number | null;
  upCount: number | null;
  downCount: number | null;
  mainNetFlow: number | null;
  totalMv: number | null;
  aggMethod: string | null;
  leaderStock: HeatLeaderStock | null;
}

/** 热力图响应（HeatMapView：各市场行业格 ≤40 + meta）。 */
export interface IndustryHeatMapView {
  /** 市场回显（M29 T255）。 */
  market?: string;
  /** 行业体系口径标注（拍板二）。 */
  industrySystem?: string | null;
  snapshotDate: string | null;
  source: string | null;
  quoteTime: string | null;
  stale: boolean;
  /** 原币口径（拍板六：CNY/HKD/USD，不折算）。 */
  currency?: string | null;
  industries: IndustryHeatMapCell[];
}

/** 三维分解单维（DimDetail：score/rank/raw——dim_detail JSON 元素）。 */
export interface MainlineDim {
  score: number;
  rank: number;
  raw: number | null;
}

/** 三维分解（dimDetail：price 价格动量 / heat 资讯热度 / event 事件密度）。 */
export interface MainlineDimDetail {
  price?: MainlineDim | null;
  heat?: MainlineDim | null;
  event?: MainlineDim | null;
}

/** 龙头 dim.attention（§4.4.3：score/mentions/eventCount/eventWeighted）。 */
export interface LeaderAttentionDim {
  score: number;
  mentions: number;
  eventCount: number;
  eventWeighted: number;
}

/** 龙头 dim.value（§4.4.3：score/totalScore/snapshotDate/dataFlags）。 */
export interface LeaderValueDim {
  score: number;
  totalScore: number | null;
  snapshotDate: string | null;
  dataFlags: string[];
}

/** 龙头 dim.price（§4.4.3：score/pctDay/pctD5/flag——flag 空串=无标记）。 */
export interface LeaderPriceDim {
  score: number;
  pctDay: number | null;
  pctD5: number | null;
  flag: string | null;
}

/** 龙头依据回溯（basis.eventIds 可点跳事件流）。 */
export interface LeaderBasis {
  eventIds: number[];
  factorSnapshotDate: string | null;
  riskEvents: number;
  divergenceNote: string | null;
}

/** 主力关注度代理徽章（state=OK 全量 / UNAVAILABLE 仅 queryTime——「暂无数据」不阻塞）。 */
export interface LeaderAttentionBadge {
  lhb30d: number | null;
  lhbLatest: { date: string | null; reason: string | null } | null;
  chgDirection: string | null;
  chgCount: number | null;
  queryTime: string | null;
  state: string;
}

/** 龙头卡（leaders[] JSON 元素，§4.4.3 契约）。 */
export interface MainlineLeaderCard {
  rank: number;
  rankLabel: string;
  subjectId: number;
  subjectCode: string;
  subjectName: string;
  score: number;
  dim: {
    attention?: LeaderAttentionDim | null;
    value?: LeaderValueDim | null;
    price?: LeaderPriceDim | null;
  } | null;
  basis: LeaderBasis | null;
  attention: LeaderAttentionBadge | null;
  disclaimer: string | null;
}

/** 榜单单行（MainlineItemView）。 */
export interface MainlineItem {
  rankNo: number;
  industry: string;
  mainScore: number;
  dimDetail: MainlineDimDetail | null;
  persistentDays: number;
  heatRank: number | null;
  divergence: string | null;
  leaders: MainlineLeaderCard[] | null;
  /** 龙头可用性（M29 W1：港美股恒 false——占位不静默）。 */
  leadersAvailable?: boolean;
  /** 龙头不可用原因（港美股龙头分析暂未支持——依赖基本面因子体系）。 */
  leaderUnavailableReason?: string | null;
  basis: string | null;
  computedAt: string | null;
}

/** 主线榜单响应（MainlineView：batch 元信息 + Top 3~5 行）。 */
export interface IndustryMainlineView {
  /** 市场回显（M29 T255）。 */
  market?: string;
  /** 行业体系口径标注（拍板二）。 */
  industrySystem?: string | null;
  rankDate: string;
  version: number;
  triggerSource: string | null;
  snapshotDate: string | null;
  degraded: boolean;
  degradedReason: string | null;
  /** 冷启动留痕（拍板五：港美股历史不足 persistMinDays 免门槛出榜，攒足自动恢复）。 */
  bootstrap?: boolean;
  basis: string | null;
  computedAt: string | null;
  items: MainlineItem[];
}

/** 板块明细格（下钻通道 A）。 */
export interface IndustryBoardCell {
  boardName: string;
  pctDay: number | null;
  upCount: number | null;
  downCount: number | null;
  mainNetFlow: number | null;
  totalMv: number | null;
}

/** 成分股格（下钻通道 B：涨跌 Top10/Bottom5）。 */
export interface IndustryConstituentCell {
  code: string;
  name: string;
  pctChange: number | null;
}

/** 行业下钻响应（DetailView：两形态共用——boards（A）/ constituents（B））。 */
export interface IndustryMainlineDetailView {
  /** 市场回显（M29 T255：同名行业靠 market 消歧）。 */
  market?: string;
  /** 行业体系口径标注（拍板二）。 */
  industrySystem?: string | null;
  industry: string;
  snapshotDate: string | null;
  source: string | null;
  quoteTime: string | null;
  stale: boolean;
  pctDay: number | null;
  pctD5: number | null;
  upCount: number | null;
  downCount: number | null;
  mainNetFlow: number | null;
  totalMv: number | null;
  /** 原币口径（拍板六：CNY/HKD/USD，不折算——总市值展示符号）。 */
  currency?: string | null;
  aggMethod: string | null;
  leaderStock: HeatLeaderStock | null;
  boards: IndustryBoardCell[] | null;
  constituents: IndustryConstituentCell[] | null;
  leaders: MainlineLeaderCard[] | null;
  /** 龙头可用性（M29 W1：港美股恒 false——占位不静默）。 */
  leadersAvailable?: boolean;
  /** 龙头不可用原因。 */
  leaderUnavailableReason?: string | null;
  memberCount: number | null;
}

/** `industry.mainline` 13 字段配置视图。 */
export interface MainlineConfigMainline {
  wp: number;
  wh: number;
  we: number;
  priceWinDay: number;
  priceWinD5: number;
  heatH24: number;
  heatD7: number;
  heatDelta: number;
  topN: number;
  persistMinDays: number;
  persistWindowDays: number;
  topThirdRank: number;
  divergenceHeatRank: number;
  updatedAt: string | null;
}

/** `industry.leader` 7 字段配置视图。 */
export interface MainlineConfigLeader {
  wa: number;
  wv: number;
  wq: number;
  mentionDays: number;
  topN: number;
  qDay: number;
  qD5: number;
  updatedAt: string | null;
}

/** 两键配置视图（GET config）。 */
export interface IndustryMainlineConfigView {
  mainline: MainlineConfigMainline;
  leader: MainlineConfigLeader;
}

/** 两键配置全量替换（PATCH config：字段级校验 30096 / expectedUpdatedAt 不符 30065/409）。 */
export interface IndustryMainlineConfigUpdate {
  wp: number;
  wh: number;
  we: number;
  priceWinDay: number;
  priceWinD5: number;
  heatH24: number;
  heatD7: number;
  heatDelta: number;
  topN: number;
  persistMinDays: number;
  persistWindowDays: number;
  topThirdRank: number;
  divergenceHeatRank: number;
  wa: number;
  wv: number;
  wq: number;
  mentionDays: number;
  leaderTopN: number;
  qDay: number;
  qD5: number;
  expectedUpdatedAt?: string;
}

/** 手动重算受理（POST recompute：同步执行，version+1 留痕见任务中心）。 */
export interface IndustryMainlineRecomputeView {
  rankDate: string;
  topSize: number;
  detail: string;
}
