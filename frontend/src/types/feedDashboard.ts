// 抓取大盘类型（M14 T116，对齐后端 FeedDashboardView——GET /api/v1/feed-dashboard 三区块契约）。
// 感知延迟口径 basis 由后端版本化（incremental-only-v1：排除每源首日回灌 + 排除日粒度源，ADR-0045）。

/** 全局统计卡（今日入库/去重拦截/活跃源/失败源 + 感知延迟）。 */
export interface FeedDashboardGlobal {
  todayNewCount: number;
  todayDupCount: number;
  activeSourceCount: number;
  failedSourceCount: number;
  latency: FeedDashboardLatency;
}

/** 感知延迟分布（无样本 p50/p90 为 null 与 0 可区分）。 */
export interface FeedDashboardLatency {
  p50Millis: number | null;
  p90Millis: number | null;
  sampleCount: number;
  basis: string;
  excludedSourceCodes: string[];
}

/** 源维度表行（异常置顶；含停用/归档行，前端默认折叠切换）。 */
export interface FeedDashboardSourceRow {
  sourceId: number;
  sourceCode: string;
  name: string;
  category: string;
  adapterType: 'rss' | 'json_api' | 'preset' | 'html_template';
  intervalMinutes: number;
  enabled: boolean;
  preset: boolean;
  deleted: boolean;
  todayPollCount: number;
  todayNewCount: number;
  todayFailCount: number;
  todayDupCount: number;
  totalCount: number;
  lastAttemptAt: string | null;
  lastSuccessAt: string | null;
  nextDueAt: string | null;
  backoffUntil: string | null;
  consecutiveFailures: number;
  lastError: string | null;
  lastRoundDetail: string | null;
  runState: 'ok' | 'fail' | 'backoff' | 'disabled' | 'pending';
  abnormal: boolean;
}

/** 近期失败列表行（origin：event=旁路事件 / state=运行态现态）。 */
export interface FeedDashboardFailure {
  sourceCode: string;
  sourceName: string;
  occurredAt: string;
  errorSummary: string;
  origin: 'event' | 'state';
}

/** 大盘三区块视图。 */
export interface FeedDashboardView {
  global: FeedDashboardGlobal;
  sources: FeedDashboardSourceRow[];
  failures: FeedDashboardFailure[];
}
