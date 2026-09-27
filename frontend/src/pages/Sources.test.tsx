import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Sources } from '@/pages/Sources';
import type { InfoSourceCardView, InfoSourcesView } from '@/types/infoSource';
import type { DataSourceConfigView } from '@/types/datasourceConfig';

// —— fetch mock：GET /info-sources（分组视图）+ GET /datasource-configs（业务源视图），可按需覆写 ——

const ok = (data: unknown) => ({
  ok: true,
  status: 200,
  json: async () => ({ code: 0, msg: 'ok', data, traceId: 't' }),
});
const fail = (status: number) => ({
  ok: false,
  status,
  json: async () => ({ code: 50000, msg: '服务异常', data: null, traceId: 't' }),
});

function infoCard(overrides: Partial<InfoSourceCardView> = {}): InfoSourceCardView {
  return {
    id: 1,
    sourceCode: 'jin10_flash',
    name: '金十数据·快讯',
    category: '快讯',
    adapterType: 'json_api',
    adapterRef: null,
    endpoint: 'https://example.com/feed',
    config: {
      listPath: null,
      stripPrefix: null,
      stripSuffix: null,
      itemMapping: [],
      headers: {},
      maxItems: null,
      pageSize: null,
      cursorType: 'NONE',
      cursorField: null,
      aiExclusion: 'NONE',
      staleSince: null,
    },
    intervalMinutes: 5,
    enabled: true,
    preset: true,
    deleted: false,
    today: { pollCount: 10, failCount: 0, newCount: 3, dupCount: 0 },
    state: {
      lastAttemptAt: null,
      lastSuccessAt: null,
      nextDueAt: null,
      cursorValue: null,
      consecutiveFailures: 0,
      backoffUntil: null,
      lastDurationMillis: null,
      lastRoundDetail: null,
      lastError: null,
    },
    createdAt: '2026-09-22T00:00:00Z',
    updatedAt: '2026-09-22T00:00:00Z',
    ...overrides,
  };
}

function infoView(): InfoSourcesView {
  return {
    groups: [
      {
        category: '快讯',
        sources: [
          infoCard(),
          infoCard({
            id: 2,
            sourceCode: 'mw_topstories',
            name: '华尔街见闻·要闻',
            enabled: false,
            today: { pollCount: 5, failCount: 1, newCount: 4, dupCount: 0 },
          }),
        ],
      },
    ],
    archived: [],
  };
}

function bizView(): DataSourceConfigView {
  const healthOf = (type: string | null) =>
    type === null
      ? { lastEventType: null, lastEventAt: null, errors24h: 0 }
      : { lastEventType: type, lastEventAt: '2026-09-22T01:00:00Z', errors24h: 0 };
  return {
    aggregation: { detailTimeoutMillis: 2000, updatedAt: null, effectiveModes: {} },
    sources: [
      'QUOTE',
      'FINANCE',
      'EVENT',
    ].map((code) => ({
      sourceCode: code,
      label: code === 'EVENT' ? '事件源' : `${code}源`,
      enabled: true,
      mode: 'REAL',
      timeoutMillis: 1500,
      retries: 1,
      cacheTtlSeconds: 5,
      fallbackChain: ['eastmoney'],
      availableProviders: ['eastmoney'],
      params: {},
      health: healthOf(code === 'QUOTE' ? 'OK' : code === 'FINANCE' ? 'ERROR' : null),
      updatedAt: null,
      effectiveModes: {},
    })),
  };
}

interface MockOpts {
  info?: 'ok' | 'fail';
  biz?: 'ok' | 'fail';
}

function stubFetch(opts: MockOpts = {}) {
  return vi.fn(async (url: string) => {
    const path = String(url);
    if (path.includes('/datasource-configs')) {
      return opts.biz === 'fail' ? fail(500) : ok(bizView());
    }
    if (path.includes('/info-sources')) {
      return opts.info === 'fail' ? fail(500) : ok(infoView());
    }
    return fail(404);
  });
}

function renderSources(route = '/sources') {
  window.location.hash = `#${route}`;
  const fetchMock = stubFetch();
  vi.stubGlobal('fetch', fetchMock);
  // 直挂不带 route prop：走组件内 hash 订阅（Tab 切换 hash 驱动重渲染）
  render(<Sources />);
  return fetchMock;
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  localStorage.clear();
  window.location.hash = '';
});

describe('Sources 源管理页（V2.3-M23 T204）', () => {
  it('默认 Tab：#/sources 渲染资讯源面板（info 默认高频运维面），Tab 高亮与 aria-selected', async () => {
    renderSources('/sources');

    expect(await screen.findByTestId('sources-page')).toBeInTheDocument();
    expect(screen.getByTestId('info-sources-panel')).toBeInTheDocument();
    expect(screen.queryByTestId('biz-sources-panel')).toBeNull();
    const tabs = screen.getByTestId('sources-tabs');
    expect(within(tabs).getByTestId('sources-tab-info')).toHaveAttribute(
      'aria-selected',
      'true',
    );
    expect(within(tabs).getByTestId('sources-tab-biz')).toHaveAttribute(
      'aria-selected',
      'false',
    );
  });

  it('?tab=biz 直达业务数据源面板，且挂载即聚合两列表端点（概览条零新端点）', async () => {
    const fetchMock = renderSources('/sources?tab=biz');

    expect(await screen.findByTestId('biz-sources-panel')).toBeInTheDocument();
    expect(screen.queryByTestId('info-sources-panel')).toBeNull();
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some((call) => String(call[0]).includes('/info-sources')),
      ).toBe(true),
    );
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some((call) => String(call[0]).includes('/datasource-configs')),
      ).toBe(true),
    );
  });

  it('Tab 切换：点 biz → 面板替换 + hash 随行 ?tab=biz；切回 info 回默认面板', async () => {
    const user = userEvent.setup();
    renderSources('/sources');

    expect(await screen.findByTestId('info-sources-panel')).toBeInTheDocument();

    await user.click(screen.getByTestId('sources-tab-biz'));
    expect(await screen.findByTestId('biz-sources-panel')).toBeInTheDocument();
    expect(screen.queryByTestId('info-sources-panel')).toBeNull();
    expect(window.location.hash).toBe('#/sources?tab=biz');

    await user.click(screen.getByTestId('sources-tab-info'));
    expect(await screen.findByTestId('info-sources-panel')).toBeInTheDocument();
    expect(window.location.hash).toBe('#/sources?tab=info');
  });

  it('Tab 切换保留既有定位参数：?source= 跨 Tab 不丢（回 info Tab 可重新定位）', async () => {
    const user = userEvent.setup();
    renderSources('/sources?source=mw_topstories');

    expect(await screen.findByTestId('info-sources-panel')).toBeInTheDocument();

    await user.click(screen.getByTestId('sources-tab-biz'));
    expect(await screen.findByTestId('biz-sources-panel')).toBeInTheDocument();
    expect(window.location.hash).toBe('#/sources?source=mw_topstories&tab=biz');
  });

  it('概览条前端聚合：资讯源 启用 1/2 · 今日入库 7；业务源 健康 1/3', async () => {
    renderSources('/sources');

    const info = await screen.findByTestId('sources-overview-info');
    await waitFor(() => expect(info).toHaveTextContent('资讯源 启用 1/2 · 今日入库 7'));
    expect(screen.getByTestId('sources-overview-biz')).toHaveTextContent('业务源 健康 1/3');
  });

  it('概览条降级：任一列表端点失败 → 对应段静默显示 —，面板主路径不受阻', async () => {
    const fetchMock = stubFetch({ biz: 'fail' });
    window.location.hash = '#/sources';
    vi.stubGlobal('fetch', fetchMock);
    render(<Sources />);

    expect(await screen.findByTestId('info-sources-panel')).toBeInTheDocument();
    expect(screen.getByTestId('info-source-card-jin10_flash')).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getByTestId('sources-overview-info')).toHaveTextContent(
        '资讯源 启用 1/2',
      ),
    );
    expect(screen.getByTestId('sources-overview-biz')).toHaveTextContent('业务源 —');
  });

  it('统一搜索：输入过滤当前 Tab 卡片（info：按源名/代码包含，不区分大小写）', async () => {
    const user = userEvent.setup();
    renderSources('/sources');

    expect(await screen.findByTestId('info-source-card-jin10_flash')).toBeInTheDocument();
    expect(screen.getByTestId('info-source-card-mw_topstories')).toBeInTheDocument();

    await user.type(screen.getByTestId('sources-search'), 'JIN10');

    await waitFor(() =>
      expect(screen.queryByTestId('info-source-card-mw_topstories')).toBeNull(),
    );
    expect(screen.getByTestId('info-source-card-jin10_flash')).toBeInTheDocument();
  });

  it('统一搜索：当前 Tab 无命中显示区分性空态（非种子异常文案）', async () => {
    const user = userEvent.setup();
    renderSources('/sources');

    expect(await screen.findByTestId('info-sources-panel')).toBeInTheDocument();

    await user.type(screen.getByTestId('sources-search'), '不存在的源');

    expect(await screen.findByTestId('info-sources-filtered-empty')).toHaveTextContent(
      '没有匹配「不存在的源」的资讯源',
    );
  });

  it('跨 Tab 命中轻提示：info Tab 搜索业务源名 → 提示「业务数据源」命中 1 个源', async () => {
    const user = userEvent.setup();
    renderSources('/sources');

    await screen.findByTestId('info-source-card-jin10_flash');

    await user.type(screen.getByTestId('sources-search'), '事件源');

    expect(await screen.findByTestId('sources-cross-tab-hint')).toHaveTextContent(
      '「业务数据源」命中 1 个源',
    );
  });
});
