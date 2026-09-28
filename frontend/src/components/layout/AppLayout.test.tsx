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

describe('AppLayout 统一导航骨架（T38 → V3.0 T221 顶栏化）', () => {
  it('顶栏导航：4 分组 Tab + 当前分组页签渲染，桌面侧栏退役（aside 删除、lg:pl-56 移除）', () => {
    const { container } = render(<AppLayout currentRoute="/watchlists">内容</AppLayout>);

    // 分组 Tab（nav-group-{label} 新增命名，数据源沿 navConfig 4 分组）
    for (const group of NAV_GROUPS) {
      expect(screen.getByTestId(`nav-group-${group.label}`)).toBeInTheDocument();
    }
    // 当前分组（数据）页签：两项页签渲染 + 当前项 aria-current
    expect(screen.getByTestId('nav-item-watchlists')).toHaveAttribute('aria-current', 'page');
    expect(screen.getByTestId('nav-item-news-library')).toBeInTheDocument();
    // 其他分组页签不渲染（页签行随分组整体替换）
    expect(screen.queryByTestId('nav-item-events')).toBeNull();
    expect(screen.queryByTestId('nav-item-task-center')).toBeNull();
    // 桌面侧栏退役：<aside> 不复存在（抽屉为 div 实现，不误伤）
    expect(container.querySelector('aside')).toBeNull();
    // 顶栏承载铃铛与登出（原侧栏底部两槽位上移主行）
    expect(screen.getByTestId('notification-bell')).toBeInTheDocument();
    expect(screen.getByTestId('nav-logout')).toBeInTheDocument();
  });

  it('17 页 navConfig 总表不变；完整导航经窄屏抽屉承载（nav-item-{route} 全量保留）', async () => {
    const user = userEvent.setup();
    render(<AppLayout currentRoute="/overview">内容</AppLayout>);

    const allItems = NAV_GROUPS.flatMap((g) => g.items);
    expect(allItems).toHaveLength(17); // 4 分组 17 页结构零变化
    await user.click(screen.getByTestId('nav-toggle'));
    const drawer = screen.getByTestId('nav-drawer');
    for (const item of allItems) {
      expect(within(drawer).getByTestId(`nav-item-${item.to.slice(1)}`)).toBeInTheDocument();
    }
    // 已裁撤入口不再出现
    expect(within(drawer).queryByTestId('nav-item-policies')).toBeNull();
    expect(within(drawer).queryByTestId('nav-item-subjects')).toBeNull();
    expect(within(drawer).queryByTestId('nav-item-datasource-config')).toBeNull();
    expect(within(drawer).queryByTestId('nav-item-info-sources')).toBeNull();
    // 抽屉不再渲染铃铛与登出（顶栏主行已有，UI 方案 §1.3）
    expect(within(drawer).queryByTestId('notification-bell')).toBeNull();
    expect(within(drawer).queryByTestId('nav-logout')).toBeNull();
  });

  it('纯静态渲染：不发起任何业务请求（PRD 场景 1.3 导航健壮性）', () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    render(
      <AppLayout currentRoute="/news-library">
        <div>内容区</div>
      </AppLayout>,
    );

    expect(fetchMock).not.toHaveBeenCalled();
    expect(screen.getByText('内容区')).toBeInTheDocument();
  });

  it('标的详情侧栏入口已裁撤：#/subjects/:code 路由不再有导航承载（直接 URL 进入仍可用）', () => {
    render(<AppLayout currentRoute="/subjects/SH600519">内容</AppLayout>);

    // 入口裁撤后：无导航项高亮（无分组命中 → 无页签行，组 Tab 全部非活跃）
    expect(screen.queryByTestId('nav-tabs-row')).toBeNull();
    for (const group of NAV_GROUPS) {
      expect(screen.getByTestId(`nav-group-${group.label}`)).not.toHaveAttribute('aria-current');
    }
  });

  it('登出：清 token 并跳 /login（顶栏主行登出按钮，既有交互平移）', async () => {
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
    // 抽屉内导航完整（within 限定，避免与桌面页签同名 testid 冲突）
    expect(within(drawer).getByTestId('nav-item-news-library')).toBeInTheDocument();

    // 遮罩点击收起
    await user.click(screen.getByTestId('nav-overlay'));
    expect(screen.queryByTestId('nav-drawer')).toBeNull();

    // 再打开，点抽屉内导航项 → 收起 + hash 变化
    await user.click(screen.getByTestId('nav-toggle'));
    await user.click(within(screen.getByTestId('nav-drawer')).getByTestId('nav-item-news-library'));
    expect(screen.queryByTestId('nav-drawer')).toBeNull();
    expect(window.location.hash).toBe('#/news-library');
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
    expect(titleForRoute('/subjects/SH600519')).toBe(''); // 侧栏入口裁撤：路由保留但导航不再承载标题
    expect(titleForRoute('/prompt-templates')).toBe('提示词模板');
    // V2.3 T204：源管理单页（带 Tab/定位参数仍命中）
    expect(titleForRoute('/sources')).toBe('源管理');
    expect(titleForRoute('/sources?tab=biz&source=s1')).toBe('源管理');
    expect(titleForRoute('/unknown')).toBe('');
  });
});
