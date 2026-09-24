// 政策时事流数据适配层（对齐技术方案 §4.1.5 + M9 页码分页契约 + 后端 PolicyController）。
// 两端点受 JWT 保护（T17），经 http.ts request 自动注入 Bearer、解析 {code,msg,data,traceId}。
//
// GET /api/v1/policies?days=&industry=&keyword=&page=&size= → PolicyPagedView（页码分页）
// GET /api/v1/policies/{id}                                 → PolicyDetailView（含关联自选标的 + aiTendency）
// 错误码：30040 政策条目不存在（404）；2001 参数校验（keyword 长度/size 上限，400）；
// 5xxx 服务异常。越界页返回 200 空列表 + 精确 total（前端按空页回退处理）。

import { request } from './http';
import type { PolicyDetailView, PolicyPagedView } from '@/types/policy';

/** 时间窗默认值（最近 7 天，对齐 §4.1.5）。 */
export const DEFAULT_POLICY_DAYS = 7;

/** 页码模式查询参数（对象收拢，多参不散摆）。 */
export interface PolicyPagedQuery {
  days?: number;
  /** 行业过滤；null/空串不过滤（不发送 industry 参数）。 */
  industry?: string | null;
  /** 关键词（标题/摘要模糊）；null/空串不过滤。前端已按 ≥2 字符拦截。 */
  keyword?: string | null;
  page: number;
  size: number;
}

/**
 * 政策列表（页码分页 + 行业/关键词过滤）。
 * @param query 查询参数（page 1 起；size 仅 10/20/50，后端上限 50）
 * @param signal 可选中止信号（筛选变更/翻页互斥时取消在途请求）
 * @throws ApiError 2001(400) 参数校验 / 30040(404) 条目不存在 / 5xxx 服务异常
 */
export async function listPoliciesPaged(
  query: PolicyPagedQuery,
  signal?: AbortSignal,
): Promise<PolicyPagedView> {
  const params = new URLSearchParams();
  params.set('days', String(query.days ?? DEFAULT_POLICY_DAYS));
  if (query.industry && query.industry.trim()) {
    params.set('industry', query.industry.trim());
  }
  if (query.keyword && query.keyword.trim()) {
    params.set('keyword', query.keyword.trim());
  }
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  return request<PolicyPagedView>(`/policies?${params.toString()}`, { signal });
}

/**
 * 政策详情 + 关联自选标的 + aiTendency。
 * @throws ApiError 30040(404) 政策条目不存在
 */
export async function getPolicy(id: number, signal?: AbortSignal): Promise<PolicyDetailView> {
  return request<PolicyDetailView>(`/policies/${id}`, { signal });
}
