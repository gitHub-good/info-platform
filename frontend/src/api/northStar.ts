// 北极星数据适配层（M18 T158，对齐后端 NorthStarController——GET /api/v1/north-star 契约）。
// 受 JWT 保护，经 http.ts request 自动注入 Bearer、解析 {code,msg,data,traceId}；
// 大盘页 30 秒轮询（document.hidden 暂停）复用大盘节奏，接口幂等无游标。

import { request } from './http';
import type { NorthStarView } from '@/types/northStar';

/** 六指标一端点（只读聚合，ns-v1 口径版本串随响应下发）。 */
export function getNorthStar(signal?: AbortSignal): Promise<NorthStarView> {
  return request<NorthStarView>('/north-star', { signal });
}
