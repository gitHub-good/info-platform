// 通知数据适配层（P1-1 通知中心）。
// - history 兜底拉取走统一 request()：自动注入 Bearer、401 清 token 跳登录。
// - SSE 长连接不走本层（EventSource 无法携带请求头，由 hooks/useNotificationStream
//   以 access_token 查询参数握手——对齐技术方案 §4.1「token 走 query 或 header」与后端 JwtAuthFilter 的 SSE 例外）。

import { request } from '@/api/http';
import type { NotificationHistoryData } from '@/types/notification';

/** 面板/重连兜底单次拉取条数（后端 latest 上限 50，取 20 对齐 history 单页）。 */
export const LATEST_NOTIFICATION_COUNT = 20;

/**
 * 拉取最近 N 条通知（GET /notifications?latest=20，单次语义 nextCursor 恒 null）。
 * 面板打开补全近期记录 / SSE 重连后补拉断线期间丢失的推送。
 * @throws ApiError 网络/服务异常（面板展示重试）
 */
export function fetchLatestNotifications(signal?: AbortSignal): Promise<NotificationHistoryData> {
  return request<NotificationHistoryData>(`/notifications?latest=${LATEST_NOTIFICATION_COUNT}`, {
    signal,
  });
}
