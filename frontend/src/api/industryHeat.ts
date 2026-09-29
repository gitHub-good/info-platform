// 行业热度与日报数据适配层（M15 T126，对齐后端 IndustryHeatController/IndustryReportController 契约）。
// 受 JWT 保护，经 http.ts request 统一注入 Bearer、解析 {code,msg,data,traceId}。
// 错误码：30076 窗口/类型/行业参数非法（400）、30077 日报已成功（409）、30078 日报不存在（404）。

import { request } from './http';
import type { MarketKey } from '@/lib/market';
import type {
  HeatWindow,
  IndustryHeatBoardView,
  IndustryHeatItemsView,
  IndustryItemsType,
  IndustryReportDetailView,
  IndustryReportListView,
  IndustryReportRetryAcceptance,
  IndustryWeeklyReportDetailView,
  IndustryWeeklyReportListView,
} from '@/types/industryHeat';

/** 下钻查询参数（beforeId 游标 + limit 缺省 20 ≤50，越界后端拒绝不截断；market 随页面三市场切换）。 */
export interface IndustryItemsQuery {
  window: HeatWindow;
  type: IndustryItemsType;
  /** 市场（M29 T255：行业枚举随市场切换，缺省 A_SHARE）。 */
  market?: MarketKey;
  beforeId?: number;
  limit?: number;
}

/** 热度榜（window 缺省 H24；market 缺省 A_SHARE——各市场枚举降序 + basis/snapshotAt 脚注 + 口径标注 + 护栏徽章）。 */
export function getIndustryHeatBoard(
  window: HeatWindow,
  market: MarketKey = 'A_SHARE',
  signal?: AbortSignal,
): Promise<IndustryHeatBoardView> {
  return request<IndustryHeatBoardView>(`/industry-heat?window=${window}&market=${market}`, {
    signal,
  });
}

/** 行业下钻（news 含 L2 事件标记 / events 与事件流同口径；total 与榜单计数对账相等——同市场口径）。 */
export function getIndustryHeatItems(
  industry: string,
  query: IndustryItemsQuery,
  signal?: AbortSignal,
): Promise<IndustryHeatItemsView> {
  const params = new URLSearchParams({ window: query.window, type: query.type });
  if (query.market != null) params.set('market', query.market);
  if (query.beforeId != null) params.set('beforeId', String(query.beforeId));
  if (query.limit != null) params.set('limit', String(query.limit));
  const industryPath = encodeURIComponent(industry);
  return request<IndustryHeatItemsView>(`/industry-heat/${industryPath}/items?${params}`, {
    signal,
  });
}

/** 日报列表（report_date DESC，beforeId 游标 + limit 缺省 10）。 */
export function getIndustryReports(
  beforeId?: number,
  limit?: number,
  signal?: AbortSignal,
): Promise<IndustryReportListView> {
  const params = new URLSearchParams();
  if (beforeId != null) params.set('beforeId', String(beforeId));
  if (limit != null) params.set('limit', String(limit));
  const qs = params.size > 0 ? `?${params}` : '';
  return request<IndustryReportListView>(`/industry-reports${qs}`, { signal });
}

/** 日报详情（content/heatTop JSON 全量；不存在 30078/404）。 */
export function getIndustryReportDetail(
  reportDate: string,
  signal?: AbortSignal,
): Promise<IndustryReportDetailView> {
  return request<IndustryReportDetailView>(`/industry-reports/${encodeURIComponent(reportDate)}`, {
    signal,
  });
}

/** 重试 FAILED 日报（202 受理走 JobExecutor 手动通道；已 SUCCESS 30077/409；不存在 30078/404）。 */
export function retryIndustryReport(reportDate: string): Promise<IndustryReportRetryAcceptance> {
  return request<IndustryReportRetryAcceptance>(
    `/industry-reports/${encodeURIComponent(reportDate)}/retry`,
    { method: 'POST' },
  );
}

// —— 行业周报（M17 T145）：/industry-reports/weekly 三端点（错误码 30084 不存在 / 30085 已成功） ——

/** 周报列表（week_start DESC，beforeId 游标 + limit 缺省 10）。 */
export function getIndustryWeeklyReports(
  beforeId?: number,
  limit?: number,
  signal?: AbortSignal,
): Promise<IndustryWeeklyReportListView> {
  const params = new URLSearchParams();
  if (beforeId != null) params.set('beforeId', String(beforeId));
  if (limit != null) params.set('limit', String(limit));
  const qs = params.size > 0 ? `?${params}` : '';
  return request<IndustryWeeklyReportListView>(`/industry-reports/weekly${qs}`, { signal });
}

/** 周报详情（content/heatTop 五区块 JSON 全量；不存在 30084/404）。 */
export function getIndustryWeeklyReportDetail(
  weekStart: string,
  signal?: AbortSignal,
): Promise<IndustryWeeklyReportDetailView> {
  return request<IndustryWeeklyReportDetailView>(
    `/industry-reports/weekly/${encodeURIComponent(weekStart)}`,
    { signal },
  );
}

/** 重试 FAILED 周报（202 受理；已 SUCCESS 30085/409；不存在 30084/404）。 */
export function retryIndustryWeeklyReport(weekStart: string): Promise<IndustryReportRetryAcceptance> {
  return request<IndustryReportRetryAcceptance>(
    `/industry-reports/weekly/${encodeURIComponent(weekStart)}/retry`,
    { method: 'POST' },
  );
}
