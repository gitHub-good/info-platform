// TopNav 双行顶栏组件测试（T221，V3.0 REQ-20260928-21 故事 1）：
// 主行（Logo + 4 分组 Tab + 铃铛 + 登出）/ 页签行（当前分组页面页签）/ 单页组直跳 /
// 17 页逐页可达 / testid 兼容（nav-item-{route} 沿用 + nav-group-{label} 新增）/
// 市场风格子路由命中 / 纯静态渲染零业务请求。
// NotificationBell 无 Provider 时为离线空态（NotificationProvider 既有兜底）。

import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { TopNav } from '@/components/layout/TopNav';
import { NAV_GROUPS, groupForRoute } from '@/components/layout/navConfig';

const ALL_GROUPS = NAV_GROUPS.map((group) => group.label);
const ALL_ITEMS = NAV_GROUPS.flatMap((group) =>
  group.items.map((item) => ({ ...item, groupLabel: group.label })),
);

afterEach(() => {
  vi.unstubAllGlobals();
  cleanup();
  localStorage.clear();
  window.location.hash = '';
});

describe('TopNav 双行顶栏（T221）', () => {
  it('主行渲染：Logo + 4 分组 Tab + 铃铛 + 登出，sticky 毛玻璃头部', () => {
    render(<TopNav currentRoute="/watchlists" onOpenDrawer={() => {}} />);

    const header = screen.getByTestId('app-topbar');
    expect(header).toBeInTheDocument();
    // 毛玻璃 sticky（ui-tokens-v3 --header-bg + backdrop-blur-md）
    expect(header.className).toContain('sticky');
    expect(header.className).toContain('bg-header-bg');
    expect(header.className).toContain('backdrop-blur-md');
    // Logo 链接 + 平台名
    expect(screen.getByTestId('topnav-logo')).toHaveAttribute('href', '#/overview');
    expect(screen.getByTestId('topnav-logo')).toHaveTextContent('信息整合与 AI 分析平台');
    // 铃铛与登出在主行
    expect(screen.getByTestId('notification-bell')).toBeInTheDocument();
    expect(screen.getByTestId('nav-logout')).toBeInTheDocument();
  });

  it('4 分组 Tab 数据源沿 navConfig：nav-group-{label} 新增命名', () => {
    render(<TopNav currentRoute="/overview" onOpenDrawer={() => {}} />);

    expect(ALL_GROUPS).toEqual(['总览', '数据', '分析', '运维']);
    for (const label of ALL_GROUPS) {
      expect(screen.getByTestId(`nav-group-${label}`)).toBeInTheDocument();
    }
  });

  it('当前分组高亮：命中组 text-foreground，非命中组 muted（2px 蓝系指示条）', () => {
    render(<TopNav currentRoute="/events" onOpenDrawer={() => {}} />);

    const active = screen.getByTestId('nav-group-分析');
    expect(active.className).toContain('text-foreground');
    expect(active.className).toContain('font-medium');
    expect(active.querySelector('[class*="bg-sidebar-primary"]')).not.toBeNull();

    const idle = screen.getByTestId('nav-group-运维');
    expect(idle.className).toContain('text-muted-foreground');
    expect(idle.querySelector('[class*="bg-sidebar-primary"]')).toBeNull();
  });

  it('页签行：当前分组页面页签全渲染（分析组 7 项，nav-item-{route} 沿用）', () => {
    render(<TopNav currentRoute="/events" onOpenDrawer={() => {}} />);

    const tabsRow = screen.getByTestId('nav-tabs-row');
    expect(tabsRow.className).toContain('backdrop-blur-md'); // 页签行同毛玻璃
    for (const item of NAV_GROUPS.find((g) => g.label === '分析')?.items ?? []) {
      expect(screen.getByTestId(`nav-item-${item.to.slice(1)}`)).toBeInTheDocument();
    }
    // 其他分组页签不渲染（页签行随分组整体替换）
    expect(screen.queryByTestId('nav-item-watchlists')).toBeNull();
    expect(screen.queryByTestId('nav-item-task-center')).toBeNull();
  });

  it('单页组（总览）：点击分组 Tab 直达概览，不渲染页签行', async () => {
    const user = userEvent.setup();
    window.location.hash = '#/events';
    render(<TopNav currentRoute="/events" onOpenDrawer={() => {}} />);

    // 多页分组有页签行；总览组点击直达
    expect(screen.getByTestId('nav-tabs-row')).toBeInTheDocument();
    await user.click(screen.getByTestId('nav-group-总览'));
    expect(window.location.hash).toBe('#/overview');

    // currentRoute 落在总览组：无页签行，组 Tab 即页面（aria-current）
    cleanup();
    window.location.hash = '';
    render(<TopNav currentRoute="/overview" onOpenDrawer={() => {}} />);
    expect(screen.queryByTestId('nav-tabs-row')).toBeNull();
    expect(screen.getByTestId('nav-group-总览')).toHaveAttribute('aria-current', 'page');
  });

  it('页签活跃态：当前页 aria-current="page" + text-foreground + 指示条', () => {
    render(<TopNav currentRoute="/watchlists" onOpenDrawer={() => {}} />);

    const active = screen.getByTestId('nav-item-watchlists');
    expect(active).toHaveAttribute('aria-current', 'page');
    expect(active.className).toContain('text-foreground');
    expect(active.querySelector('[class*="bg-sidebar-primary"]')).not.toBeNull();

    const idle = screen.getByTestId('nav-item-news-library');
    expect(idle).not.toHaveAttribute('aria-current');
    expect(idle.className).toContain('text-muted-foreground');
  });

  it('/market-top/methodology 子路由命中「全市场推荐」页签（routeMatches 前缀匹配）', () => {
    render(<TopNav currentRoute="/market-top/methodology" onOpenDrawer={() => {}} />);

    expect(screen.getByTestId('nav-item-market-top')).toHaveAttribute('aria-current', 'page');
    expect(screen.getByTestId('nav-group-分析').className).toContain('text-foreground');
  });

  it('18 页逐页可达：每页顶栏命中自身页签并带 aria-current（总览经组 Tab 直达）', () => {
    expect(ALL_ITEMS).toHaveLength(18); // 分组 Tab 数据源沿 navConfig 4 分组不变（M27 T245 行业主线入分析组）

    for (const item of ALL_ITEMS) {
      const testid =
        item.groupLabel === '总览' ? 'nav-group-总览' : `nav-item-${item.to.slice(1)}`;
      const { unmount } = render(<TopNav currentRoute={item.to} onOpenDrawer={() => {}} />);
      expect(screen.getByTestId(testid)).toHaveAttribute('aria-current', 'page');
      unmount();
      cleanup();
    }
  });

  it('点击分组 Tab 跳转该组首项页面（跨组 ≤2 次点击可达任意页）', async () => {
    const user = userEvent.setup();
    window.location.hash = '#/overview';
    render(<TopNav currentRoute="/overview" onOpenDrawer={() => {}} />);

    await user.click(screen.getByTestId('nav-group-运维'));
    expect(window.location.hash).toBe('#/task-center'); // 运维组首项

    // 二次点击：组内页签（≤2 次点击可达断言链）
    cleanup();
    render(<TopNav currentRoute="/task-center" onOpenDrawer={() => {}} />);
    expect(screen.getByTestId('nav-item-job-logs')).toBeInTheDocument();
  });

  it('登出：清 token 并跳 /login（既有交互平移到主行）', async () => {
    localStorage.setItem('access_token', 'jwt-x');
    const user = userEvent.setup();
    render(<TopNav currentRoute="/overview" onOpenDrawer={() => {}} />);

    await user.click(screen.getByTestId('nav-logout'));

    expect(localStorage.getItem('access_token')).toBeNull();
    expect(window.location.hash).toBe('#/login');
  });

  it('未知路由（#/subjects/:code）：顶栏仍在，无活跃分组与页签行', () => {
    render(<TopNav currentRoute="/subjects/SH600519" onOpenDrawer={() => {}} />);

    expect(screen.getByTestId('app-topbar')).toBeInTheDocument();
    expect(screen.queryByTestId('nav-tabs-row')).toBeNull();
    for (const label of ALL_GROUPS) {
      expect(screen.getByTestId(`nav-group-${label}`)).not.toHaveAttribute('aria-current');
      expect(screen.getByTestId(`nav-group-${label}`).className).toContain('text-muted-foreground');
    }
  });

  it('纯静态渲染：不发起任何业务请求（PRD 场景 1.3 导航健壮性）', () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    render(<TopNav currentRoute="/news-library" onOpenDrawer={() => {}} />);

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('groupForRoute：路由归属分组判定（navConfig 增量，供页签行取数）', () => {
    expect(groupForRoute('/watchlists')?.label).toBe('数据');
    expect(groupForRoute('/market-top/methodology')?.label).toBe('分析');
    expect(groupForRoute('/job-logs?jobName=x')?.label).toBe('运维');
    expect(groupForRoute('/overview')?.label).toBe('总览');
    expect(groupForRoute('/subjects/SH600519')).toBeNull(); // 无导航承载路由
    expect(groupForRoute('/unknown')).toBeNull();
  });

  it('窄屏单行态：汉堡 + 当前页标题（titleForRoute）在顶栏渲染', () => {
    render(<TopNav currentRoute="/job-logs" onOpenDrawer={() => {}} />);

    expect(screen.getByTestId('nav-toggle')).toBeInTheDocument();
    expect(screen.getByTestId('nav-current-page')).toHaveTextContent('Job 日志');
  });
});
