// 订阅管理类型（体检 P1-3，对齐后端 SubscriptionView/SubscriptionListView——技术方案 §4.1.6）。
//
// subType/channel/status 为后端枚举 code（领域枚举不透出字符串名，前端按 code 渲染）：
// - subType：1 主题 TOPIC / 2 标的 SUBJECT / 3 事件类型 EVENT_TYPE / 4 政策主题 POLICY_THEME
// - channel：1 应用内 IN_APP / 2 邮件 EMAIL（预留）
// - status：1 订阅中 SUBSCRIBED / 0 已退订 UNSUBSCRIBED（软退订，行保留）

/** 订阅条目（GET/POST /api/v1/subscriptions 响应；契约无 createdAt，创建时间不展示）。 */
export interface SubscriptionView {
  id: number;
  subType: number;
  subKey: string;
  channel: number;
  status: number;
}

/** 订阅列表一页（游标分页；nextCursor 为 null 表示无下一页）。 */
export interface SubscriptionPage {
  items: SubscriptionView[];
  nextCursor: number | null;
}

/** 订阅类型 code（对齐后端 SubscriptionType）。 */
export const SUB_TYPE = {
  TOPIC: 1,
  SUBJECT: 2,
  EVENT_TYPE: 3,
  POLICY_THEME: 4,
} as const;

/** 订阅类型徽章元数据（label + 低饱和底亮字配色，色板对齐信息流 TYPE_META 惯例）。 */
export const SUB_TYPE_META: Record<
  number,
  { label: string; className: string }
> = {
  [SUB_TYPE.TOPIC]: { label: '主题', className: 'bg-sky-500/15 text-sky-400' },
  [SUB_TYPE.SUBJECT]: { label: '标的', className: 'bg-emerald-500/15 text-emerald-400' },
  [SUB_TYPE.EVENT_TYPE]: { label: '事件类型', className: 'bg-amber-500/15 text-amber-400' },
  [SUB_TYPE.POLICY_THEME]: { label: '政策主题', className: 'bg-violet-500/15 text-violet-300' },
};

/** 推送渠道 code → 展示名（邮件为后端预留位）。 */
export const CHANNEL_LABELS: Record<number, string> = {
  1: '应用内',
  2: '邮件（预留）',
};

/**
 * 事件类型订阅可选项（subKey 取中文标签）。
 * 后端 FeedMatcher.eventTypeMatch 按「subKey 含内容类型中文标签（公告/新闻/政策）或等于枚举名」
 * 命中——中文标签即有效 subKey，且列表展示无需二次映射。
 */
export const EVENT_TYPE_OPTIONS: ReadonlyArray<{ value: string; label: string }> = [
  { value: '公告', label: '公告' },
  { value: '新闻', label: '新闻' },
  { value: '政策', label: '政策' },
];
