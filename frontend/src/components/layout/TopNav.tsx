// 双行顶栏（V3.0 T221，UI 方案 §1 裁决 D / REQ-20260928-21 故事 1）：
// 主行（sticky top-0，h-14 = --header-height，毛玻璃 bg-header-bg + backdrop-blur-md）=
//   Logo + 4 分组 Tab + 通知铃铛 + 登出；窄屏（<lg）单行态 = 汉堡 + 当前页标题 + 铃铛 + 登出。
// 页签行（sticky top-14，h-11 = --header-tabs-height）= 当前分组的页面页签（仅多页分组渲染；
// 「总览」单页组点击分组 Tab 直达 #/overview 不渲染页签行）。
// 分组 Tab 数据源沿 navConfig 4 分组不变；页签 testid 沿用 nav-item-{route}（既有测试选择器
// 不破），新增 nav-group-{label}；纯静态原生链接 + aria-current，零弹出层焦点管理负担。

import { LogOut, Menu } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { NotificationBell } from '@/components/notifications/NotificationBell';
import { logout } from '@/api/auth';
import { navigate } from '@/lib/navigation';
import {
  NAV_GROUPS,
  groupForRoute,
  routeMatches,
  titleForRoute,
  type NavGroup,
  type NavItem,
} from '@/components/layout/navConfig';
import { cn } from '@/lib/utils';

/** 分组 Tab（主行）：活跃 = text-foreground + 2px 蓝系底指示条（bg-sidebar-primary，v2 复用）；
 * 单页组活跃时组 Tab 即页面（aria-current="page"）。 */
function GroupTab({ group, active }: { group: NavGroup; active: boolean }) {
  return (
    <a
      href={`#${group.items[0].to}`}
      aria-current={active && group.single ? 'page' : undefined}
      data-testid={`nav-group-${group.label}`}
      className={cn(
        'relative inline-flex h-11 items-center whitespace-nowrap px-3 text-sm outline-none transition-colors focus-visible:ring-2 focus-visible:ring-sidebar-ring',
        active
          ? 'font-medium text-foreground'
          : 'text-muted-foreground hover:bg-accent/60 hover:text-foreground',
      )}
    >
      {group.label}
      {active ? (
        <span
          aria-hidden="true"
          className="absolute inset-x-2 bottom-0 h-0.5 rounded-full bg-sidebar-primary"
        />
      ) : null}
    </a>
  );
}

/** 页面页签（页签行）：活跃 = text-foreground + 2px 指示条 + aria-current="page"；
 * testid 沿用 nav-item-{route}（抽屉 NavItemLink 同名同语义）。 */
function PageTab({ item, active }: { item: NavItem; active: boolean }) {
  return (
    <a
      href={`#${item.to}`}
      aria-current={active ? 'page' : undefined}
      data-testid={`nav-item-${item.to.slice(1)}`}
      className={cn(
        'relative inline-flex h-11 shrink-0 items-center whitespace-nowrap px-3 text-sm outline-none transition-colors focus-visible:ring-2 focus-visible:ring-sidebar-ring',
        active
          ? 'font-medium text-foreground'
          : 'text-muted-foreground hover:bg-accent/60 hover:text-foreground',
      )}
    >
      {item.label}
      {active ? (
        <span
          aria-hidden="true"
          className="absolute inset-x-2 bottom-0 h-0.5 rounded-full bg-sidebar-primary"
        />
      ) : null}
    </a>
  );
}

interface TopNavProps {
  /** 当前路由（分组命中 / 页签高亮 / 窄屏标题）。 */
  currentRoute: string;
  /** 打开窄屏导航抽屉（汉堡按钮，<lg 可见）。 */
  onOpenDrawer: () => void;
}

/**
 * 顶栏导航（纯静态渲染，不请求任何业务接口——导航健壮性既有裁决）。
 * 三态口径（UI 方案 §1.5）：默认 muted / 悬停 hover:bg-accent/60 + text-foreground /
 * 活跃 text-foreground + 指示条 / 焦点 focus-visible:ring-2（蓝系可见）；触控目标 h-11 = 44px。
 */
export function TopNav({ currentRoute, onOpenDrawer }: TopNavProps) {
  const activeGroup = groupForRoute(currentRoute);
  const tabs = activeGroup && !activeGroup.single ? activeGroup.items : null;

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  return (
    <>
      <header
        data-testid="app-topbar"
        className="sticky top-0 z-40 border-b border-border bg-header-bg text-foreground backdrop-blur-md"
      >
        <div className="flex h-12 items-center gap-1 px-3 sm:px-4 lg:h-(--header-height)">
          <Button
            variant="ghost"
            size="icon"
            aria-label="打开导航"
            onClick={onOpenDrawer}
            data-testid="nav-toggle"
            className="lg:hidden"
          >
            <Menu className="size-5" aria-hidden="true" />
          </Button>
          <span
            className="truncate text-sm font-medium lg:hidden"
            data-testid="nav-current-page"
          >
            {titleForRoute(currentRoute)}
          </span>

          <a
            href="#/overview"
            data-testid="topnav-logo"
            className="hidden items-center gap-2.5 text-sm font-medium text-foreground outline-none focus-visible:ring-2 focus-visible:ring-sidebar-ring lg:flex"
          >
            <span
              aria-hidden="true"
              className="flex size-6 shrink-0 items-center justify-center rounded-md bg-sidebar-primary text-xs font-bold text-sidebar-primary-foreground"
            >
              ◆
            </span>
            <span className="truncate">信息整合与 AI 分析平台</span>
          </a>

          <nav aria-label="分组导航" className="ml-4 hidden items-center gap-1 lg:flex">
            {NAV_GROUPS.map((group) => (
              <GroupTab
                key={group.label}
                group={group}
                active={activeGroup?.label === group.label}
              />
            ))}
          </nav>

          <div className="ml-auto flex items-center gap-1">
            <NotificationBell />
            <button
              type="button"
              onClick={handleLogout}
              aria-label="登出"
              title="登出"
              data-testid="nav-logout"
              className="flex size-9 items-center justify-center rounded-lg text-muted-foreground outline-none transition-colors hover:bg-accent/60 hover:text-foreground focus-visible:ring-2 focus-visible:ring-sidebar-ring"
            >
              <LogOut className="size-4" aria-hidden="true" />
            </button>
          </div>
        </div>
      </header>

      {tabs ? (
        <div
          data-testid="nav-tabs-row"
          className="sticky top-(--header-height) z-30 hidden border-b border-border bg-header-bg backdrop-blur-md lg:block"
        >
          <nav
            aria-label="页面页签"
            className="flex h-(--header-tabs-height) items-center gap-1 overflow-x-auto px-3 sm:px-4"
          >
            {tabs.map((item) => (
              <PageTab key={item.to} item={item} active={routeMatches(currentRoute, item.to)} />
            ))}
          </nav>
        </div>
      ) : null}
    </>
  );
}
