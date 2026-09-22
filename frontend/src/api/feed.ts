// 个人信息流数据适配层（T43，对齐后端 FeedController / SubscriptionController——T27 已交付）。
//
// - GET /api/v1/feed/personal?cursor=（Bearer）：订阅命中内容（公告/新闻/政策）+ 每日推荐，
//   按 publishedAt 倒序游标分页（单页 20）；FeedItem.keywords[] 为命中订阅关键词，前端高亮用。
// - GET /api/v1/subscriptions（Bearer）：当前用户订阅列表（游标分页）。
//   信息流页仅取首页判断「有无活跃订阅（status=1）」，用于区分两种空态
//   （无订阅→引导空态；有订阅无命中→muted 文案，UI 方案 §3.5 交互 5）。

import { request } from './http';
import type { FeedPage, SubscriptionSummary } from '@/types/feed';

/** GET /api/v1/feed/personal —— 个人信息流一页（cursor 为上一页末条 id；缺省首页）。 */
export async function getPersonalFeed(
  cursor?: number | null,
  signal?: AbortSignal,
): Promise<FeedPage> {
  const query = cursor != null ? `?cursor=${cursor}` : '';
  return request<FeedPage>(`/feed/personal${query}`, { signal });
}

/**
 * GET /api/v1/subscriptions —— 当前用户订阅（首页，≤20 条）。
 * 仅用于空态判别，订阅量超出首页属正常场景（不为判别翻页）。
 */
export async function listSubscriptions(signal?: AbortSignal): Promise<SubscriptionSummary[]> {
  const page = await request<{ items: SubscriptionSummary[]; nextCursor: number | null }>(
    '/subscriptions',
    { signal },
  );
  return page.items;
}
