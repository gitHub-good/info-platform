import { useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { LogOut, Menu, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { NotificationBell } from '@/components/notifications/NotificationBell';
import { logout } from '@/api/auth';
import { navigate } from '@/lib/navigation';
import {
  NAV_GROUPS,
  routeMatches,
  titleForRoute,
  type NavItem,
} from '@/components/layout/navConfig';
import { cn } from '@/lib/utils';

function isActive(item: NavItem, currentRoute: string): boolean {
  return routeMatches(currentRoute, item.to);
}

interface NavItemLinkProps {
  item: NavItem;
  active: boolean;
  onNavigate?: () => void;
}

function NavItemLink({ item, active, onNavigate }: NavItemLinkProps) {
  const Icon = item.icon;
  return (
    <a
      href={`#${item.to}`}
      aria-current={active ? 'page' : undefined}
      onClick={onNavigate}
      data-testid={`nav-item-${item.to.slice(1)}`}
      className={cn(
        'flex items-center gap-2 rounded-lg px-3 py-1.5 text-sm outline-none transition-colors focus-visible:ring-2 focus-visible:ring-sidebar-ring',
        active
          ? 'bg-sidebar-accent font-medium text-sidebar-accent-foreground'
          : 'text-sidebar-foreground/80 hover:bg-sidebar-accent/60 hover:text-sidebar-accent-foreground',
      )}
    >
      <Icon className="size-4 shrink-0" aria-hidden="true" />
      <span className="truncate">{item.label}</span>
    </a>
  );
}

interface SidebarContentProps {
  currentRoute: string;
  onNavigate?: () => void;
}

/**
 * 侧栏主体（分组导航 + 底部通知铃铛与登出），桌面侧栏与窄屏抽屉共用。
 * 铃铛消费 NotificationProvider（App 登录态挂载）；独立渲染（无 Provider）时为离线空态。
 */
function SidebarContent({ currentRoute, onNavigate }: SidebarContentProps) {
  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="px-5 pb-2 pt-4">
        <span className="block truncate text-sm font-medium text-sidebar-foreground">
          信息整合与 AI 分析平台
        </span>
      </div>
      <nav aria-label="主导航" className="flex-1 overflow-y-auto px-3 pb-4">
        {NAV_GROUPS.map((group, index) => (
          <div key={group.label} className={index === 0 ? 'mt-2' : 'mt-4'}>
            <div className="px-3 pb-1 text-xs text-muted-foreground">{group.label}</div>
            <div className="flex flex-col gap-0.5">
              {group.items.map((item) => (
                <NavItemLink
                  key={item.to}
                  item={item}
                  active={isActive(item, currentRoute)}
                  onNavigate={onNavigate}
                />
              ))}
            </div>
          </div>
        ))}
      </nav>
      <div className="border-t border-sidebar-border px-3 py-1">
        <NotificationBell />
      </div>
      <div className="border-t border-sidebar-border px-3 py-2">
        <button
          type="button"
          onClick={handleLogout}
          data-testid="nav-logout"
          className="flex w-full items-center gap-2 rounded-lg px-3 py-1.5 text-sm text-sidebar-foreground/80 outline-none transition-colors hover:bg-sidebar-accent/60 hover:text-sidebar-accent-foreground focus-visible:ring-2 focus-visible:ring-sidebar-ring"
        >
          <LogOut className="size-4 shrink-0" aria-hidden="true" />
          登出
        </button>
      </div>
    </div>
  );
}

interface AppLayoutProps {
  /** 当前路由（active 高亮与顶栏标题）。 */
  currentRoute: string;
  children: ReactNode;
}

/**
 * 统一导航骨架（T38，UI 方案 §2.2）：
 * 桌面（lg+）固定左侧栏 w-56（不随内容滚动）；窄屏顶栏（h-12）+ 左滑抽屉（w-64 + 半透明遮罩，
 * 遮罩点击 / Escape / 选中任一项后收起，焦点圈定抽屉内）。内容区独立滚动，各页面保留自带 main+max-w。
 * 纯静态渲染：不请求任何业务接口（PRD 场景 1.3 导航健壮性）。
 */
export function AppLayout({ currentRoute, children }: AppLayoutProps) {
  const [drawerOpen, setDrawerOpen] = useState(false);
  const drawerRef = useRef<HTMLDivElement>(null);

  // 抽屉打开时 Escape 关闭 + 焦点移入抽屉
  useEffect(() => {
    if (!drawerOpen) return;
    drawerRef.current?.focus();
    const onKeyDown = (event: globalThis.KeyboardEvent) => {
      if (event.key === 'Escape') setDrawerOpen(false);
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [drawerOpen]);

  // 焦点圈定：Tab 在抽屉内首个/末个可聚焦元素间环绕
  const trapFocus = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key !== 'Tab' || !drawerRef.current) return;
    const focusables = drawerRef.current.querySelectorAll<HTMLElement>(
      'a[href], button:not([disabled])',
    );
    if (focusables.length === 0) return;
    const first = focusables[0];
    const last = focusables[focusables.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  };

  return (
    <div className="min-h-screen bg-background">
      <aside className="fixed inset-y-0 left-0 z-30 hidden w-56 border-r border-sidebar-border bg-sidebar text-sidebar-foreground lg:flex lg:flex-col">
        <SidebarContent currentRoute={currentRoute} />
      </aside>

      <header
        className="sticky top-0 z-40 flex h-12 items-center gap-1 border-b border-sidebar-border bg-sidebar px-2 text-sidebar-foreground lg:hidden"
        data-testid="app-topbar"
      >
        <Button
          variant="ghost"
          size="icon"
          aria-label="打开导航"
          onClick={() => setDrawerOpen(true)}
          data-testid="nav-toggle"
        >
          <Menu className="size-5" aria-hidden="true" />
        </Button>
        <span className="truncate text-sm font-medium" data-testid="nav-current-page">
          {titleForRoute(currentRoute)}
        </span>
      </header>

      {drawerOpen ? (
        <div className="fixed inset-0 z-50 lg:hidden" role="dialog" aria-modal="true" aria-label="导航抽屉">
          <div
            className="absolute inset-0 bg-black/50"
            onClick={() => setDrawerOpen(false)}
            data-testid="nav-overlay"
          />
          <div
            ref={drawerRef}
            tabIndex={-1}
            onKeyDown={trapFocus}
            className="absolute inset-y-0 left-0 flex w-64 flex-col border-r border-sidebar-border bg-sidebar text-sidebar-foreground shadow-xl outline-none"
            data-testid="nav-drawer"
          >
            <div className="flex items-center justify-between px-3 py-2">
              <span className="truncate px-2 text-sm font-medium">信息整合与 AI 分析平台</span>
              <Button
                variant="ghost"
                size="icon"
                aria-label="关闭导航"
                onClick={() => setDrawerOpen(false)}
                data-testid="nav-drawer-close"
              >
                <X className="size-4" aria-hidden="true" />
              </Button>
            </div>
            <SidebarContent currentRoute={currentRoute} onNavigate={() => setDrawerOpen(false)} />
          </div>
        </div>
      ) : null}

      <div className="min-w-0 lg:pl-56">{children}</div>
    </div>
  );
}
