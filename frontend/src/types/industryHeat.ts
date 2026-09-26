// 行业热度与日报类型（M15 T126，对齐后端 IndustryHeatBoardView/IndustryHeatItemsView/
// IndustryReportListView/IndustryReportDetailView——方案 §4.8 契约）。
// 枚举线格式 = 后端 Java 枚举 name()：HeatWindow/GuardLevel/ReportStatus/EventType/Direction/Importance。

/** 热度窗口（H24 近 24 小时 / D7 近 7 天）。 */
export type HeatWindow = 'H24' | 'D7';

/** 护栏等级（NORMAL 正常 / DEGRADED 一级降级 / FUSED 二级熔断，横幅三处同源之一）。 */
export type GuardLevel = 'NORMAL' | 'DEGRADED' | 'FUSED';

/** 热度榜行（31 行业降序，0 分沉底；deltaPct = vs 上一等长窗口环比）。 */
export interface IndustryHeatRow {
  industry: string;
  heatScore: number;
  prevScore: number;
  deltaPct: number;
  newsCount: number;
  eventCount: number;
}

/** 热度榜视图（basis 口径版本串 + snapshotAt 快照时刻 + pipeline 护栏徽章数据面）。 */
export interface IndustryHeatBoardView {
  window: HeatWindow;
  industries: IndustryHeatRow[];
  basis: string;
  snapshotAt: string;
  pipeline: { level: GuardLevel };
}

/** 下钻清单类型（news = 该行业 L1 DONE 条目 / events = 影响该行业的事件，与事件流同口径）。 */
export type IndustryItemsType = 'news' | 'events';

/** 下钻条目卡（news 行与 events 行共用容器，未用字段 null）。 */
export interface IndustryHeatItem {
  newsId: number;
  eventId: number | null;
  title: string | null;
  sourceName: string | null;
  publishedAt: string | null;
  hasEvent: boolean | null;
  eventType: string | null;
  summary: string | null;
  direction: string | null;
  importance: string | null;
  eventTime: string | null;
}

/** 下钻视图（total 与榜单 newsCount/eventCount 对账相等；nextBeforeId 尾页 null）。 */
export interface IndustryHeatItemsView {
  industry: string;
  window: HeatWindow;
  type: IndustryItemsType;
  total: number;
  items: IndustryHeatItem[];
  nextBeforeId: number | null;
}

/** 日报状态（SUCCESS 含纯统计降级版 / FAILED 可重试）。 */
export type ReportStatus = 'SUCCESS' | 'FAILED';

/** 日报列表行。 */
export interface IndustryReportListItem {
  id: number;
  reportDate: string;
  status: ReportStatus;
  summary: string;
  totalNews: number;
  totalEvents: number;
  narrativeDegraded: boolean;
  createdAt: string | null;
  updatedAt: string | null;
}

/** 日报列表视图（beforeId 游标）。 */
export interface IndustryReportListView {
  reports: IndustryReportListItem[];
  nextBeforeId: number | null;
}

/** 日报关键数字（来自原文，label + value + unit）。 */
export interface ReportFigure {
  label: string | null;
  value: string | null;
  unit: string | null;
}

/** 日报精选事件（L2 结构化列直读，quote/figures 可回溯）。 */
export interface ReportEvent {
  eventId: number;
  newsId: number;
  eventType: string;
  summary: string;
  industries: string[];
  direction: string;
  importance: string;
  quote: string | null;
  figures: ReportFigure[];
  eventTime: string | null;
}

/** 日报 Top 行业动态（统计数字 + AI 点评合并）。 */
export interface ReportTopIndustry {
  industry: string;
  newsCount: number;
  eventCount: number;
  heatScore: number;
  deltaPct: number;
  commentary: string | null;
  refEventIds: number[];
}

/** 日报 content JSON（后端解析后全量透出；数字全部统计 SQL 产出）。 */
export interface IndustryReportContent {
  summary: string;
  narrativeDegraded: boolean;
  watchPoints: string[];
  industryCounts: Record<string, number>;
  containerCounts: Record<string, number>;
  totalNews: number;
  totalEvents: number;
  topIndustries: ReportTopIndustry[];
  events: ReportEvent[];
  disclaimer: string;
}

/** 日报生成时点热度快照留存（31 行 H24 榜）。 */
export interface ReportHeatTopRow {
  industry: string;
  heatScore: number;
  prevScore: number;
  deltaPct: number;
  newsCount: number;
  eventCount: number;
  snapshotAt: string;
}

/** 日报详情视图（content/heatTop 为 JSON 全量）。 */
export interface IndustryReportDetailView {
  id: number;
  reportDate: string;
  status: ReportStatus;
  content: IndustryReportContent | null;
  heatTop: ReportHeatTopRow[] | null;
  errorMessage: string | null;
  promptVersion: string | null;
  basis: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

/** 重试受理回执（202，executionId 供任务中心留痕对账）。 */
export interface IndustryReportRetryAcceptance {
  executionId: number;
}

/** 事件类型中文展示（后端 EventType displayName 的前端镜像，卡片/日报徽章共用）。 */
export const EVENT_TYPE_LABELS: Record<string, string> = {
  EARNINGS_FORECAST: '业绩预告',
  MA_MERGER: '并购重组',
  BUYBACK_CHANGE: '回购·增持·减持',
  MAJOR_CONTRACT: '重大合同·中标',
  POLICY_RELEASE: '政策发布',
  REGULATORY_PENALTY: '监管处罚·立案',
  EXEC_CHANGE: '高管变动',
  TECH_BREAKTHROUGH: '技术突破·产品发布',
  OTHER: '其他',
};

/** 事件方向中文展示（Direction.displayName 镜像）。 */
export const DIRECTION_LABELS: Record<string, string> = {
  BULLISH: '利好',
  BEARISH: '利空',
  NEUTRAL: '中性',
};

/** 事件重要度中文展示（Importance 镜像：HIGH 1.0 / MEDIUM 0.5 / LOW 0.25）。 */
export const IMPORTANCE_LABELS: Record<string, string> = {
  HIGH: '高',
  MEDIUM: '中',
  LOW: '低',
};

/** 未知枚举的兜底展示（线格式越界不崩，原样可见）。 */
export function labelOf(map: Record<string, string>, key: string | null | undefined): string {
  if (!key) return '--';
  return map[key] ?? key;
}

// —— 行业周报（M17 T145，对齐后端 IndustryWeeklyReportListView/DetailView——GET /api/v1/industry-reports/weekly 契约） ——

/** 周报列表行。 */
export interface IndustryWeeklyReportListItem {
  id: number;
  weekStart: string;
  status: ReportStatus;
  summary: string | null;
  totalNews: number;
  totalEvents: number;
  narrativeDegraded: boolean;
  createdAt: string | null;
  updatedAt: string | null;
}

/** 周报列表视图（beforeId 游标）。 */
export interface IndustryWeeklyReportListView {
  reports: IndustryWeeklyReportListItem[];
  nextBeforeId: number | null;
}

/** 周报热度总览行（升/降温 Top + 周环比）。 */
export interface WeeklyMoverRow {
  industry: string;
  score: number;
  prevScore: number;
  deltaPct: number;
  newsCount: number;
  eventCount: number;
}

/** 周报事件回顾行（主键归并，quote 原文可回溯）。 */
export interface WeeklyEventRow {
  eventId: number;
  newsId: number;
  eventType: string;
  summary: string;
  industries: string[];
  direction: string;
  importance: string;
  quote: string | null;
  eventDate: string | null;
  eventTime: string | null;
}

/** 周报政策动向行（POLICY_RELEASE 周窗清单）。 */
export interface WeeklyPolicyRow {
  eventId: number;
  summary: string;
  industries: string[];
  direction: string;
  quote: string | null;
  eventTime: string | null;
}

/** 走向判断行（信号/置信度 trend-v1 规则层锁定；narrativeSource=LLM/TEMPLATE）。 */
export interface WeeklyTrendItem {
  industry: string;
  signal: string;
  signalLabel: string;
  confidence: string;
  confidenceLabel: string;
  deltaPct: number;
  weekScore: number;
  prevScore: number;
  eventCount: number;
  policyCount: number;
  narrative: string;
  narrativeSource: string;
  evidenceEventIds: number[];
}

/** 周报 content 五区块 JSON。 */
export interface IndustryWeeklyReportContent {
  summary: string;
  narrativeDegraded: boolean;
  weekStart: string;
  weekEnd: string;
  totalNews: number;
  totalEvents: number;
  topRisers: WeeklyMoverRow[];
  topFallers: WeeklyMoverRow[];
  eventReview: WeeklyEventRow[];
  policyMoves: WeeklyPolicyRow[];
  nextWeekWatch: string[];
  trendJudgement: { basis: string; items: WeeklyTrendItem[] };
  disclaimer: string;
}

/** 周报详情视图。 */
export interface IndustryWeeklyReportDetailView {
  id: number;
  weekStart: string;
  status: ReportStatus;
  content: IndustryWeeklyReportContent | null;
  heatTop: Array<Record<string, unknown>> | null;
  errorMessage: string | null;
  promptVersion: string | null;
  basis: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

/** 走向判断信号中文展示（TrendSignal.displayName 镜像）。 */
export const TREND_SIGNAL_LABELS: Record<string, string> = {
  HEATING: '升温',
  COOLING: '降温',
  STABLE: '平稳',
};

/** 走向判断置信度中文展示（TrendConfidence 镜像——trend-v1 规则层锁定）。 */
export const TREND_CONFIDENCE_LABELS: Record<string, string> = {
  HIGH: '高',
  MEDIUM: '中',
  LOW: '低',
};
