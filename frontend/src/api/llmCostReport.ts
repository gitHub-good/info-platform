// LLM 成本报表数据适配层（T30 + 后端 LlmCostReportController）。
// GET /api/v1/llm-cost-report?window=（缺省 7d）→ LlmCostReport
// 受 JWT 保护（T17），经 http.ts request 自动注入 Bearer、解析 {code,msg,data,traceId}。

import { request } from './http';
import type { LlmCostReport, LlmCostWindow } from '@/types/llmCostReport';

/**
 * 拉取成本报表。
 * @param window 时间窗 today/7d/30d；null 走后端缺省 7d
 * @param signal 可选中止信号
 * @throws ApiError 2001 非法窗口 / 5xxx 服务异常
 */
export async function getLlmCostReport(
  window: LlmCostWindow | null = null,
  signal?: AbortSignal,
): Promise<LlmCostReport> {
  const params = new URLSearchParams();
  if (window) params.set('window', window);
  const query = params.toString();
  return request<LlmCostReport>(`/llm-cost-report${query ? `?${query}` : ''}`, { signal });
}
