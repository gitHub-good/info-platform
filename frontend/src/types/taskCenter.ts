// 任务执行中心类型（T41，对齐后端 JobCenterFacade / JobController 契约 §4.4.3）。
// 时间字段为 ISO-8601 字符串（UTC）；生效级别含任务域专用 LIVE_NEXT_CYCLE（间隔/cron/userIds 自下一调度周期生效，
// ADR-0017），缺省按重启生效渲染的规则见 EffectBadge/local 渲染分支。

/** 任务域生效级别：LIVE 保存即生效（启停立即）/ LIVE_NEXT_CYCLE 自下一调度周期生效 / RESTART 重启后生效。 */
export type JobEffectiveMode = 'LIVE' | 'LIVE_NEXT_CYCLE' | 'RESTART';

/** 上次执行摘要（从未执行为 null，页面「从未执行」空态，PRD 场景 4.5）。 */
export interface JobLastExecutionView {
  /** SUCCESS / FAILED / STARTED（运行中） */
  status: string;
  startTime: string;
  /** STARTED（进行中）时为 null。 */
  endTime: string | null;
  durationMillis: number | null;
}

/** 单任务总览行（GET /api/v1/jobs 每项）。 */
export interface JobView {
  jobKey: string;
  /** 留痕名（历史跳转 #/job-logs?jobName= 的过滤口径）。 */
  jobName: string;
  /** 展示名（如「政策抓取」）。 */
  name: string;
  description: string;
  scheduleType: 'FIXED_DELAY' | 'CRON';
  /** 间隔型任务的调度间隔（毫秒）；cron 型为 null。 */
  intervalMillis: number | null;
  /** cron 型任务的表达式；间隔型为 null。 */
  cron: string | null;
  /** 逗号分隔用户 id（仅 DAILY_RECOMMEND 使用）。 */
  userIds: string;
  enabled: boolean;
  running: boolean;
  lastExecution: JobLastExecutionView | null;
  /** 停用或未注册时为 null。 */
  nextExecutionTime: string | null;
  updatedAt: string | null;
  effectiveModes: Record<string, JobEffectiveMode>;
}

/** GET /api/v1/jobs 响应。 */
export interface JobsView {
  jobs: JobView[];
}

/** PATCH /api/v1/jobs/{jobKey} 请求（undefined 字段不修改）。 */
export interface JobConfigUpdate {
  enabled?: boolean;
  intervalMillis?: number;
  cron?: string;
  userIds?: string;
  expectedUpdatedAt?: string;
}

/** POST /api/v1/jobs/{jobKey}/run 202 受理结果。 */
export interface JobTriggerResult {
  /** job_execution_log 落痕 id（轮询任务列表或 Job 日志看状态流转）。 */
  executionId: number;
  status: string;
}
