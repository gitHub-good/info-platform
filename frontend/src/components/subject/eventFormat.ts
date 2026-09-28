// 事件分区展示格式化（详情页 EventSection 与详情弹框共用）：
// 异动类型枚举 → 中文标签、触发时刻 ISO → 本地时间串。独立成模块避免组件文件混出非组件导出
// （破坏 React fast refresh 边界）。

/** 异动类型展示标签（anomaly_event.anomaly_type 枚举名 → 中文） */
const ANOMALY_TYPE_LABEL: Record<string, string> = {
  PRICE_CHANGE: '涨跌幅异动',
  VOLUME: '量异动',
  EVENT: '事件',
};

/** 异动类型 → 中文标签（未知枚举原样透出，便于排查） */
export function typeLabel(anomalyType: string): string {
  return ANOMALY_TYPE_LABEL[anomalyType] ?? anomalyType;
}

/** 触发时刻（ISO-8601 UTC）→ 本地时间串展示 */
export function formatTriggerTime(triggerTime: string): string {
  const date = new Date(triggerTime);
  if (Number.isNaN(date.getTime())) return triggerTime;
  return date.toLocaleString('zh-CN', { hour12: false });
}
