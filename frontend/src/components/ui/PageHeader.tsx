// 页头统一容器（M26 T230，UI V3.0 前端改版方案 §4 项 5 / REQ-20260928-21 #12）：
// title（h1 text-xl font-medium）+ subtitle + actions 三槽，替换 17 页手写页头并补齐缺失副标题。
//
// 从简裁量留档（方案 §4 项 5 裁量项）：桌面顶栏页签行已承载页面名，但窄屏（<lg）顶栏
// 收敛为单行、分组页签不可见，页面内仍需 h1 标识——故 PageHeader 保留页面级 h1，
// 只统一结构（标题/副标题/操作区三槽）与摆放（操作区右置）；各页操作沿既有入口不改语义。
// 副标题沿用 M18 风格约定（mt-1 text-sm text-muted-foreground）。

import type { ReactNode } from 'react';

export interface PageHeaderProps {
  /** 页面标题（页面级 h1，每页唯一）。 */
  title: ReactNode;
  /** 副标题（页面职责一句话；M18 起全站约定，5 页缺失本批补齐）。 */
  subtitle?: ReactNode;
  /** 操作区（右侧：主按钮 / 筛选 / 刷新等，沿各页既有）。 */
  actions?: ReactNode;
  /** 根节点 testid（缺省 'page-header'）。 */
  testId?: string;
  /** h1 标题 testid（存量页面选择器兼容，如 prompt-editor-title）。 */
  titleTestId?: string;
}

/** 页头统一容器：左标题+副标题 / 右操作区；副标题与操作区带固定子 testid 供页面抽查断言。 */
export function PageHeader({
  title,
  subtitle,
  actions,
  testId = 'page-header',
  titleTestId,
}: PageHeaderProps) {
  return (
    <header className="mb-4 flex flex-wrap items-start justify-between gap-2" data-testid={testId}>
      <div className="min-w-0">
        <h1 className="text-xl font-medium" data-testid={titleTestId}>
          {title}
        </h1>
        {subtitle ? (
          <p className="mt-1 text-sm text-muted-foreground" data-testid="page-header-subtitle">
            {subtitle}
          </p>
        ) : null}
      </div>
      {actions ? (
        <div className="flex flex-wrap items-center gap-2" data-testid="page-header-actions">
          {actions}
        </div>
      ) : null}
    </header>
  );
}
