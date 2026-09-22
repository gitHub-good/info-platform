// 政策时事流数据适配层（对齐技术方案 §4.1.5 + 后端 PolicyController）。
// 两端点受 JWT 保护（T17），经 http.ts request 自动注入 Bearer、解析 {code,msg,data,traceId}。
//
// GET /api/v1/policies?days=7&industry=&cursor=  → PolicyListView（游标分页，每页 20）
// GET /api/v1/policies/{id}                       → PolicyDetailView（含关联自选标的 + aiTendency）
// 错误码：30040 政策条目不存在（404，GlobalExceptionHandler 映射）。

import { request } from './http';
import type { PolicyDetailView, PolicyListView } from '@/types/policy';

/** 时间窗默认值（最近 7 天，对齐 §4.1.5）。 */
export const DEFAULT_POLICY_DAYS = 7;

/**
 * 政策列表（游标分页 + 行业过滤）。
 * @param days 时间窗（天），默认 7
 * @param industry 行业过滤；null/空串不过滤（不发送 industry 参数）
 * @param cursor 游标（上一页末条 id）；null 表首页
 * @param signal 可选中止信号（切换行业过滤时取消在途请求）
 * @throws ApiError 30040(404) 条目不存在 / 5xxx 服务异常
 */
export async function listPolicies(
  days: number = DEFAULT_POLICY_DAYS,
  industry: string | null = null,
  cursor: number | null = null,
  signal?: AbortSignal,
): Promise<PolicyListView> {
  const params = new URLSearchParams();
  params.set('days', String(days));
  if (industry && industry.trim()) params.set('industry', industry.trim());
  if (cursor != null && cursor > 0) params.set('cursor', String(cursor));
  return request<PolicyListView>(`/policies?${params.toString()}`, { signal });
}

/**
 * 政策详情 + 关联自选标的 + aiTendency。
 * @throws ApiError 30040(404) 政策条目不存在
 */
export async function getPolicy(id: number, signal?: AbortSignal): Promise<PolicyDetailView> {
  return request<PolicyDetailView>(`/policies/${id}`, { signal });
}
