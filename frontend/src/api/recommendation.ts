// 推荐数据适配层（每日推荐 T23/T29 + 推荐中心 M16 T134/T135，对齐后端 RecommendationCardController——方案 §4.8 契约）。
// 推荐中心三面：GET /recommendations 卡片流（四维筛选 + 游标）、POST /{id}/feedback 四动作、POST /{id}/read 已读。
//
// GET /api/v1/recommendations/daily（Bearer）：返回当日盘前 Top5。**该端点为触发式**——
// 当日未生成时首次请求触发异步生成并服务端轮询至终态（LLM 3~8s，上限 30s）；
// 已生成则命中幂等缓存直返。仅用户显式点击「立即生成」时调用，页面挂载判读走只读的
// GET /feed/personal（recommendationPending 字段，readDaily 只读语义不触发生成）。

import { request } from './http';
import type {
  DailyRecommendationView,
  RecommendationFeedbackAction,
  RecommendationFeedbackResult,
  RecommendationListView,
} from '@/types/recommendation';

/** 推荐中心卡片流查询（空值 = 不过滤；beforeId 游标 + limit 缺省 20 ≤50）。 */
export interface RecommendationCenterQuery {
  level?: string;
  eventType?: string;
  direction?: string;
  beforeId?: number;
  limit?: number;
}

/** 推荐中心卡片流（GET /api/v1/recommendations）。 */
export function getRecommendationCards(
  query: RecommendationCenterQuery,
  signal?: AbortSignal,
): Promise<RecommendationListView> {
  const params = new URLSearchParams();
  if (query.level) params.set('level', query.level);
  if (query.eventType) params.set('eventType', query.eventType);
  if (query.direction) params.set('direction', query.direction);
  if (query.beforeId != null) params.set('beforeId', String(query.beforeId));
  if (query.limit != null) params.set('limit', String(query.limit));
  const qs = params.size > 0 ? `?${params}` : '';
  return request<RecommendationListView>(`/recommendations${qs}`, { signal });
}

/**
 * 四动作反馈（POST /api/v1/recommendations/{id}/feedback）。
 * @throws ApiError 30080 卡片不存在/非本人（404）、30081 action 非法或 subjectCode 缺失（400）
 */
export function postRecommendationFeedback(
  cardId: number,
  action: RecommendationFeedbackAction,
  subjectCode?: string,
): Promise<RecommendationFeedbackResult> {
  return request<RecommendationFeedbackResult>(`/recommendations/${cardId}/feedback`, {
    method: 'POST',
    body: subjectCode ? { action, subjectCode } : { action },
  });
}

/** 已读 + 隐式采纳（POST /api/v1/recommendations/{id}/read；幂等 200 直返；fire-and-forget 用）。 */
export function markRecommendationRead(cardId: number): Promise<void> {
  return request(`/recommendations/${cardId}/read`, { method: 'POST' });
}

/**
 * 获取（并在未生成时触发）当日每日推荐 Top5。
 * @throws ApiError 5xxx 服务异常 / 401 跳登录（http 层统一）
 */
export async function getDailyRecommendation(signal?: AbortSignal): Promise<DailyRecommendationView> {
  return request<DailyRecommendationView>('/recommendations/daily', { signal });
}
