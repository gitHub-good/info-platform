// 订阅管理数据适配层（体检 P1-3，对齐后端 SubscriptionController——T27 已交付 CRUD）。
//
// - GET /api/v1/subscriptions?type=&cursor=（Bearer）：当前用户订阅游标分页（单页 20，含已退订行）。
// - POST /api/v1/subscriptions（Bearer）：订阅（幂等——已订阅直返 / 已退订重新激活，重复请求不报错不产生重复行）。
// - DELETE /api/v1/subscriptions/{id}（Bearer）：退订（软退订；不存在→30050/404，越权→30051/403，已退订幂等）。
// 渠道固定走缺省应用内（channel 不传，后端 service/entity 层兜底 IN_APP），邮件为后端预留位。

import { request } from './http';
import type { SubscriptionPage, SubscriptionView } from '@/types/subscription';

/**
 * 列出当前用户订阅（游标分页，可选按类型过滤）。
 * @param type 订阅类型 code（1~4），缺省全部类型
 * @param cursor 上一页末条 id，缺省首页
 * @throws ApiError 2001/400（type 非法）
 */
export async function listSubscriptionPage(
  options: { type?: number; cursor?: number | null; signal?: AbortSignal } = {},
): Promise<SubscriptionPage> {
  const { type, cursor, signal } = options;
  const params = new URLSearchParams();
  if (type != null) params.set('type', String(type));
  if (cursor != null) params.set('cursor', String(cursor));
  const qs = params.toString();
  const query = qs ? `?${qs}` : '';
  return request<SubscriptionPage>(`/subscriptions${query}`, { signal });
}

/**
 * 创建订阅（幂等）：已订阅直返、已退订重新激活、全新插入。
 * @param subType 订阅类型 code（1~4）
 * @param subKey 订阅键（主题/政策主题为关键词、标的为 String(subjectId)、事件类型为中文标签）
 * @throws ApiError 2001/400（subType/subKey 校验失败）
 */
export async function createSubscription(payload: {
  subType: number;
  subKey: string;
  signal?: AbortSignal;
}): Promise<SubscriptionView> {
  const { signal, ...body } = payload;
  return request<SubscriptionView>('/subscriptions', { method: 'POST', body, signal });
}

/**
 * 退订（软退订，行保留可重新激活）。
 * @throws ApiError 30050/404（不存在）、30051/403（越权）
 */
export async function unsubscribeSubscription(
  id: number,
  signal?: AbortSignal,
): Promise<void> {
  await request<void>(`/subscriptions/${id}`, { method: 'DELETE', signal });
}
