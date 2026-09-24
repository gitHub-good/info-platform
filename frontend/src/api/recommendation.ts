// 每日推荐数据适配层（体检 P1-4，对齐后端 RecommendationController——T23/T29 已交付）。
//
// GET /api/v1/recommendations/daily（Bearer）：返回当日盘前 Top5。**该端点为触发式**——
// 当日未生成时首次请求触发异步生成并服务端轮询至终态（LLM 3~8s，上限 30s）；
// 已生成则命中幂等缓存直返。仅用户显式点击「立即生成」时调用，页面挂载判读走只读的
// GET /feed/personal（recommendationPending 字段，readDaily 只读语义不触发生成）。

import { request } from './http';
import type { DailyRecommendationView } from '@/types/recommendation';

/**
 * 获取（并在未生成时触发）当日每日推荐 Top5。
 * @throws ApiError 5xxx 服务异常 / 401 跳登录（http 层统一）
 */
export async function getDailyRecommendation(signal?: AbortSignal): Promise<DailyRecommendationView> {
  return request<DailyRecommendationView>('/recommendations/daily', { signal });
}
