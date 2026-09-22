// 任务执行中心数据适配层（T41 + 后端 JobController）。
// 受 JWT 保护，经 http.ts request 自动注入 Bearer、解析 {code,msg,data,traceId}。
// PATCH 带 expectedUpdatedAt 防并发误覆盖（不符 → 30065/409）；手动触发 202 受理、运行中 → 409/30063（防重入红线）。

import { request } from './http';
import type { JobConfigUpdate, JobTriggerResult, JobView, JobsView } from '@/types/taskCenter';

/** 拉取全部任务总览（5 任务：调度状态/上次执行/下次执行/运行中）。 */
export function getJobs(signal?: AbortSignal): Promise<JobsView> {
  return request<JobsView>('/jobs', { signal });
}

/** 更新任务调度（启停立即生效；间隔/cron 自下一调度周期生效，effectiveModes 明示）。 */
export function patchJob(jobKey: string, update: JobConfigUpdate): Promise<JobView> {
  return request<JobView>(`/jobs/${jobKey}`, {
    method: 'PATCH',
    body: update,
  });
}

/** 手动触发（异步受理 202；同任务运行中再触发抛 409/30063）。 */
export function runJob(jobKey: string): Promise<JobTriggerResult> {
  return request<JobTriggerResult>(`/jobs/${jobKey}/run`, {
    method: 'POST',
  });
}
