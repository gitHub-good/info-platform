// Job 执行日志数据适配层（T33 + 后端 JobLogController）。
// GET /api/v1/job-logs?jobName=&cursor=（游标分页，每页 20）→ JobLogPage
// 受 JWT 保护（T17），经 http.ts request 自动注入 Bearer、解析 {code,msg,data,traceId}。

import { request } from './http';
import type { JobLogPage } from '@/types/jobLog';

/**
 * Job 执行日志列表（游标分页 + jobName 过滤）。
 * @param jobName 过滤；null/空串不过滤（不发送 jobName 参数）
 * @param cursor 游标（上一页末条 id）；null 表首页
 * @param signal 可选中止信号
 * @throws ApiError 5xxx 服务异常
 */
export async function listJobLogs(
  jobName: string | null = null,
  cursor: number | null = null,
  signal?: AbortSignal,
): Promise<JobLogPage> {
  const params = new URLSearchParams();
  if (jobName && jobName.trim()) params.set('jobName', jobName.trim());
  if (cursor != null && cursor > 0) params.set('cursor', String(cursor));
  return request<JobLogPage>(`/job-logs?${params.toString()}`, { signal });
}
