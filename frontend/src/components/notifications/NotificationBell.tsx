// 通知铃铛 + 面板（P1-1 通知中心 UI）：侧栏底部登出上方挂载，登录后全站可见。
// 交互：未读徽章（收到推送 +1，水位推进已读）；点击开轻量 popover 面板——
// 未读/全部列表（类型徽章 + 异动标的代码 + 时间）、全部已读、清空、条目点击跳标的详情；
// 面板内 SSE 三态指示（连接中/在线/离线）、空态文案、history 兜底失败重试。

import { useEffect, useRef, useState } from 'react';
import { Bell, CheckCheck, Trash2 } from 'lucide-react';
import { useNotificationCenter } from '@/components/notifications/NotificationProvider';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { formatDateTime } from '@/lib/format';
import { navigate } from '@/lib/navigation';
import { TYPE_LABELS, type ConnectionStatus, type NotificationItem } from '@/types/notification';
import { cn } from '@/lib/utils';

const STATUS_META: Record<ConnectionStatus, { label: string; dotClass: string }> = {
  connecting: { label: '连接中', dotClass: 'bg-amber-400' },
  online: { label: '在线', dotClass: 'bg-emerald-400' },
  offline: { label: '离线', dotClass: 'bg-rose-400' },
};

/** 连接状态小指示（面板头部）。 */
function StatusIndicator({ status }: { status: ConnectionStatus }) {
  const meta = STATUS_META[status];
  return (
    <span className="inline-flex items-center gap-1.5 text-xs text-muted-foreground">
      <span className={cn('size-1.5 rounded-full', meta.dotClass)} aria-hidden="true" />
      <span data-testid="notification-status">{meta.label}</span>
    </span>
  );
}

/** 单条通知行：未读圆点 + 类型徽章 + 异动标的代码 + 摘要 + 时间；可点跳标的详情。 */
function NotificationRow({
  item,
  unread,
  onClick,
}: {
  item: NotificationItem;
  unread: boolean;
  onClick: () => void;
}) {
  const clickable = Boolean(item.subjectCode);
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={!clickable}
      title={clickable ? `查看 ${item.subjectCode}` : '无关联标的'}
      data-testid={`notification-item-${item.id}`}
      className={cn(
        'flex w-full flex-col gap-1 rounded-lg px-2 py-2 text-left text-sm transition-colors',
        clickable
          ? 'hover:bg-sidebar-accent/60 focus-visible:bg-sidebar-accent/60 outline-none focus-visible:ring-2 focus-visible:ring-sidebar-ring'
          : 'cursor-default',
      )}
    >
      <span className="flex items-center gap-2">
        <span
          className={cn('size-1.5 shrink-0 rounded-full', unread ? 'bg-primary' : 'bg-transparent')}
          aria-label={unread ? '未读' : '已读'}
        />
        <Badge variant="secondary">{TYPE_LABELS[item.type] ?? item.type}</Badge>
        {item.subjectCode ? (
          <span className="font-mono text-xs text-muted-foreground">{item.subjectCode}</span>
        ) : null}
        <span className="ml-auto shrink-0 text-xs text-muted-foreground">
          {formatDateTime(item.time)}
        </span>
      </span>
      <span className="line-clamp-2 pl-3.5 text-xs text-sidebar-foreground/90">{item.content}</span>
    </button>
  );
}

/**
 * 通知铃铛（消费 NotificationProvider 状态；无 Provider 时渲染离线空态按钮）。
 * 面板为锚定铃铛的轻量 popover（Escape / 点击面板外关闭），不引入新 UI 依赖。
 */
export function NotificationBell() {
  const {
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
  } = useNotificationCenter();
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);

  // 面板打开：拉最近 history 兜底补全（SSE 断线期间的记录经此处进面板）
  useEffect(() => {
    if (open) void refreshHistory();
  }, [open, refreshHistory]);

  // Escape 关闭 + 点击面板外收起
  useEffect(() => {
    if (!open) return;
    const onKeyDown = (event: globalThis.KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    const onPointerDown = (event: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) setOpen(false);
    };
    window.addEventListener('keydown', onKeyDown);
    window.addEventListener('mousedown', onPointerDown);
    return () => {
      window.removeEventListener('keydown', onKeyDown);
      window.removeEventListener('mousedown', onPointerDown);
    };
  }, [open]);

  const handleClick = (item: NotificationItem) => {
    markItemRead(item.id);
    setOpen(false);
    if (item.subjectCode) navigate(`/subjects/${item.subjectCode}`);
  };

  return (
    <div className="relative" ref={rootRef} data-testid="notification-bell-root">
      <button
        type="button"
        onClick={() => setOpen((prev) => !prev)}
        aria-haspopup="dialog"
        aria-expanded={open}
        data-testid="notification-bell"
        className="flex w-full items-center gap-2 rounded-lg px-3 py-1.5 text-sm text-sidebar-foreground/80 outline-none transition-colors hover:bg-sidebar-accent/60 hover:text-sidebar-accent-foreground focus-visible:ring-2 focus-visible:ring-sidebar-ring"
      >
        <span className="relative shrink-0">
          <Bell className="size-4" aria-hidden="true" />
          {unreadCount > 0 ? (
            <span
              data-testid="notification-unread-badge"
              className="absolute -right-1.5 -top-1.5 inline-flex min-w-4 items-center justify-center rounded-full bg-primary px-1 text-[10px] font-medium leading-4 text-primary-foreground"
            >
              {unreadCount > 99 ? '99+' : unreadCount}
            </span>
          ) : null}
        </span>
        <span className="truncate">通知</span>
      </button>

      {open ? (
        <div
          role="dialog"
          aria-label="通知面板"
          data-testid="notification-panel"
          className="absolute bottom-0 left-full z-50 ml-2 flex w-80 flex-col gap-2 rounded-xl border border-border bg-popover p-3 text-popover-foreground shadow-xl"
        >
          <div className="flex items-center gap-2">
            <span className="text-sm font-medium">通知</span>
            <StatusIndicator status={status} />
            <span className="ml-auto flex items-center gap-1">
              <Button
                variant="ghost"
                size="sm"
                onClick={markAllRead}
                disabled={unreadCount === 0}
                data-testid="notification-mark-all"
              >
                <CheckCheck className="size-3.5" aria-hidden="true" />
                全部已读
              </Button>
              <Button
                variant="ghost"
                size="sm"
                onClick={clearAll}
                disabled={items.length === 0}
                data-testid="notification-clear"
              >
                <Trash2 className="size-3.5" aria-hidden="true" />
                清空
              </Button>
            </span>
          </div>

          <div className="flex max-h-80 flex-col gap-1 overflow-y-auto">
            {historyError ? (
              <div className="flex flex-col items-center gap-2 px-2 py-4 text-xs text-muted-foreground">
                <span>通知记录加载失败：{historyError}</span>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => void refreshHistory()}
                  data-testid="notification-retry"
                >
                  重试
                </Button>
              </div>
            ) : historyLoading && items.length === 0 ? (
              <div className="px-2 py-4 text-center text-xs text-muted-foreground" data-testid="notification-loading">
                加载中…
              </div>
            ) : items.length === 0 ? (
              <div className="px-2 py-4 text-center text-xs text-muted-foreground" data-testid="notification-empty">
                暂无通知
              </div>
            ) : (
              items.map((item) => (
                <NotificationRow
                  key={item.id}
                  item={item}
                  unread={item.id > lastReadId}
                  onClick={() => handleClick(item)}
                />
              ))
            )}
          </div>
        </div>
      ) : null}
    </div>
  );
}
