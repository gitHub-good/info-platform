// 数据源配置页类型（T40，对齐后端 DataSourceConfigFacade / DataSourceConfigController 契约）。
// 时间字段为 ISO-8601 字符串（UTC）；数据源参数全部 LIVE 级（方案 §4.2），effectiveMode 缺失按 RESTART 渲染（UI 方案 §4.2）。

import type { EffectiveMode } from '@/types/llmConfig';

/** 运行模式：REAL 真实外呼 / MOCK 本地模拟。 */
export type DataSourceMode = 'REAL' | 'MOCK';

/** 健康徽章数据：最近一条 data_source_event（含 OK 心跳）+ 24h 异常计数。 */
export interface DataSourceHealthView {
  /** OK/MISSING/TIMEOUT/ERROR/LIMITED；null = 从未抓取（「暂无抓取记录」空态） */
  lastEventType: string | null;
  lastEventAt: string | null;
  errors24h: number;
}

/** 单源卡片回显。 */
export interface DataSourceCardView {
  sourceCode: string;
  label: string;
  enabled: boolean;
  mode: DataSourceMode;
  timeoutMillis: number;
  retries: number;
  cacheTtlSeconds: number;
  /** 降级链（ADR-0033）：有序 provider，首元素 = 主源；已按旧 backupSource 折算/代码默认链兜底后的有效链 */
  fallbackChain: string[];
  /** 可用 provider 注册表（代码事实，页面降级链编辑依据；长度 ≤1 显示「暂无备选源」） */
  availableProviders: string[];
  /** 各源自由参数（URL / 条数 / referer 等，键空间按源不同） */
  params: Record<string, string | number>;
  health: DataSourceHealthView;
  updatedAt: string | null;
  effectiveModes: Record<string, EffectiveMode>;
}

/** 聚合总超时条回显。 */
export interface AggregationGlobalView {
  detailTimeoutMillis: number;
  updatedAt: string | null;
  effectiveModes: Record<string, EffectiveMode>;
}

/** GET /api/v1/datasource-configs 响应。 */
export interface DataSourceConfigView {
  sources: DataSourceCardView[];
  aggregation: AggregationGlobalView;
}

/** PATCH /datasource-configs/{sourceCode} 请求（undefined 字段不修改；params 提供即整体替换）。 */
export interface DataSourceConfigUpdate {
  enabled?: boolean;
  mode?: DataSourceMode;
  timeoutMillis?: number;
  retries?: number;
  cacheTtlSeconds?: number;
  /** 降级链（ADR-0033）：提供即写入（空数组合法 = 仅主源），保存后下一次取数生效 */
  fallbackChain?: string[];
  params?: Record<string, string | number>;
  expectedUpdatedAt?: string;
}

/** PATCH /datasource-configs/aggregation/global 请求。 */
export interface AggregationGlobalUpdate {
  detailTimeoutMillis?: number;
  expectedUpdatedAt?: string;
}

/** 连通性测试结果（ok=false 时 itemCount 为 null；mock 模式带 note 说明）。 */
export interface DataSourceConnectivityResult {
  ok: boolean;
  latencyMillis: number | null;
  itemCount: number | null;
  mode: string;
  error: string | null;
  note: string | null;
}
