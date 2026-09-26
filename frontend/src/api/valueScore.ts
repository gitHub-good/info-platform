// 价值评分数据适配层（M20 T172 + 后端 ValueScoreController 契约 §4.7.3）。
// 受 JWT 保护，经 http.ts request 自动注入 Bearer、解析 {code,msg,data,traceId}。
// PATCH 13 字段全量替换，带 expectedUpdatedAt 防并发误覆盖（不符 → 30065/409）；非法值 30087 字段级、原值保留。

import { request } from './http';
import type { ScoreWeightsUpdate, ScoreWeightsView, ValueScoreView } from '@/types/valueScore';

/** 拉取当前权重与阈值视图（任务中心 FACTOR_SNAPSHOT 编辑 Dialog 预填数据源）。 */
export function getScoreWeights(signal?: AbortSignal): Promise<ScoreWeightsView> {
  return request<ScoreWeightsView>('/value-scores/weights', { signal });
}

/** 全量替换权重与阈值（保存即热生效——下一轮 17:30 快照按新参数与 basis 计算）。 */
export function patchScoreWeights(update: ScoreWeightsUpdate): Promise<ScoreWeightsView> {
  return request<ScoreWeightsView>('/value-scores/weights', {
    method: 'PATCH',
    body: update,
  });
}

/** 拉取标的价值评分（最新快照；无快照 → 404/30086 由调用方渲染空态引导）。 */
export function getSubjectValueScore(
  subjectId: number,
  signal?: AbortSignal,
): Promise<ValueScoreView> {
  return request<ValueScoreView>(`/subjects/${subjectId}/value-score`, { signal });
}
