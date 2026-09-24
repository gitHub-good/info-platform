// Job 执行日志数据适配层（T33 + M9 页码分页契约 + 后端 JobLogController）。
// GET /api/v1/job-logs?jobName=&status=&page=&size= → JobLogPagedView
// 受 JWT 保护（T17），经 http.ts request 自动注入 Bearer、解析 {code,msg,data,traceId}。
// 错误码：2001(400) 参数校验（非法 status / size 上限）；5xxx 服务异常；
// 越界页返回 200 空列表 + 精确 total（前端按空页回退处理）。

import { request } from './http';
import type { JobExecutionStatus, JobLogPagedView } from '@/types/jobLog';

/**
 * Job 执行日志列表（页码分页 + jobName/status 过滤）。
 * @param jobName 过滤；null/空串不过滤（不发送 jobName 参数）
 * @param status 状态等值过滤（SUCCESS/FAILED/STARTED）；null = 全部（不发送 status 参数）
 * @param page 页码（1 起）
 * @param size 每页条数（仅 10/20/50，后端上限 50）
 * @param signal 可选中止信号
 * @throws ApiError 2001(400) 参数校验 / 5xxx 服务异常
 */
export async function listJobLogsPaged(
  jobName: string | null = null,
  status: JobExecutionStatus | null = null,
  page: number,
  size: number,
  signal?: AbortSignal,
): Promise<JobLogPagedView> {
  const params = new URLSearchParams();
  if (jobName && jobName.trim()) params.set('jobName', jobName.trim());
  if (status) params.set('status', status);
  params.set('page', String(page));
  params.set('size', String(size));
  return request<JobLogPagedView>(`/job-logs?${params.toString()}`, { signal });
}
