// LLM 成本报表类型（对齐后端 record：LlmCostReport，T30）。
//
// GET /api/v1/llm-cost-report?window=today|7d|30d → LlmCostReport
// 受 JWT 保护（T17），经 http.ts request 自动注入 Bearer、解析 {code,msg,data,traceId}。

/** 时间窗键（后端缺省 7d，非法值 400/2001）。 */
export type LlmCostWindow = 'today' | '7d' | '30d';

/** 用户当日预算状态（与后端 LlmBudgetStatus 枚举名一致）。 */
export type LlmBudgetStatus = 'NORMAL' | 'WARNING' | 'EXHAUSTED';

/** provider 维度成本分布（provider=null 的拒绝/失败行归「未发起」）。 */
export interface LlmProviderCost {
  provider: string;
  calls: number;
  successCalls: number;
  failedCalls: number;
  totalTokens: number;
  costMicros: number;
}

/** 场景维度成本分布（scene = briefTypeKey："1" 个股 "2" 事件 "3" 政策 "4" 每日推荐）。 */
export interface LlmSceneCost {
  scene: string;
  calls: number;
  totalTokens: number;
  costMicros: number;
}

/** 今日用户预算余量与告警状态。 */
export interface LlmUserBudget {
  userId: number;
  usedTokens: number;
  budgetTokens: number;
  remainingTokens: number;
  status: LlmBudgetStatus;
}

/** 成本报表视图（windowStart 为 ISO-8601 整秒文本，UTC）。 */
export interface LlmCostReport {
  window: LlmCostWindow;
  windowStart: string;
  totalCalls: number;
  successCalls: number;
  failedCalls: number;
  rejectedCalls: number;
  cacheHits: number;
  /** 0~1，四位小数；成功/(成功+失败)，拒绝不入分母；空窗口为 0。 */
  successRate: number;
  /** 0~1，四位小数；缓存命中/总调用。 */
  cacheHitRate: number;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  /** 估算总成本（微元 = 百万分之一元）。 */
  costMicros: number;
  dailyBudgetTokens: number;
  budgetWarnRatio: number;
  providers: LlmProviderCost[];
  scenes: LlmSceneCost[];
  topUserBudgets: LlmUserBudget[];
}
