// SSE 通知流生命周期管理（P1-1 通知中心核心）。
// 生命周期：登录后（Provider 挂载即有 token）建立 EventSource → 命名事件入列 →
// onerror 关闭旧连接并按指数退避封顶重连（重连成功后 history 兜底补拉丢失推送）→
// 登出/令牌清除时 Provider 卸载即断开（App 登录守卫保证无 token 不渲染 Provider）。
// 注意：不监听 visibilitychange——document.hidden 时保持连接（推送不能只在可见时工作），
// SSE 心跳帧由后端周期下发（comment 帧不触发 onmessage，不污染事件流）。

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { API_BASE_URL, getToken } from '@/api/http';
import { fetchLatestNotifications } from '@/api/notification';
import type { ConnectionStatus, NotificationItem, PushEventPayload } from '@/types/notification';
import { NOTIFICATION_EVENT_TYPES } from '@/types/notification';

/** 重连基础退避（1s 起步，指数翻倍）。 */
const RECONNECT_BASE_DELAY_MS = 1000;

/** 重连退避封顶（30s；PRD「异动 15 秒知道」由后端检测链路保证，此处为断线恢复节流）。 */
const MAX_RECONNECT_DELAY_MS = 30_000;

/** 面板保留的最大条目数（超出丢弃最旧，防长会话内存增长）。 */
const MAX_ITEMS = 50;

/** 已读水位 localStorage 键（个人单用户平台，全局一水位）。 */
const LAST_READ_KEY = 'notifications_last_read_id';

function readLastReadId(): number {
  try {
    return Number(localStorage.getItem(LAST_READ_KEY)) || 0;
  } catch {
    return 0;
  }
}

function persistLastReadId(id: number): void {
  try {
    localStorage.setItem(LAST_READ_KEY, String(id));
  } catch {
    // 隐私模式 / SSR：静默忽略，未读水位退化为会话内
  }
}

/** 归并条目：按 id 去重降序，超出上限丢最旧。 */
function mergeItems(prev: NotificationItem[], incoming: NotificationItem[]): NotificationItem[] {
  const byId = new Map<number, NotificationItem>();
  for (const item of [...prev, ...incoming]) {
    byId.set(item.id, item); // 后写覆盖（history 与 SSE 同 id 以新数据为准）
  }
  return [...byId.values()].sort((a, b) => b.id - a.id).slice(0, MAX_ITEMS);
}

export interface NotificationStreamState {
  items: NotificationItem[];
  unreadCount: number;
  /** 已读水位（id > 水位即未读）；行级未读态按此判定。 */
  lastReadId: number;
  status: ConnectionStatus;
  /** history 兜底请求态（面板打开/重试反馈）。 */
  historyLoading: boolean;
  historyError: string | null;
  /** 拉最近记录（面板打开、失败重试）。 */
  refreshHistory: () => Promise<void>;
  /** 单条已读（点击跳转时用）：水位只进不退。 */
  markItemRead: (id: number) => void;
  markAllRead: () => void;
  clearAll: () => void;
}

/**
 * SSE 通知流 hook（仅 NotificationProvider 挂载一份，全站共享）。
 * EventSource 不可用（如测试环境）时安全退化为离线态，不发请求。
 */
export function useNotificationStream(): NotificationStreamState {
  const [items, setItems] = useState<NotificationItem[]>([]);
  const [status, setStatus] = useState<ConnectionStatus>('connecting');
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyError, setHistoryError] = useState<string | null>(null);
  const [lastReadId, setLastReadId] = useState<number>(() => readLastReadId());
  const lastReadRef = useRef(lastReadId);

  const bumpWatermark = useCallback((id: number) => {
    if (id > lastReadRef.current) {
      lastReadRef.current = id;
      persistLastReadId(id);
      setLastReadId(id);
    }
  }, []);

  /** history 兜底：拉最近 N 条归并（面板打开 / 重连补拉共用）。 */
  const refreshHistory = useCallback(async () => {
    setHistoryLoading(true);
    setHistoryError(null);
    try {
      const data = await fetchLatestNotifications();
      setItems((prev) =>
        mergeItems(
          prev,
          data.items.map((item) => ({
            id: item.id,
            type: item.type,
            subjectId: item.subjectId,
            subjectCode: item.subjectCode,
            refId: item.refId,
            content: item.content,
            time: item.createdAt ?? item.pushedAt,
          })),
        ),
      );
    } catch (err) {
      // 兜底拉取失败不打断 SSE 连接；面板内展示重试
      setHistoryError(err instanceof Error ? err.message : String(err));
    } finally {
      setHistoryLoading(false);
    }
  }, []);

  // SSE 连接生命周期（挂载建立，卸令断开；token 每次重连现取，支持登录态轮换）
  useEffect(() => {
    if (typeof EventSource === 'undefined' || !getToken()) {
      setStatus('offline');
      return;
    }
    let source: EventSource | null = null;
    let reconnectTimer: number | undefined;
    let attempts = 0;
    let disposed = false;

    const connect = () => {
      if (disposed) return;
      const token = getToken();
      if (!token) {
        // 登出清 token：停止重连（Provider 随登录守卫卸载，此处兜底）
        setStatus('offline');
        return;
      }
      setStatus('connecting');
      // EventSource 无法携带 Authorization 头：access_token 走查询参数（后端 SSE 握手例外）
      source = new EventSource(
        `${API_BASE_URL}/notifications/stream?access_token=${encodeURIComponent(token)}`,
      );
      source.onopen = () => {
        if (disposed) return;
        attempts = 0;
        setStatus('online');
        // 重连成功：history 补拉断线期间丢失的推送（后端 PENDING 补拉之外的双保险）
        void refreshHistory();
      };
      source.onerror = () => {
        source?.close();
        source = null;
        if (disposed) return;
        setStatus('offline');
        const delay = Math.min(MAX_RECONNECT_DELAY_MS, RECONNECT_BASE_DELAY_MS * 2 ** attempts);
        attempts += 1;
        reconnectTimer = window.setTimeout(connect, delay);
      };
      for (const type of NOTIFICATION_EVENT_TYPES) {
        source.addEventListener(type, (event) => {
          const message = event as MessageEvent<string>;
          try {
            const payload = JSON.parse(message.data) as PushEventPayload;
            const id = Number(message.lastEventId) || Date.now();
            setItems((prev) =>
              mergeItems(prev, [
                {
                  id,
                  type: payload.type,
                  subjectId: payload.subjectId,
                  subjectCode: payload.subjectCode,
                  refId: payload.refId,
                  content: payload.content,
                  time: new Date().toISOString(),
                },
              ]),
            );
          } catch {
            // 载荷异常：丢弃该帧（SSE data 非法 JSON），不触碰连接
          }
        });
      }
    };

    connect();
    return () => {
      disposed = true;
      source?.close();
      if (reconnectTimer !== undefined) window.clearTimeout(reconnectTimer);
    };
  }, [refreshHistory]);

  const markItemRead = useCallback((id: number) => bumpWatermark(id), [bumpWatermark]);

  const markAllRead = useCallback(() => {
    const maxId = items.reduce((max, item) => Math.max(max, item.id), 0);
    bumpWatermark(maxId);
  }, [items, bumpWatermark]);

  const clearAll = useCallback(() => {
    // 清空即视为全部已阅：水位推进到当前最大 id，避免 history 补拉后未读复活
    const maxId = items.reduce((max, item) => Math.max(max, item.id), 0);
    bumpWatermark(maxId);
    setItems([]);
  }, [items, bumpWatermark]);

  const unreadCount = useMemo(
    () => items.filter((item) => item.id > lastReadId).length,
    [items, lastReadId],
  );

  return {
    items,
    unreadCount,
    lastReadId,
    status,
    historyLoading,
    historyError,
    refreshHistory,
    markItemRead,
    markAllRead,
    clearAll,
  };
}
