// 通知中心类型（P1-1 体检项：SSE 推送前端消费）。
// - SSE 实时事件与 history 端点记录归一为 NotificationItem，供面板统一渲染。
// - 事件名对齐技术方案 §4.1.3：anomaly / event / policy / ai_brief / daily_recommend。

/** SSE 事件名（后端 PushType.eventName，对齐 §4.1.3）。 */
export const NOTIFICATION_EVENT_TYPES = [
  'anomaly',
  'event',
  'policy',
  'ai_brief',
  'daily_recommend',
] as const;

/** SSE data 载荷（后端 NotificationEvent 序列化 JSON）。 */
export interface PushEventPayload {
  type: string;
  subjectId: number | null;
  /** 标的内部统一代码（P1-1 契约增量；标的已删等边缘为 null） */
  subjectCode: string | null;
  refId: string | null;
  content: string;
}

/** history 端点返回项（后端 NotificationView）。 */
export interface NotificationHistoryItem {
  id: number;
  type: string;
  subjectId: number | null;
  subjectCode: string | null;
  refId: string | null;
  content: string;
  status: number;
  pushedAt: string | null;
  createdAt: string | null;
}

/** history 端点返回结构（后端 NotificationHistory）。 */
export interface NotificationHistoryData {
  items: NotificationHistoryItem[];
  nextCursor: number | null;
}

/** 面板统一条目（SSE 实时 + history 兜底归一）。 */
export interface NotificationItem {
  /** push_record.id（SSE 来自事件 id 字段，history 来自记录 id）；用于去重与已读水位 */
  id: number;
  type: string;
  subjectId: number | null;
  subjectCode: string | null;
  refId: string | null;
  content: string;
  /** ISO 时间：history=createdAt，SSE=客户端接收时刻 */
  time: string | null;
}

/** SSE 连接三态（面板内指示）。 */
export type ConnectionStatus = 'connecting' | 'online' | 'offline';

/** 事件名 → 面板类型徽章文案。 */
export const TYPE_LABELS: Record<string, string> = {
  anomaly: '异动',
  event: '事件',
  policy: '政策',
  ai_brief: 'AI 简报',
  daily_recommend: '每日推荐',
};
