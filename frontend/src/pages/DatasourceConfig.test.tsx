import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DatasourceConfig } from '@/pages/DatasourceConfig';
import type { DataSourceCardView, DataSourceConfigView } from '@/types/datasourceConfig';

// —— fetch mock：datasource-configs 组（GET/PATCH/连通性测试，状态化可变异） ——

const ok = (data: unknown) => ({
  ok: true,
  status: 200,
  json: async () => ({ code: 0, msg: 'ok', data, traceId: 't' }),
});
const fail = (status: number, code: number, msg: string) => ({
  ok: false,
  status,
  json: async () => ({ code, msg, data: null, traceId: 't' }),
});

const LIVE = 'LIVE' as const;

function sourceOf(overrides: Partial<DataSourceCardView> = {}): DataSourceCardView {
  return {
    sourceCode: 'QUOTE',
    label: '行情源',
    enabled: true,
    mode: 'REAL',
    timeoutMillis: 1500,
    retries: 0,
    cacheTtlSeconds: 5,
    params: {
      quoteUrl: 'https://push2.eastmoney.com/api/qt/stock/get',
      fields: 'f43,f57',
    },
    health: { lastEventType: 'OK', lastEventAt: '2026-09-22T02:00:00Z', errors24h: 0 },
    updatedAt: '2026-09-22T01:00:00Z',
    effectiveModes: {},
    ...overrides,
  };
}

function fullView(): DataSourceConfigView {
  return {
    sources: [
      sourceOf(),
      sourceOf({
        sourceCode: 'ANNOUNCE',
        label: '公告源',
        mode: 'MOCK',
        timeoutMillis: 2000,
        cacheTtlSeconds: 300,
        params: {
          announceUrl: 'https://np-anotice-stock.eastmoney.com/api/security/ann',
          announcePageSize: 3,
          announceDetailUrlTemplate: 'https://pdf.dfcfw.com/pdf/H2_{art_code}_1.pdf',
        },
        health: { lastEventType: null, lastEventAt: null, errors24h: 0 },
      }),
      sourceOf({
        sourceCode: 'EVENT',
        label: '事件源',
        timeoutMillis: 500,
        mode: 'REAL',
        params: {},
      }),
      sourceOf({ sourceCode: 'FINANCE', label: '财务源', params: {} }),
      sourceOf({ sourceCode: 'VALUATION', label: '估值源', params: {} }),
      sourceOf({ sourceCode: 'NEWS', label: '新闻源', params: {} }),
      sourceOf({ sourceCode: 'POLICY', label: '政策源', params: {} }),
    ],
    aggregation: {
      detailTimeoutMillis: 2000,
      updatedAt: '2026-09-22T01:00:00Z',
      effectiveModes: { detailTimeoutMillis: LIVE },
    },
  };
}

interface StoreOpts {
  view?: DataSourceConfigView;
  failGet?: boolean;
  failQuotePatch?: boolean;
}

/** 状态化 mock：GET 返回视图副本；PATCH 返回合并后的卡片。 */
function makeStore({ view = fullView(), failGet = false, failQuotePatch = false }: StoreOpts = {}) {
  const state = { view };
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    const path = String(url);
    if (path.includes('/connectivity-test')) {
      return ok({
        ok: true,
        latencyMillis: 812,
        itemCount: 3,
        mode: 'REAL',
        error: null,
        note: null,
      });
    }
    if (path.includes('/aggregation/global') && init?.method === 'PATCH') {
      const body = JSON.parse(String(init.body)) as { detailTimeoutMillis?: number };
      state.view = {
        ...state.view,
        aggregation: {
          ...state.view.aggregation,
          detailTimeoutMillis: body.detailTimeoutMillis ?? state.view.aggregation.detailTimeoutMillis,
        },
      };
      return ok(state.view.aggregation);
    }
    const patchMatch = path.match(/\/datasource-configs\/([A-Z]+)$/);
    if (patchMatch && init?.method === 'PATCH') {
      if (failQuotePatch && patchMatch[1] === 'QUOTE') {
        return fail(400, 2001, 'timeoutMillis: 须为正整数');
      }
      const body = JSON.parse(String(init.body)) as Record<string, unknown>;
      const updated = state.view.sources.map((s) =>
        s.sourceCode === patchMatch[1] ? { ...s, ...body, params: { ...s.params, ...(body.params as object) } } : s,
      );
      const saved = updated.find((s) => s.sourceCode === patchMatch[1]);
      state.view = { ...state.view, sources: updated };
      return ok(saved);
    }
    if (path.includes('/datasource-configs')) {
      if (failGet) {
        return fail(500, 50000, '服务异常');
      }
      return ok(state.view);
    }
    return ok(null);
  });
  return { fetchMock, state };
}

function renderPage(store: ReturnType<typeof makeStore>) {
  vi.stubGlobal('fetch', store.fetchMock);
  render(<DatasourceConfig />);
}

afterEach(() => {
  vi.unstubAllGlobals();
  cleanup();
});

describe('DatasourceConfig 页面', () => {
  it('加载骨架 → 7 源卡 + 聚合总超时条渲染，健康徽章与空态', async () => {
    const store = makeStore();
    renderPage(store);

    expect(screen.getByTestId('datasource-config-loading')).toBeInTheDocument();
    expect(await screen.findByTestId('datasource-aggregation-card')).toBeInTheDocument();

    // 7 张卡 + 事件源只读说明；健康徽章：OK=成功、从未抓取=暂无抓取记录（PRD 场景 3.5 空态）
    expect(await screen.findByTestId('datasource-card-QUOTE')).toBeInTheDocument();
    expect(screen.getByTestId('datasource-card-EVENT')).toBeInTheDocument();
    expect(screen.getByTestId('datasource-health-QUOTE')).toHaveTextContent('成功');
    expect(screen.getByTestId('datasource-health-ANNOUNCE')).toHaveTextContent('暂无抓取记录');
    expect(screen.getByText('读取本地异动表，无外部端点')).toBeInTheDocument();

    // 事件源只读卡：无模式切换/编辑参数入口
    expect(screen.queryByTestId('datasource-mode-real-EVENT')).toBeNull();
    expect(screen.queryByTestId('datasource-edit-params-EVENT')).toBeNull();
  });

  it('mock 模式卡顶常显提示条；真实→mock 切换弹确认（PRD 风险对策）', async () => {
    const store = makeStore();
    renderPage(store);
    const user = userEvent.setup();
    await screen.findByTestId('datasource-card-QUOTE');

    // QUOTE 为 REAL：无提示条；切 mock 弹确认
    expect(screen.queryByTestId('datasource-mock-banner-QUOTE')).toBeNull();
    await user.click(screen.getByTestId('datasource-mode-mock-QUOTE'));

    const confirm = await screen.findByTestId('datasource-mode-confirm-QUOTE');
    expect(confirm).toBeInTheDocument();
    await user.click(confirm);

    // 确认后保存成功：卡顶提示条出现（本地状态合并 mode=MOCK）
    expect(await screen.findByTestId('datasource-mock-banner-QUOTE')).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getByTestId('datasource-feedback-QUOTE')).toHaveTextContent('已保存'),
    );
  });

  it('非法值前端拦截不发请求（超时 0 / 重试 4），原值保留', async () => {
    const store = makeStore();
    renderPage(store);
    const user = userEvent.setup();
    await screen.findByTestId('datasource-card-QUOTE');

    await user.clear(screen.getByTestId('datasource-timeout-QUOTE'));
    await user.type(screen.getByTestId('datasource-timeout-QUOTE'), '0');
    await user.clear(screen.getByTestId('datasource-retries-QUOTE'));
    await user.type(screen.getByTestId('datasource-retries-QUOTE'), '4');
    await user.click(screen.getByTestId('datasource-save-QUOTE'));

    expect(await screen.findByText('须为正整数')).toBeInTheDocument();
    expect(screen.getByText('须为 0~3 的整数')).toBeInTheDocument();
    const patchCalls = store.fetchMock.mock.calls.filter(
      (call) => String(call[1]?.method) === 'PATCH' && String(call[0]).endsWith('/QUOTE'),
    );
    expect(patchCalls).toHaveLength(0);
  });

  it('单卡保存失败仅该卡反馈，不污染他卡（三态规范）', async () => {
    const store = makeStore({ failQuotePatch: true });
    renderPage(store);
    const user = userEvent.setup();
    await screen.findByTestId('datasource-card-QUOTE');

    await user.clear(screen.getByTestId('datasource-timeout-QUOTE'));
    await user.type(screen.getByTestId('datasource-timeout-QUOTE'), '3000');
    await user.click(screen.getByTestId('datasource-save-QUOTE'));

    // QUOTE 卡报错并保留原输入值；FINANCE 卡无反馈条
    await waitFor(() =>
      expect(screen.getByTestId('datasource-feedback-QUOTE')).toHaveTextContent(
        'timeoutMillis: 须为正整数',
      ),
    );
    expect(screen.getByTestId('datasource-timeout-QUOTE')).toHaveValue('3000');
    // 其他卡无反馈条（idle 不渲染 DOM）
    expect(screen.queryByTestId('datasource-feedback-FINANCE')).toBeNull();
  });

  it('聚合总超时保存即生效反馈（即时生效枚举）', async () => {
    const store = makeStore();
    renderPage(store);
    const user = userEvent.setup();
    await screen.findByTestId('datasource-aggregation-card');

    await user.clear(screen.getByTestId('datasource-aggregation-timeout'));
    await user.type(screen.getByTestId('datasource-aggregation-timeout'), '3000');
    await user.click(screen.getByTestId('datasource-aggregation-save'));

    await waitFor(() =>
      expect(screen.getByTestId('datasource-aggregation-feedback')).toHaveTextContent('已保存'),
    );
    expect(screen.getByTestId('datasource-aggregation-feedback')).toHaveTextContent('即时生效');
    expect(store.state.view.aggregation.detailTimeoutMillis).toBe(3000);
  });

  it('连通性测试内联结果（成功：耗时 + 条数）', async () => {
    const store = makeStore();
    renderPage(store);
    const user = userEvent.setup();
    await screen.findByTestId('datasource-card-QUOTE');

    await user.click(screen.getByTestId('datasource-connect-QUOTE'));

    const result = await screen.findByTestId('datasource-connect-result-QUOTE');
    expect(result).toHaveTextContent('抓取成功 · 812ms · 3 条');
  });

  it('编辑参数 Dialog：URL 非法拦截，合法保存走 PATCH params', async () => {
    const store = makeStore();
    renderPage(store);
    const user = userEvent.setup();
    await screen.findByTestId('datasource-card-QUOTE');

    await user.click(screen.getByTestId('datasource-edit-params-QUOTE'));
    const urlInput = await screen.findByTestId('datasource-param-QUOTE-quoteUrl');
    await user.clear(urlInput);
    await user.type(urlInput, 'not-a-url');
    await user.click(screen.getByTestId('datasource-params-save-QUOTE'));

    expect(await screen.findByText(/URL 须以 http/)).toBeInTheDocument();

    await user.clear(urlInput);
    await user.type(urlInput, 'https://quote.example.com/get');
    await user.click(screen.getByTestId('datasource-params-save-QUOTE'));

    await waitFor(() => {
      const patch = store.fetchMock.mock.calls.find(
        (call) =>
          String(call[1]?.method) === 'PATCH' &&
          String(call[0]).endsWith('/QUOTE') &&
          String(call[0]).includes('/datasource-configs/'),
      );
      expect(patch).toBeTruthy();
      expect(JSON.parse(String(patch?.[1]?.body)).params.quoteUrl).toBe(
        'https://quote.example.com/get',
      );
    });
  });

  it('整页加载失败：错误 + 重试', async () => {
    const store = makeStore({ failGet: true });
    renderPage(store);

    expect(await screen.findByTestId('datasource-config-error')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('服务异常');

    // 重试成功恢复
    store.fetchMock.mockImplementationOnce(async () => ok(fullView()));
    await userEvent.setup().click(screen.getByTestId('datasource-config-retry'));
    expect(await screen.findByTestId('datasource-aggregation-card')).toBeInTheDocument();
  });

  it('取数条数仅有分页语义的源展示（公告 3 / 行情 —）', async () => {
    renderPage(makeStore());

    expect(await screen.findByTestId('datasource-count-ANNOUNCE')).toHaveValue('3');
    expect(screen.queryByTestId('datasource-count-QUOTE')).toBeNull();
    expect(screen.queryByTestId('datasource-count-QUOTE-EVENT')).toBeNull();
  });
});
