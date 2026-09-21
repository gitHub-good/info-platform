// 自选清单数据适配层（对齐技术方案 §4.1.2 + 后端 WatchlistController）。
// 所有端点受 JWT 保护；创建清单与加标的带 Idempotency-Key 头（业务语义键，
// 后端实际去重走自然键：清单=userId+name，加标的=watchlistId+subjectId）。
//
// 错误码：30010 清单不存在(404) / 30011 已在清单或同名(409) /
//         30012 越权(403) / 30001 标的不存在(404)。

import { request } from './http';
import type { WatchlistItemView, WatchlistView } from '@/types/watchlist';

/** 生成 Idempotency-Key：优先 crypto.randomUUID，回退 name+时间戳（仅审计用，不影响去重）。 */
function idempotencyKey(prefix: string): string {
  const uuid =
    typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : null;
  return uuid ?? `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

/** GET /api/v1/watchlists —— 列出当前用户全部清单（含清单项）。 */
export async function listWatchlists(signal?: AbortSignal): Promise<WatchlistView[]> {
  return request<WatchlistView[]>('/watchlists', { signal });
}

/** POST /api/v1/watchlists —— 创建清单；同名→30011(409)。 */
export async function createWatchlist(name: string, remark?: string): Promise<WatchlistView> {
  return request<WatchlistView>('/watchlists', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey('wl') },
    body: { name, remark: remark ?? null },
  });
}

/** GET /api/v1/watchlists/{id} —— 单清单（含清单项）；不存在→30010，越权→30012。 */
export async function getWatchlist(id: number): Promise<WatchlistView> {
  return request<WatchlistView>(`/watchlists/${id}`);
}

/**
 * POST /api/v1/watchlists/{id}/items —— 加标的；
 * 清单不存在→30010 / 越权→30012 / 标的不存在→30001 / 已在清单→30011(409)。
 */
export async function addWatchlistItem(
  watchlistId: number,
  subjectId: number,
  anomalyThreshold?: number,
): Promise<WatchlistItemView> {
  // 后端返回新增的清单项视图（单项）；页面随后重拉整张清单详情以刷新列表。
  return request<WatchlistItemView>(`/watchlists/${watchlistId}/items`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey('item') },
    body: {
      subjectId,
      ...(anomalyThreshold === undefined ? {} : { anomalyThreshold }),
    },
  });
}

/** DELETE /api/v1/watchlists/{id}/items/{itemId} —— 移除清单项（行级校验，幂等）。 */
export async function removeWatchlistItem(watchlistId: number, itemId: number): Promise<void> {
  await request<unknown>(`/watchlists/${watchlistId}/items/${itemId}`, { method: 'DELETE' });
}

/**
 * PATCH /api/v1/watchlists/{id}/items/{itemId} —— 改异动阈值；
 * 清单/清单项不存在→30010 / 越权→30012。
 */
export async function updateItemThreshold(
  watchlistId: number,
  itemId: number,
  anomalyThreshold: number,
): Promise<WatchlistItemView> {
  return request<WatchlistItemView>(`/watchlists/${watchlistId}/items/${itemId}`, {
    method: 'PATCH',
    body: { anomalyThreshold },
  });
}
