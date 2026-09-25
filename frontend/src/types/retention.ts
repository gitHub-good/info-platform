// 留痕数据保留窗口类型（T73，对齐后端 RetentionConfigFacade / RetentionController 契约 §4.3，M10 技术方案增补）。
// 时间字段为 ISO-8601 字符串（UTC）；保存即热生效——下一轮清理按新窗口，改大窗口不恢复已删数据（REQ 裁决）。

/** 四窗口字段名（键名与 retention.global 文档字段一致）。 */
export type RetentionWindowField =
  | 'jobExecutionLogDays'
  | 'dataSourceEventDays'
  | 'llmCallLogDays'
  | 'readingEventDays';

/** 四表保留窗口（天）。 */
export interface RetentionWindows {
  jobExecutionLogDays: number;
  dataSourceEventDays: number;
  llmCallLogDays: number;
  readingEventDays: number;
}

/** 单字段护栏（GET limits 每项：下限与默认，来自后端枚举常量）。 */
export interface RetentionFieldLimits {
  min: number;
  default: number;
}

/** GET /retention/windows 响应：窗口 + 各字段下限/默认（Dialog 预填与校验提示数据源）+ updatedAt（下次防呆比对）。 */
export interface RetentionWindowsView {
  windows: RetentionWindows;
  limits: Record<RetentionWindowField, RetentionFieldLimits>;
  updatedAt: string | null;
}

/** PATCH /retention/windows 请求：四字段全量整体替换 + 可选并发防呆（不符 → 30065/409）。 */
export interface RetentionWindowsUpdate extends RetentionWindows {
  expectedUpdatedAt?: string;
}
