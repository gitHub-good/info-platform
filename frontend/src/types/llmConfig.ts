// LLM 模型配置页类型（T39，对齐后端 LlmConfigView/LlmConfigController 契约）。
// 时间字段为 ISO-8601 字符串（UTC）；effectiveMode 缺失时前端一律按 RESTART 渲染（UI 方案 §4.2）。

/** 生效级别：LIVE 保存即生效 / RESTART 重启后生效。 */
export type EffectiveMode = 'LIVE' | 'RESTART';

/** API key 脱敏态（明文永不出现，ADR-0018）。 */
export interface LlmApiKeyView {
  status: 'CONFIGURED' | 'NOT_SET';
  source: 'DB' | 'ENV' | null;
  last4: string | null;
}

/** llm.global 回显。 */
export interface LlmGlobalConfigView {
  timeoutSeconds: number;
  retry: number;
  dailyTokenBudgetPerUser: number;
  budgetWarnRatio: number;
  cacheDefaultTtlSeconds: number;
  cacheTtlSeconds: Record<string, number>;
  /** 只读展示项（RESTART 级，走 yml，调整需改配置重启）。 */
  cacheMaximumSize: number;
  /** 当前用户今日已用 token（预算二次确认比对口径）。 */
  todayUsedTokens: number;
  updatedAt: string | null;
  effectiveModes: Record<string, EffectiveMode>;
}

/** llm.provider.{name} 回显。 */
export interface LlmProviderConfigView {
  name: string;
  model: string;
  enabled: boolean;
  isDefault: boolean;
  fallback: string | null;
  baseUrl: string | null;
  baseUrlEffective: string;
  inputPricePerMillion: number;
  outputPricePerMillion: number;
  apiKey: LlmApiKeyView;
  updatedAt: string | null;
  effectiveModes: Record<string, EffectiveMode>;
}

/** GET /api/v1/llm-config 响应。 */
export interface LlmConfigView {
  global: LlmGlobalConfigView;
  providers: LlmProviderConfigView[];
  /** false = CONFIG_SECRET 未配置，key 写入降级（30064），页面 key 区只读。 */
  apiKeyWriteEnabled: boolean;
}

/** PATCH /llm-config/global 请求（undefined 字段不修改）。 */
export interface LlmGlobalUpdate {
  timeoutSeconds?: number;
  retry?: number;
  dailyTokenBudgetPerUser?: number;
  budgetWarnRatio?: number;
  cacheDefaultTtlSeconds?: number;
  cacheTtlSeconds?: Record<string, number>;
  expectedUpdatedAt?: string;
}

/** PUT /llm-config/providers/{name} 请求（undefined 字段不修改）。 */
export interface LlmProviderUpdate {
  model?: string;
  enabled?: boolean;
  isDefault?: boolean;
  fallback?: string;
  inputPricePerMillion?: number;
  outputPricePerMillion?: number;
  baseUrl?: string;
  expectedUpdatedAt?: string;
}

/** 连通性测试结果（ok=false 时 error 带原因摘要）。 */
export interface LlmConnectivityResult {
  ok: boolean;
  latencyMillis: number | null;
  model: string | null;
  error: string | null;
}
