// 抓取大盘数据适配层（M14 T116，对齐后端 FeedDashboardController——GET /api/v1/feed-dashboard 契约）。
// 受 JWT 保护，经 http.ts request 自动注入 Bearer、解析 {code,msg,data,traceId}；
// 页面 30 秒轮询（document.hidden 暂停）+ 手动刷新，接口幂等无游标。

import { request } from './http';
import type { FeedDashboardView } from '@/types/feedDashboard';

/** 三区块一端点（global 全局统计 / sources 源维度表 / failures 近期失败列表；只读聚合）。 */
export function getFeedDashboard(signal?: AbortSignal): Promise<FeedDashboardView> {
  return request<FeedDashboardView>('/feed-dashboard', { signal });
}
