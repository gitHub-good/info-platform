// 事件流数据适配层（M15 T127，对齐后端 EventStreamController 契约）。
// 受 JWT 保护，经 http.ts request 统一注入 Bearer、解析 {code,msg,data,traceId}。
// 错误码：30079 筛选枚举非法 / limit 越界（400）；30083 事件不存在（404，M17 T144）。

import { request } from './http';
import type { EventStreamQuery, EventStreamView, ImpactChainView } from '@/types/eventStream';

/** 事件流（四维可空筛选 + beforeId 游标 + limit 缺省 20 ≤50，越界后端拒绝不截断）。 */
export function getEvents(
  query: EventStreamQuery,
  signal?: AbortSignal,
): Promise<EventStreamView> {
  const params = new URLSearchParams();
  if (query.type) params.set('type', query.type);
  if (query.industry) params.set('industry', query.industry);
  if (query.importance) params.set('importance', query.importance);
  if (query.direction) params.set('direction', query.direction);
  if (query.beforeId != null) params.set('beforeId', String(query.beforeId));
  if (query.limit != null) params.set('limit', String(query.limit));
  const qs = params.size > 0 ? `?${params}` : '';
  return request<EventStreamView>(`/events${qs}`, { signal });
}

/** 事件影响链（M17 T144 事件详情扩展区块：HIGH 缓存直返 / MEDIUM 首展按需 / LOW 空态）。 */
export function getEventImpactChains(eventId: number, signal?: AbortSignal): Promise<ImpactChainView> {
  return request<ImpactChainView>(`/events/${eventId}/impact-chains`, { signal });
}
