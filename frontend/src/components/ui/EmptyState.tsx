// 空态统一容器（M26 T228，UI V3.0 前端改版方案 §4 项 3 / REQ-20260928-21 #10）：
// 图标槽 + 标题 + 描述 + 动作槽（可选 CTA 链/按钮），居中呈现在页面/区块两种密度下——
// standard（py-10）页面级列表空态、compact（py-6）区块/卡片内嵌空态。
// 纯展示受控组件：动作交互由调用方经 action 槽注入（CTA 跳转沿各页既有引导，组件不猜业务）。
// 替换范围 = 17 导航页页面级空态；文案沿各页既有引导语义（只统一呈现不改业务语义）。

import type { ReactNode } from 'react';
import { cn } from 'cn';

/** 空态密度：standard 页面级（py-10）/ compact 区块级（py-6）。 */
export type EmptyStateSize = 'standard' | 'compact';

export interface EmptyStateProps {
  /** 图标槽（如 lucide 图标；缺省不渲染圆底图标位）。 */
  icon?: ReactNode;
  /** 标题（空态主文案，如「暂无事件」）。 */
  title: ReactNode;
  /** 描述（引导动作/成因说明；缺省省略）。 */
  description?: ReactNode;
  /** 动作槽（CTA 按钮/链接，可多枚；缺省省略）。 */
  action?: ReactNode;
  /** 密度档，缺省 standard（页面级）。 */
  size?: EmptyStateSize;
  /** 根节点 testid（沿各页既有空态 testid，如 events-empty）。 */
  testId?: string;
  /** 追加类名（页面级布局微调）。 */
  className?: string;
}

/** 空态统一容器：muted 灰调、居中、图标圆底；标题/描述带固定子 testid 供页面空态抽查断言。 */
export function EmptyState({
  icon,
  title,
  description,
  action,
  size = 'standard',
  testId,
  className,
}: EmptyStateProps) {
  return (
    <div
      className={cn(
        'flex flex-col items-center gap-2 text-center',
        size === 'standard' ? 'py-10' : 'py-6',
        className,
      )}
      data-testid={testId}
    >
      {icon ? (
        <span
          className="flex size-10 shrink-0 items-center justify-center rounded-full bg-muted"
          aria-hidden="true"
        >
          {icon}
        </span>
      ) : null}
      <p className="text-sm font-medium text-foreground" data-testid="empty-state-title">
        {title}
      </p>
      {description ? (
        <p
          className="max-w-md text-sm text-muted-foreground"
          data-testid="empty-state-description"
        >
          {description}
        </p>
      ) : null}
      {action ? (
        <div className="mt-1 flex flex-wrap items-center justify-center gap-2">{action}</div>
      ) : null}
    </div>
  );
}
