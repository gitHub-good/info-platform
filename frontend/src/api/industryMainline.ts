// 行业主线数据适配层（M27 T245，对齐后端 IndustryMainlineController 契约 §4.5——六端点）。
// 受 JWT 保护，经 http.ts request 统一注入 Bearer、解析 {code,msg,data,traceId}。
// 错误码：30093 快照全空（404）/ 30094 无榜单（404）/ 30095 查询参数非法（400）/ 30096 配置校验失败（400）；
// PATCH 带 expectedUpdatedAt 并发防呆（不符 → 30065/409）。

import { request } from './http';
import type {
  IndustryHeatMapView,
  IndustryMainlineConfigUpdate,
  IndustryMainlineConfigView,
  IndustryMainlineDetailView,
  IndustryMainlineRecomputeView,
  IndustryMainlineView,
} from '@/types/industryMainline';

/**
 * 31 行业热力图（GET /api/v1/industry-heat-map?date=）。
 * @throws ApiError 30093 快照无任何数据（404——空态由页面 EmptyState 呈现）
 */
export function getIndustryHeatMap(date?: string, signal?: AbortSignal): Promise<IndustryHeatMapView> {
  const qs = date ? `?date=${encodeURIComponent(date)}` : '';
  return request<IndustryHeatMapView>(`/industry-heat-map${qs}`, { signal });
}

/**
 * 主线榜单（GET /api/v1/industry-mainline?date=&version=；缺省最新有榜日最大版本）。
 * @throws ApiError 30094 全库无榜单（404）/ 30095 参数非法或该日+版本不存在（400）
 */
export function getIndustryMainline(
  query: { date?: string; version?: number } = {},
  signal?: AbortSignal,
): Promise<IndustryMainlineView> {
  const params = new URLSearchParams();
  if (query.date) params.set('date', query.date);
  if (query.version != null) params.set('version', String(query.version));
  const qs = params.size > 0 ? `?${params}` : '';
  return request<IndustryMainlineView>(`/industry-mainline${qs}`, { signal });
}

/**
 * 行业下钻（GET /api/v1/industry-mainline/{industry}/detail；当日行 source 分形态：
 * 通道 A 板块明细 / 通道 B 成分股涨跌 + 领涨股）。
 * @throws ApiError 30095 行业非申万 31 枚举或该行业无快照行（400/404）
 */
export function getIndustryMainlineDetail(
  industry: string,
  signal?: AbortSignal,
): Promise<IndustryMainlineDetailView> {
  return request<IndustryMainlineDetailView>(
    `/industry-mainline/${encodeURIComponent(industry)}/detail`,
    { signal },
  );
}

/** 两键配置视图（GET config——页面配置 Dialog 预填数据源；键缺失 = 代码缺省）。 */
export function getIndustryMainlineConfig(signal?: AbortSignal): Promise<IndustryMainlineConfigView> {
  return request<IndustryMainlineConfigView>('/industry-mainline/config', { signal });
}

/** 两键配置全量替换（PATCH config——保存即热生效；非法 30096 字段级原值保留）。 */
export function patchIndustryMainlineConfig(
  update: IndustryMainlineConfigUpdate,
): Promise<IndustryMainlineConfigView> {
  return request<IndustryMainlineConfigView>('/industry-mainline/config', {
    method: 'PATCH',
    body: update,
  });
}

/** 手动重算（POST recompute——同步执行 version+1；任务中心手动触发同路径）。 */
export function recomputeIndustryMainline(): Promise<IndustryMainlineRecomputeView> {
  return request<IndustryMainlineRecomputeView>('/industry-mainline/recompute', { method: 'POST' });
}
