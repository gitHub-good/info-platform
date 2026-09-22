// 概览仪表盘类型（对齐后端 record：OverviewService.OverviewView，T42 契约 §4.4.4）。
//
// GET /api/v1/overview → OverviewView（五卡片聚合）
// 受 JWT 保护，经 http.ts request 自动注入 Bearer、解析 {code,msg,data,traceId}。

/** 成本水位状态（契约 §4.4.4 三态；OK 对应成本报表 NORMAL 的同一阈值语义）。 */
export type OverviewLlmStatus = 'OK' | 'WARNING' | 'EXHAUSTED';

/** 成本水位卡：今日已用 token / 成本（微元）/ 运行时当前预算。error 非空 = 该卡取数失败。 */
export interface OverviewLlmToday {
  tokenUsed: number;
  costMicros: number;
  budgetTokens: number;
  status: OverviewLlmStatus;
  error: string | null;
}

/** 今日异动卡（今日日界计数）。 */
export interface OverviewAnomalyToday {
  count: number;
  error: string | null;
}

/** 政策动态最新条目（id/title/publishedAt，入库时间滚动 24h 窗口）。 */
export interface OverviewPolicyLatest {
  id: number;
  title: string;
  publishedAt: string | null;
}

/** 政策动态卡（滚动 24h 计数 + 最新 5 条）。 */
export interface OverviewPolicy24h {
  count: number;
  latest: OverviewPolicyLatest[];
  error: string | null;
}

/** 任务健康卡（滚动 24h 执行数/失败数 + 不健康任务 jobKey）。 */
export interface OverviewJobHealth {
  windowRuns: number;
  windowFailed: number;
  unhealthyJobs: string[];
  error: string | null;
}

/** 数据源健康项（最近一条事件含 OK 心跳 + 24h 异常计数 + 运行时模式）。 */
export interface OverviewSourceHealth {
  sourceCode: string;
  mode: string;
  lastEventType: string | null;
  lastEventAt: string | null;
  errors24h: number;
}

/** 概览聚合视图（sourceHealth 为契约裸数组，其卡级错误置兄弟字段 sourceHealthError）。 */
export interface OverviewView {
  llmToday: OverviewLlmToday;
  anomalyToday: OverviewAnomalyToday;
  policy24h: OverviewPolicy24h;
  jobHealth: OverviewJobHealth;
  sourceHealth: OverviewSourceHealth[];
  sourceHealthError: string | null;
}
