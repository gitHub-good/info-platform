import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import App from '@/App';

/** 构造类 Response 的 mock（只暴露 request() 用到的 ok/status/json）。 */
function mockResponse(status: number, body: unknown) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as unknown as Response;
}

/** Job 日志页数据（游标分页空尾）。 */
function jobLogPage(items: Array<Record<string, unknown>> = []) {
  return { items, nextCursor: null };
}

/** 数据源配置页最小视图（#/datasource-config 导航用：总超时条 + 7 源空态健康）。 */
function datasourceConfigView() {
  const sources = ['QUOTE', 'FINANCE', 'VALUATION', 'ANNOUNCE', 'NEWS', 'POLICY', 'EVENT'].map(
    (code) => ({
      sourceCode: code,
      label: `${code}源`,
      enabled: true,
      mode: 'MOCK',
      timeoutMillis: 1500,
      retries: 0,
      cacheTtlSeconds: 5,
      params: {},
      health: { lastEventType: null, lastEventAt: null, errors24h: 0 },
      updatedAt: null,
      effectiveModes: {},
    }),
  );
  return {
    sources,
    aggregation: { detailTimeoutMillis: 2000, updatedAt: null, effectiveModes: {} },
  };
}

/** LLM 配置页最小视图（#/llm-config 导航用：全局卡 + 空 provider）。 */
function emptyLlmConfig() {
  return {
    global: {
      timeoutSeconds: 30,
      retry: 1,
      dailyTokenBudgetPerUser: 20000,
      budgetWarnRatio: 0.8,
      cacheDefaultTtlSeconds: 3600,
      cacheTtlSeconds: {},
      cacheMaximumSize: 1000,
      todayUsedTokens: 0,
      updatedAt: null,
      effectiveModes: {},
    },
    providers: [],
    apiKeyWriteEnabled: false,
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
  cleanup();
  localStorage.clear();
  window.location.hash = '';
});

/** 按路由分发到各页所需接口的最小 fetch mock（默认全部成功空态）。 */
function makeFetch() {
  return vi.fn(async (url: string) => {
    const path = String(url);
    if (path.includes('/watchlists')) {
      return mockResponse(200, { code: 0, msg: 'ok', data: [], traceId: 't' });
    }
    if (path.includes('/job-logs')) {
      return mockResponse(200, {
        code: 0,
        msg: 'ok',
        data: jobLogPage([
          {
            id: 1,
            jobName: 'PolicyFetchJob',
            startTime: '2026-09-22T01:00:00Z',
            endTime: '2026-09-22T01:00:01Z',
            status: 'SUCCESS',
            durationMillis: 1000,
            processedCount: 3,
            errorCount: 0,
            errorMessage: null,
          },
        ]),
        traceId: 't',
      });
    }
    if (path.includes('/auth/login')) {
      return mockResponse(200, {
        code: 0,
        msg: 'ok',
        data: { accessToken: 'jwt-new', refreshToken: 'r', tokenType: 'Bearer', expiresIn: 3600 },
        traceId: 't',
      });
    }
    if (path.includes('/llm-config')) {
      return mockResponse(200, { code: 0, msg: 'ok', data: emptyLlmConfig(), traceId: 't' });
    }
    if (path.includes('/datasource-configs')) {
      return mockResponse(200, {
        code: 0,
        msg: 'ok',
        data: datasourceConfigView(),
        traceId: 't',
      });
    }
    return mockResponse(200, { code: 0, msg: 'ok', data: null, traceId: 't' });
  });
}

/** 已登录状态下渲染（设 token + 可选初始 hash）。 */
function renderLoggedIn(hash: string) {
  window.location.hash = hash;
  localStorage.setItem('access_token', 'jwt-test');
  const fetchMock = makeFetch();
  vi.stubGlobal('fetch', fetchMock);
  render(<App />);
  return fetchMock;
}

describe('App 路由与登录守卫（T38）', () => {
  it('已登录空 hash → 默认落地 /overview 占位页，hash 同步为 #/overview', async () => {
    renderLoggedIn('');

    expect(screen.getByTestId('overview-placeholder')).toBeInTheDocument();
    expect(screen.getByTestId('overview-placeholder-badge')).toHaveTextContent('开发中');
    await waitFor(() => expect(window.location.hash).toBe('#/overview'));
  });

  it('已登录访问 /login → 跳默认页 /overview（不渲染登录表单）', async () => {
    renderLoggedIn('#/login');

    await waitFor(() => expect(window.location.hash).toBe('#/overview'));
    expect(screen.queryByTestId('login-form')).toBeNull();
    expect(screen.getByTestId('overview-placeholder')).toBeInTheDocument();
  });

  it('未登录访问受保护路由 → 渲染登录页（无侧栏），登录成功回原目标页', async () => {
    // Arrange：未登录访问 #/job-logs（目标页 hash 与登录后目标不同，保证 hash 真实跳变）
    window.location.hash = '#/job-logs';
    const fetchMock = makeFetch();
    vi.stubGlobal('fetch', fetchMock);
    render(<App />);

    // Assert：全屏登录页，无 AppLayout 侧栏
    expect(screen.getByTestId('login-card')).toBeInTheDocument();
    expect(screen.queryByTestId('nav-item-job-logs')).toBeNull();

    // Act：登录成功
    const user = userEvent.setup();
    await user.type(screen.getByTestId('login-username'), 'admin');
    await user.type(screen.getByTestId('login-password'), 'admin123');
    await user.click(screen.getByTestId('login-submit'));

    // Assert：回原目标页 #/job-logs，侧栏随布局出现
    await waitFor(() => expect(screen.getByTestId('job-log-page')).toBeInTheDocument());
    expect(window.location.hash).toBe('#/job-logs');
    expect(screen.getByTestId('nav-item-job-logs')).toBeInTheDocument();
    expect(localStorage.getItem('access_token')).toBe('jwt-new');
  });

  it('已登录各既有页在新导航下可达（回归：watchlists / job-logs / cost-report）', async () => {
    // watchlists
    let fetchMock = renderLoggedIn('#/watchlists');
    expect(screen.getByTestId('watchlist-page')).toBeInTheDocument();
    expect(screen.getByTestId('nav-item-watchlists')).toHaveAttribute('aria-current', 'page');
    expect(String(fetchMock.mock.calls[0][0])).toContain('/watchlists');

    // job-logs（经侧栏 <a> 导航）
    const user = userEvent.setup();
    await user.click(screen.getByTestId('nav-item-job-logs'));
    expect(await screen.findByTestId('job-log-page')).toBeInTheDocument();

    // cost-report
    await user.click(screen.getByTestId('nav-item-cost-report'));
    await waitFor(() => {
      expect(screen.queryByTestId('job-log-page')).toBeNull();
    });
  });

  it('#/job-logs?jobName=xxx 初始即按参数预过滤请求（T41 任务中心跳转口径）', async () => {
    const fetchMock = renderLoggedIn('#/job-logs?jobName=PolicyFetchJob');

    // 首次请求即带 jobName 参数
    await screen.findByTestId('job-log-page');
    const jobLogCall = fetchMock.mock.calls
      .map((c) => String(c[0]))
      .find((url) => url.includes('/job-logs'));
    expect(jobLogCall).toContain('jobName=PolicyFetchJob');
  });

  it('#/subjects/:code 路由参数化：记录最近浏览标的，无参入口回退最近浏览', async () => {
    renderLoggedIn('#/subjects/SZ000001');

    // SubjectDetail 挂载即记录路由参数标的（最近浏览，供侧栏无参入口回退）
    await waitFor(() => expect(localStorage.getItem('last_viewed_subject')).toBe('SZ000001'));
    // 侧栏「标的详情」项 active，入口为无参路由
    const subjectNav = screen.getByTestId('nav-item-subjects');
    expect(subjectNav).toHaveAttribute('aria-current', 'page');
    expect(subjectNav).toHaveAttribute('href', '#/subjects');

    // 无参入口（侧栏点击）→ 回退最近浏览标的 SZ000001
    const user = userEvent.setup();
    await user.click(subjectNav);
    await waitFor(() =>
      expect(localStorage.getItem('last_viewed_subject')).toBe('SZ000001'),
    );
    expect(window.location.hash).toBe('#/subjects');
  });

  it('3 个新页路由占位（#/task-center、#/overview、#/feed），#/llm-config（T39）与 #/datasource-config（T40）为实现页', async () => {
    const user = userEvent.setup();
    const fetchMock = renderLoggedIn('#/overview');

    // 经侧栏逐项导航，未实现页渲染「开发中」占位
    const placeholders: Array<[string, string]> = [
      ['nav-item-task-center', 'task-center-placeholder'],
      ['nav-item-feed', 'feed-placeholder'],
      ['nav-item-overview', 'overview-placeholder'],
    ];
    for (const [navId, pageId] of placeholders) {
      await user.click(screen.getByTestId(navId));
      expect(await screen.findByTestId(pageId)).toBeInTheDocument();
      expect(screen.getByTestId(`${pageId}-badge`)).toHaveTextContent('开发中');
    }

    // #/llm-config（T39）：真实页挂载并请求配置接口（不再渲染占位）
    await user.click(screen.getByTestId('nav-item-llm-config'));
    expect(await screen.findByTestId('llm-config-page')).toBeInTheDocument();
    expect(screen.queryByTestId('llm-config-placeholder')).toBeNull();

    // #/datasource-config（T40）：真实页挂载并请求数据源配置接口
    await user.click(screen.getByTestId('nav-item-datasource-config'));
    expect(await screen.findByTestId('datasource-config-page')).toBeInTheDocument();
    expect(screen.queryByTestId('datasource-config-placeholder')).toBeNull();
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some((call) => String(call[0]).includes('/datasource-configs')),
      ).toBe(true),
    );
  });

  it('未知路由已登录时无内容区崩坏（侧栏仍在，内容区空）', () => {
    renderLoggedIn('#/nonexistent');
    expect(screen.getByTestId('nav-item-overview')).toBeInTheDocument();
  });
});
