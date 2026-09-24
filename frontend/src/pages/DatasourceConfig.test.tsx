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
    fallbackChain: ['eastmoney', 'tencent'],
    availableProviders: ['eastmoney', 'tencent'],
    params: {
      quoteUrl: 'https://push2.eastmoney.com/api/qt/stock/get',
      fields: 'f43,f57',
    },
    health: { lastEventType: 'OK', lastEventAt: '2026-09-22T02:00:00Z', errors24h: 0 },
    updatedAt: '2026-09-22T01:00:00Z',
    effectiveModes: { fallbackChain: LIVE },
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
        fallbackChain: ['eastmoney', 'cninfo'],
        availableProviders: ['eastmoney', 'cninfo'],
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
        fallbackChain: ['local'],
        availableProviders: ['local'],
        params: {},
      }),
      sourceOf({
        sourceCode: 'FINANCE',
        label: '财务源',
        fallbackChain: ['eastmoney', 'sina'],
        availableProviders: ['eastmoney', 'sina'],
        params: {},
      }),
      sourceOf({ sourceCode: 'VALUATION', label: '估值源', params: {} }),
      sourceOf({
        sourceCode: 'NEWS',
        label: '新闻源',
        fallbackChain: ['sina'],
        availableProviders: ['sina'],
        params: {},
      }),
      sourceOf({
        sourceCode: 'POLICY',
        label: '政策源',
        fallbackChain: ['gov'],
        availableProviders: ['gov'],
        params: {},
      }),
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

  // —— ADR-0033 降级链可视化 ——

  it('降级链区块：多 provider 源按序渲染主源徽章 → 备选链，单 provider 源显示暂无备选源', async () => {
    renderPage(makeStore());

    const chain = await screen.findByTestId('datasource-chain-QUOTE');
    expect(chain).toHaveTextContent('东方财富·主源');
    expect(chain).toHaveTextContent('→');
    expect(chain).toHaveTextContent('腾讯');

    // 单 provider 源（新闻源）无备选、无编辑入口；多 provider 源有「编辑降级链」
    expect(screen.getByTestId('datasource-chain-NEWS')).toHaveTextContent('暂无备选源');
    expect(screen.getByTestId('datasource-chain-NEWS')).toHaveTextContent('新浪·主源');
    expect(screen.queryByTestId('datasource-edit-chain-NEWS')).toBeNull();
    expect(screen.getByTestId('datasource-edit-chain-QUOTE')).toBeInTheDocument();
  });

  // —— ADR-0034 T58 页面冒烟：财务/公告备选链 chips ——

  it('财务卡链 chips：东方财富主源 → 新浪备选（ADR-0034 注册表驱动自动出现）', async () => {
    renderPage(makeStore());

    const chain = await screen.findByTestId('datasource-chain-FINANCE');
    expect(chain).toHaveTextContent('东方财富·主源');
    expect(chain).toHaveTextContent('新浪');
    expect(screen.getByTestId('datasource-edit-chain-FINANCE')).toBeInTheDocument();
  });

  it('公告卡链 chips：东方财富主源 → 巨潮资讯备选（ADR-0034 T57，一条映射微调）', async () => {
    renderPage(makeStore());

    const chain = await screen.findByTestId('datasource-chain-ANNOUNCE');
    expect(chain).toHaveTextContent('东方财富·主源');
    expect(chain).toHaveTextContent('巨潮资讯');
    expect(screen.getByTestId('datasource-edit-chain-ANNOUNCE')).toBeInTheDocument();
  });

  it('编辑降级链 Dialog：点亮顺序即链序（首个为主源），保存走 PATCH fallbackChain', async () => {
    const store = makeStore();
    renderPage(store);
    const user = userEvent.setup();
    await screen.findByTestId('datasource-card-QUOTE');

    await user.click(screen.getByTestId('datasource-edit-chain-QUOTE'));
    // 初始点亮 = 当前链 [eastmoney, tencent]；先清空再按新顺序点亮（腾讯主源 → 东财备选）
    await user.click(screen.getByTestId('datasource-chain-toggle-QUOTE-eastmoney'));
    await user.click(screen.getByTestId('datasource-chain-toggle-QUOTE-tencent'));
    await user.click(screen.getByTestId('datasource-chain-toggle-QUOTE-tencent'));
    await user.click(screen.getByTestId('datasource-chain-toggle-QUOTE-eastmoney'));

    expect(screen.getByTestId('datasource-chain-preview-QUOTE')).toHaveTextContent(
      '腾讯 → 东方财富',
    );
    await user.click(screen.getByTestId('datasource-chain-save-QUOTE'));

    await waitFor(() => {
      const patch = store.fetchMock.mock.calls.find(
        (call) =>
          String(call[1]?.method) === 'PATCH' &&
          String(call[0]).endsWith('/datasource-configs/QUOTE'),
      );
      expect(patch).toBeTruthy();
      expect(JSON.parse(String(patch?.[1]?.body)).fallbackChain).toEqual([
        'tencent',
        'eastmoney',
      ]);
    });
    // 保存后卡片按响应刷新：腾讯主源徽章在前
    expect(await screen.findByTestId('datasource-chain-QUOTE')).toHaveTextContent('腾讯·主源');
  });

  it('清空备选（仅主源）：全部熄灭保存空链，卡片回到主源单链', async () => {
    const store = makeStore();
    renderPage(store);
    const user = userEvent.setup();
    await screen.findByTestId('datasource-card-QUOTE');

    await user.click(screen.getByTestId('datasource-edit-chain-QUOTE'));
    await user.click(screen.getByTestId('datasource-chain-toggle-QUOTE-eastmoney'));
    await user.click(screen.getByTestId('datasource-chain-toggle-QUOTE-tencent'));
    expect(screen.getByTestId('datasource-chain-preview-QUOTE')).toHaveTextContent('仅默认主源');
    await user.click(screen.getByTestId('datasource-chain-save-QUOTE'));

    await waitFor(() => {
      const patch = store.fetchMock.mock.calls.find(
        (call) =>
          String(call[1]?.method) === 'PATCH' &&
          String(call[0]).endsWith('/datasource-configs/QUOTE'),
      );
      expect(JSON.parse(String(patch?.[1]?.body)).fallbackChain).toEqual([]);
    });
    // 响应（服务端折算后单元素链）驱动卡片显示「暂无备选源」
    expect(await screen.findByTestId('datasource-chain-QUOTE')).toHaveTextContent('暂无备选源');
  });

  it('降级链保存反馈：降级链已更新，下一次取数生效（即时生效枚举）', async () => {
    renderPage(makeStore());
    const user = userEvent.setup();
    await screen.findByTestId('datasource-card-QUOTE');

    await user.click(screen.getByTestId('datasource-edit-chain-QUOTE'));
    await user.click(screen.getByTestId('datasource-chain-toggle-QUOTE-tencent'));
    await user.click(screen.getByTestId('datasource-chain-save-QUOTE'));

    await waitFor(() =>
      expect(screen.getByTestId('datasource-feedback-QUOTE')).toHaveTextContent(
        '降级链已更新，下一次取数生效',
      ),
    );
    expect(screen.getByTestId('datasource-feedback-QUOTE')).toHaveTextContent('即时生效：降级链');
  });

  it('降级链未改保存不发 PATCH（取消/等价链直关）', async () => {
    const store = makeStore();
    renderPage(store);
    const user = userEvent.setup();
    await screen.findByTestId('datasource-card-QUOTE');

    await user.click(screen.getByTestId('datasource-edit-chain-QUOTE'));
    await user.click(screen.getByTestId('datasource-chain-save-QUOTE'));

    const patchCalls = store.fetchMock.mock.calls.filter(
      (call) => String(call[1]?.method) === 'PATCH',
    );
    expect(patchCalls).toHaveLength(0);
    expect(screen.queryByTestId('datasource-feedback-QUOTE')).toBeNull();
  });

  it('降级链保存失败仅本卡反馈错误原样展示', async () => {
    const store = makeStore({ failQuotePatch: true });
    renderPage(store);
    const user = userEvent.setup();
    await screen.findByTestId('datasource-card-QUOTE');

    await user.click(screen.getByTestId('datasource-edit-chain-QUOTE'));
    await user.click(screen.getByTestId('datasource-chain-toggle-QUOTE-tencent'));
    await user.click(screen.getByTestId('datasource-chain-save-QUOTE'));

    await waitFor(() =>
      expect(screen.getByTestId('datasource-feedback-QUOTE')).toHaveTextContent(
        'timeoutMillis: 须为正整数',
      ),
    );
  });

  it('估值源链与行情源同款渲染（eastmoney → tencent 备选链）', async () => {
    renderPage(makeStore());

    expect(await screen.findByTestId('datasource-chain-VALUATION')).toHaveTextContent(
      '东方财富·主源',
    );
    expect(screen.getByTestId('datasource-chain-VALUATION')).toHaveTextContent('腾讯');
    expect(screen.getByTestId('datasource-edit-chain-VALUATION')).toBeInTheDocument();
  });
});
