// 通知中心全局 Provider（P1-1）：登录后由 App 挂载一份，SSE 连接与通知状态全站共享。
// 无 Provider 时（如 AppLayout 独立测试渲染）useNotificationCenter() 返回离线空态——
// 布局保持纯展示语义，不因缺 Provider 抛错。

import { createContext, useContext, type ReactNode } from 'react';
import { useNotificationStream, type NotificationStreamState } from '@/hooks/useNotificationStream';

/** 无 Provider 时的离线空态（只读、动作 no-op）。 */
const idleState: NotificationStreamState = {
  items: [],
  unreadCount: 0,
  lastReadId: 0,
  status: 'offline',
  historyLoading: false,
  historyError: null,
  refreshHistory: () => Promise.resolve(),
  markItemRead: () => undefined,
  markAllRead: () => undefined,
  clearAll: () => undefined,
};

const NotificationContext = createContext<NotificationStreamState>(idleState);

/** 通知中心状态消费者（铃铛/面板用；无 Provider 时返回离线空态）。 */
export function useNotificationCenter(): NotificationStreamState {
  return useContext(NotificationContext);
}

/** 挂载 SSE 通知流并向下提供状态；仅登录态渲染（App 登录守卫保证）。 */
export function NotificationProvider({ children }: { children: ReactNode }) {
  const state = useNotificationStream();
  return <NotificationContext.Provider value={state}>{children}</NotificationContext.Provider>;
}
