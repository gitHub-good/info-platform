// AI 简报数据适配层（对齐技术方案 §4.1.4 + 后端 AIBriefController）。
// 两端点受 JWT 保护（T17），经 http.ts request 自动注入 Bearer、解析 {code,msg,data,traceId}。
//
// POST /api/v1/ai-briefs：Idempotency-Key 头（业务语义键 subjectId+briefType+date 由后端服务层
//   构造，头作审计，与 watchlist.ts 同约定）；202{taskId}；成本上限→429/30030；
//   个股型标的缺失→404/30001；参数非法→400/2001。
// GET  /api/v1/ai-briefs/{taskId}：200{status,content,sourceLinks,disclaimer}；任务不存在→404/30032。

import { request } from './http';
import type {
  AiBriefView,
  BriefTypeCode,
  CreateBriefResult,
} from '@/types/aibrief';

/** 生成 Idempotency-Key：优先 crypto.randomUUID，回退 prefix+时间戳（仅审计用，不影响后端去重）。 */
function idempotencyKey(prefix: string): string {
  const uuid =
    typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : null;
  return uuid ?? `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

/**
 * 创建 AI 简报（异步受理，202 + taskId）。
 * @param subjectId 标的内部主键；每日推荐型（briefType=4）传 null
 * @throws ApiError 配额用尽(30030/429) / 标的不存在(30001/404) / 参数非法(2001/400)
 */
export async function createBrief(
  subjectId: number | null,
  briefType: BriefTypeCode,
): Promise<number> {
  const data = await request<CreateBriefResult>('/ai-briefs', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey('brief') },
    body: { subjectId, briefType },
  });
  return data.taskId;
}

/**
 * 查询 AI 简报（轮询用，每 {@link AI_BRIEF_POLL_INTERVAL_MS} 一次直到终态）。
 * @throws ApiError 任务不存在(30032/404)
 */
export async function getBrief(taskId: number, signal?: AbortSignal): Promise<AiBriefView> {
  return request<AiBriefView>(`/ai-briefs/${taskId}`, { signal });
}

/** 轮询间隔：每 2s 查询一次直到终态（§4.1.4「前端任务轮询/SSE」）。 */
export const AI_BRIEF_POLL_INTERVAL_MS = 2000;
