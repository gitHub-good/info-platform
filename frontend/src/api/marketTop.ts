// 全市场榜单数据适配层（M21 T181/T184 + 后端 MarketTopController 契约 §4.7）。
// 受 JWT 保护，经 http.ts request 自动注入 Bearer、解析 {code,msg,data,traceId}。
// PATCH 5 字段全量替换，带 expectedUpdatedAt 防并发误覆盖（不符 → 30065/409）；非法值 30091 字段级、原值保留。
// 读取面：GET /market-top 榜单详情（30089 无榜单 / 30090 参数非法或版本不存在）+ GET /market-top/versions。

import { request } from './http';
import type { MarketKey } from '@/lib/market';
import type {
  MarketTopConfigUpdate,
  MarketTopConfigView,
  MarketTopHitStatsView,
  MarketTopRankView,
  MarketTopVersionSummary,
} from '@/types/marketTop';

/** 拉取当前漏斗配置视图（任务中心 MARKET_TOP_JOB 编辑 Dialog 预填数据源）。 */
export function getMarketTopConfig(signal?: AbortSignal): Promise<MarketTopConfigView> {
  return request<MarketTopConfigView>('/market-top/config', { signal });
}

/** 全量替换漏斗配置（保存即热生效——下一轮 18:00 榜单按新参数计算）。 */
export function patchMarketTopConfig(update: MarketTopConfigUpdate): Promise<MarketTopConfigView> {
  return request<MarketTopConfigView>('/market-top/config', {
    method: 'PATCH',
    body: update,
  });
}

/**
 * 榜单详情（GET /api/v1/market-top?market=&date=&version=；M29 T256 分市场独立榜单——
 * market 缺省 A_SHARE，后端在途未识别时忽略该参数回 A 股榜，容错不报错）。
 * @throws ApiError 30089 无任何榜单（404）/ 30090 参数非法或版本不存在
 */
export function getMarketTopRank(
  query: { market?: MarketKey; date?: string; version?: number } = {},
  signal?: AbortSignal,
): Promise<MarketTopRankView> {
  const params = new URLSearchParams();
  if (query.market != null) params.set('market', query.market);
  if (query.date) params.set('date', query.date);
  if (query.version != null) params.set('version', String(query.version));
  const qs = params.size > 0 ? `?${params}` : '';
  return request<MarketTopRankView>(`/market-top${qs}`, { signal });
}

/** 历史版本列表（GET /api/v1/market-top/versions?market=&date=；日期降序、版本降序——分市场独立）。 */
export function getMarketTopVersions(
  query: { market?: MarketKey; date?: string } = {},
  signal?: AbortSignal,
): Promise<MarketTopVersionSummary[]> {
  const params = new URLSearchParams();
  if (query.market != null) params.set('market', query.market);
  if (query.date) params.set('date', query.date);
  const qs = params.size > 0 ? `?${params}` : '';
  return request<MarketTopVersionSummary[]>(`/market-top/versions${qs}`, { signal });
}

/**
 * 历史命中统计（GET /api/v1/market-top/hit-stats?market=，M22 T193 hits-v1 惰性回算；
 * M29 T256 分市场——market 缺省 A_SHARE）。
 * @throws ApiError 30089 无任何榜单（404）——「榜单页引导生成」空态口径
 */
export function getMarketTopHitStats(
  market: MarketKey = 'A_SHARE',
  signal?: AbortSignal,
): Promise<MarketTopHitStatsView> {
  return request<MarketTopHitStatsView>(`/market-top/hit-stats?market=${market}`, { signal });
}
