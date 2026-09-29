// 资讯脉搏数据适配层（V3.2 M28，对齐后端 NewsPulseController）。
// 窗口码白名单与后端 NewsPulseWindow 同源；非法值后端 2xxx/400。

import { request } from './http';
import type { MarketKey } from '@/lib/market';
import type { PulseRow, PulseWindowView } from '@/types/newsPulse';

/** 时间窗码（后端 NewsPulseWindow.code 口径，顺序即展示序）。 */
export type NewsPulseWindowKey = '30m' | '1h' | '3h' | '6h' | '12h' | '24h';

/** 展示名（tab 文案）。 */
export const WINDOW_LABELS: Record<NewsPulseWindowKey, string> = {
  '30m': '30 分钟',
  '1h': '1 小时',
  '3h': '3 小时',
  '6h': '6 小时',
  '12h': '12 小时',
  '24h': '24 小时',
};

/**
 * GET /api/v1/news-pulse?market= —— 六窗最新快照总览（从未分析的窗口 latest=null）。
 * M29 T257：market 参数随五页统一控件下发（§5.3 契约零变化——后端当前不识别该参数则忽略，
 * 联调差异由 T258 收口；市场归集四桶恒全量回显）。
 */
export function listPulseWindows(
  market: MarketKey = 'A_SHARE',
  signal?: AbortSignal,
): Promise<PulseWindowView[]> {
  return request<PulseWindowView[]>(`/news-pulse?market=${market}`, { signal });
}

/**
 * GET /api/v1/news-pulse/{window}?market= —— 单窗最新快照（无快照 data=null）。
 */
export function getPulse(
  window: NewsPulseWindowKey,
  market: MarketKey = 'A_SHARE',
  signal?: AbortSignal,
): Promise<PulseRow | null> {
  return request<PulseRow | null>(`/news-pulse/${window}?market=${market}`, { signal });
}

/**
 * POST /api/v1/news-pulse/{window}/refresh?market= —— 手动重算单窗（同步返回新快照）。
 */
export function refreshPulse(window: NewsPulseWindowKey, market: MarketKey = 'A_SHARE'): Promise<PulseRow> {
  return request<PulseRow>(`/news-pulse/${window}/refresh?market=${market}`, { method: 'POST' });
}
