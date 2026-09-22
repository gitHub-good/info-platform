import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppLayout } from '@/components/layout/AppLayout';
import { NAV_GROUPS, titleForRoute } from '@/components/layout/navConfig';

afterEach(() => {
  vi.unstubAllGlobals();
  cleanup();
  localStorage.clear();
  window.location.hash = '';
});

describe('AppLayout 统一导航骨架（T38）', () => {
  it('渲染 4 分组 12 项导航（PRD 场景 1.1 全量可达），当前项高亮', () => {
    render(<AppLayout currentRoute="/watchlists">内容</AppLayout>);

    // 分组标题
    for (const group of NAV_GROUPS) {
      expect(screen.getByText(group.label)).toBeInTheDocument();
    }
    // 全部导航项按 data-testid 定位（约定 nav-item-<路由名>）：11 页 + 底部登出共 12 项
    const allItems = NAV_GROUPS.flatMap((g) => g.items);
    expect(allItems).toHaveLength(11);
    for (const item of allItems) {
      expect(screen.getByTestId(`nav-item-${item.to.slice(1)}`)).toBeInTheDocument();
    }
    // 当前页高亮：aria-current="page"
    expect(screen.getByTestId('nav-item-watchlists')).toHaveAttribute('aria-current', 'page');
    expect(screen.getByTestId('nav-item-overview')).not.toHaveAttribute('aria-current');
    // 底部登出固定存在
    expect(screen.getByTestId('nav-logout')).toBeInTheDocument();
  });

  it('纯静态渲染：不发起任何业务请求（PRD 场景 1.3 导航健壮性）', () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    render(
      <AppLayout currentRoute="/policies">
        <div>内容区</div>
      </AppLayout>,
    );

    expect(fetchMock).not.toHaveBeenCalled();
    expect(screen.getByText('内容区')).toBeInTheDocument();
  });

  it('标的详情入口为无参路由：最近浏览标的由路由解析回退承载（href 恒为 #/subjects）', () => {
    render(<AppLayout currentRoute="/overview">内容</AppLayout>);

    expect(screen.getByTestId('nav-item-subjects')).toHaveAttribute('href', '#/subjects');
  });

  it('登出：清 token 并跳 /login（既有交互平移）', async () => {
    localStorage.setItem('access_token', 'jwt-x');
    const user = userEvent.setup();

    render(<AppLayout currentRoute="/overview">内容</AppLayout>);
    await user.click(screen.getByTestId('nav-logout'));

    expect(localStorage.getItem('access_token')).toBeNull();
    expect(window.location.hash).toBe('#/login');
  });

  it('窄屏抽屉：菜单按钮打开，遮罩点击收起，选中导航项后自动收起', async () => {
    const user = userEvent.setup();
    render(<AppLayout currentRoute="/overview">内容</AppLayout>);

    // 顶栏存在且带当前页标题
    expect(screen.getByTestId('app-topbar')).toBeInTheDocument();
    expect(screen.getByTestId('nav-current-page')).toHaveTextContent('概览');

    // 初始无抽屉
    expect(screen.queryByTestId('nav-drawer')).toBeNull();
    await user.click(screen.getByTestId('nav-toggle'));
    const drawer = screen.getByTestId('nav-drawer');
    expect(drawer).toBeInTheDocument();
    // 抽屉内导航完整（within 限定，避免与桌面侧栏同名 testid 冲突）
    expect(within(drawer).getByTestId('nav-item-policies')).toBeInTheDocument();

    // 遮罩点击收起
    await user.click(screen.getByTestId('nav-overlay'));
    expect(screen.queryByTestId('nav-drawer')).toBeNull();

    // 再打开，点抽屉内导航项 → 收起 + hash 变化
    await user.click(screen.getByTestId('nav-toggle'));
    await user.click(within(screen.getByTestId('nav-drawer')).getByTestId('nav-item-policies'));
    expect(screen.queryByTestId('nav-drawer')).toBeNull();
    expect(window.location.hash).toBe('#/policies');
  });

  it('抽屉打开时 Escape 关闭', async () => {
    const user = userEvent.setup();
    render(<AppLayout currentRoute="/overview">内容</AppLayout>);

    await user.click(screen.getByTestId('nav-toggle'));
    expect(screen.getByTestId('nav-drawer')).toBeInTheDocument();

    await user.keyboard('{Escape}');
    expect(screen.queryByTestId('nav-drawer')).toBeNull();
  });

  it('titleForRoute：已知路由回组内标题，未知路由回空串', () => {
    expect(titleForRoute('/overview')).toBe('概览');
    expect(titleForRoute('/job-logs?jobName=x')).toBe('Job 日志');
    expect(titleForRoute('/subjects/SH600519')).toBe('标的详情');
    expect(titleForRoute('/unknown')).toBe('');
  });
});
