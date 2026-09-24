// Job 执行日志类型（对齐后端 record：JobLogView / JobLogPagedView）。
//
// GET /api/v1/job-logs?jobName=&status=&page=&size= → JobLogPagedView（页码分页）
// 受 JWT 保护（T17），经 http.ts request 自动注入 Bearer、解析 {code,msg,data,traceId}。

/** Job 执行状态（对齐后端 JobExecutionStatus 枚举名）。 */
export type JobExecutionStatus = 'STARTED' | 'SUCCESS' | 'FAILED';

/** Job 执行日志视图（GET /job-logs 每条）。startTime/endTime 为 ISO-8601 整秒文本（UTC）。 */
export interface JobLogView {
  id: number;
  jobName: string;
  startTime: string;
  /** endTime 在 STARTED 时为 null。 */
  endTime: string | null;
  status: JobExecutionStatus;
  /** durationMillis 在 STARTED 时为 null。 */
  durationMillis: number | null;
  processedCount: number;
  errorCount: number;
  /** errorMessage 在非 FAILED 时为 null。 */
  errorMessage: string | null;
}

/** Job 执行日志页码分页视图（M9）。total 为筛选后精确总数。 */
export interface JobLogPagedView {
  items: JobLogView[];
  total: number;
  page: number;
  size: number;
}
