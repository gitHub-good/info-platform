// 资讯源管理类型（M13 T105，对齐后端 InfoSourcesListView/InfoSourceCardView/ConnectivityTestResultView——技术方案 §4.5、UI §3）。
// sourceCode 由后端从名称生成（编排者裁定），前端不设输入框。

/** 适配通道线格式：rss / json_api / preset（html_template 预留不出现在页面）。 */
export type InfoSourceAdapterType = 'rss' | 'json_api' | 'preset';

/** 游标类型线格式（ID 数值 / TIME 时间 / NONE 纯去重）。 */
export type InfoSourceCursorType = 'ID' | 'TIME' | 'NONE';

/** 新增/编辑开放类型（预置通道不可自建，蓝图裁决 1）。 */
export type CreatableAdapterType = 'rss' | 'json_api';

/** 源配置视图（info_source.config 线格式；rss 缺省映射为空表）。 */
export interface InfoSourceConfigView {
  listPath: string | null;
  stripPrefix: string | null;
  stripSuffix: string | null;
  itemMapping: InfoSourceItemMappingView[];
  headers: Record<string, string>;
  maxItems: number | null;
  pageSize: number | null;
  cursorType: InfoSourceCursorType;
  cursorField: string | null;
}

export interface InfoSourceItemMappingView {
  source: string;
  target: string;
  transform: string;
}

/** 轮询运行态摘要（新源首抓前各时刻为 null）。 */
export interface InfoSourcePollStateView {
  lastAttemptAt: string | null;
  lastSuccessAt: string | null;
  nextDueAt: string | null;
  cursorValue: string | null;
  consecutiveFailures: number;
  backoffUntil: string | null;
  lastDurationMillis: number | null;
  lastRoundDetail: string | null;
  lastError: string | null;
}

/** 今日计数（source_daily_stats 当日行，无行全 0）。 */
export interface InfoSourceTodayCounters {
  pollCount: number;
  failCount: number;
  newCount: number;
  dupCount: number;
}

/** 源卡片视图。 */
export interface InfoSourceCardView {
  id: number;
  sourceCode: string;
  name: string;
  category: string;
  adapterType: InfoSourceAdapterType;
  adapterRef: string | null;
  endpoint: string;
  config: InfoSourceConfigView;
  intervalMinutes: number;
  enabled: boolean;
  preset: boolean;
  deleted: boolean;
  today: InfoSourceTodayCounters;
  state: InfoSourcePollStateView;
  createdAt: string | null;
  updatedAt: string | null;
}

/** 分组列表视图（active 按 category 分组 + archived 归档组）。 */
export interface InfoSourcesView {
  groups: Array<{ category: string; sources: InfoSourceCardView[] }>;
  archived: InfoSourceCardView[];
}

/** config 请求负载（轻量映射表单序列化目标；RSS 类型不带映射键）。 */
export interface InfoSourceConfigPayload {
  listPath?: string;
  stripPrefix?: string;
  itemMapping?: Array<{ source: string; target: string; transform: string }>;
  headers?: Record<string, string>;
  cursorType?: InfoSourceCursorType;
  cursorField?: string;
}

/** 新增源请求（sourceCode 后端生成；enabled 缺省 true 保存即启用）。 */
export interface CreateInfoSourceRequest {
  name: string;
  category: string;
  adapterType: CreatableAdapterType;
  endpoint: string;
  intervalMinutes: number;
  enabled?: boolean;
  config?: InfoSourceConfigPayload;
}

/** 编辑源请求（部分字段合并；adapterType 不可变）。 */
export interface UpdateInfoSourceRequest {
  name?: string;
  category?: string;
  endpoint?: string;
  intervalMinutes?: number;
  enabled?: boolean;
  config?: InfoSourceConfigPayload;
}

/** 连通性测试诊断体（失败原因在 body，200 恒返回）。 */
export interface InfoSourceConnectivityResult {
  reachable: boolean;
  robotsAllowed: boolean;
  latencyMillis: number | null;
  parsedCount: number | null;
  error: string | null;
  sampleItems: Array<{ title: string; url: string | null; publishedAt: string | null }>;
}

/** 手动抓取 202 受理回执。 */
export interface InfoSourcePollAccepted {
  sourceId: number;
  sourceCode: string;
}
