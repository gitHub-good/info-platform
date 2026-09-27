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
  // 直挂不带 route prop：走组件内 hash 订阅
  render(<Sources />);
  return fetchMock;
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  localStorage.clear();
  window.location.hash = '';
});

describe('Sources 源管理页（V2.4 T212 单列表分组）', () => {
  it('场景 1 单列表分组：无 Tab 切换件，资讯源分组段在前 + 业务数据源段收尾，同屏渲染', async () => {
    renderSources('/sources');

    expect(await screen.findByTestId('sources-page')).toBeInTheDocument();
    // Tab 消亡：无 tablist / tab 件
    expect(screen.queryByTestId('sources-tabs')).toBeNull();
    expect(screen.queryByRole('tab')).toBeNull();
    // 两段同屏：资讯源段在前、业务数据源段收尾（DOM 顺序断言）
    const info = await screen.findByTestId('info-sources-panel');
    const biz = screen.getByTestId('biz-sources-panel');
    expect(biz).toBeInTheDocument();
    expect(info.compareDocumentPosition(biz) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    // 资讯源沿 category 既有分组（服务端 view.groups 直供）
    expect(info).toHaveTextContent('快讯');
  });

  it('场景 2 新增入口唯一：页头「＋新增源」打开新增 Dialog；面板段无第二入口，业务段明示代码注册域', async () => {
    const user = userEvent.setup();
    renderSources('/sources');

    expect(await screen.findByTestId('info-source-card-jin10_flash')).toBeInTheDocument();
    // 面板头无新增按钮（页头全局唯一）
    expect(screen.queryByTestId('info-sources-add')).toBeNull();
    // 业务段无新增入口 + 代码注册域明示
    const bizSection = screen.getByTestId('sources-biz-section');
    expect(
      within(bizSection).getByTestId('biz-sources-code-registered-note'),
    ).toHaveTextContent('不提供新增入口');

    await user.click(screen.getByTestId('sources-add'));
    expect(await screen.findByTestId('info-source-add-save')).toBeInTheDocument();
  });

  it('场景 3 搜索跨段：输入同时过滤资讯源与业务数据源两段卡片', async () => {
    const user = userEvent.setup();
    renderSources('/sources');

    await screen.findByTestId('info-source-card-jin10_flash');
    expect(screen.getByTestId('info-source-card-mw_topstories')).toBeInTheDocument();
    // 业务源卡可见（BizSourcesPanel 卡片 testid 沿 datasource-config 前缀）
    await waitFor(() =>
      expect(screen.getByTestId('sources-biz-section').textContent).toContain('QUOTE源'),
    );

    // 「事件源」命中业务段、不命中资讯段；「JIN10」反之——两段同步过滤
    await user.type(screen.getByTestId('sources-search'), '事件源');
    await waitFor(() =>
      expect(screen.queryByTestId('info-source-card-jin10_flash')).toBeNull(),
    );
    expect(screen.queryByTestId('info-sources-panel')).toHaveTextContent('没有匹配');

    await user.clear(screen.getByTestId('sources-search'));
    await user.type(screen.getByTestId('sources-search'), 'JIN10');
    await waitFor(() =>
      expect(screen.queryByTestId('info-source-card-mw_topstories')).toBeNull(),
    );
    expect(screen.getByTestId('info-source-card-jin10_flash')).toBeInTheDocument();
  });

  it('场景 4 段定位：?section=biz 进入时滚动定位业务数据源段（scrollIntoView 调用）', async () => {
    const scrollSpy = vi.fn();
    Element.prototype.scrollIntoView = scrollSpy;
    renderSources('/sources?section=biz');

    expect(await screen.findByTestId('biz-sources-panel')).toBeInTheDocument();
    await waitFor(() => expect(scrollSpy).toHaveBeenCalled());
    delete (Element.prototype as Partial<Element>).scrollIntoView;
  });

  it('场景 4b ?source= 定位保留：进入即高亮目标资讯源卡（大盘失败跳转零断链）', async () => {
    renderSources('/sources?source=jin10_flash');

    const card = await screen.findByTestId('info-source-card-jin10_flash');
    await waitFor(() => expect(card.className).toContain('ring-2'));
  });

  it('场景 5 概览条口径不变：资讯源 启用 1/2 · 今日入库 7；业务源 健康 1/3', async () => {
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

  it('跨 Tab 命中提示机制退役：搜索非空不再渲染 sources-cross-tab-hint', async () => {
    const user = userEvent.setup();
    renderSources('/sources');

    await screen.findByTestId('info-source-card-jin10_flash');

    await user.type(screen.getByTestId('sources-search'), '事件源');
    await waitFor(() =>
      expect(screen.getByTestId('sources-biz-section').textContent).toContain('事件源'),
    );
    expect(screen.queryByTestId('sources-cross-tab-hint')).toBeNull();
  });
});
