import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { EventCard, EventStreamView } from '@/types/eventStream';

// —— fetch mock：对齐后端 EventStreamController 契约（方案 §4.8：GET /api/v1/events） ——

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

function cardOf(overrides: Partial<EventCard> = {}): EventCard {
  return {
    id: 9,
    eventType: 'POLICY_RELEASE',
    summary: '央行降准释放流动性，银行板块受益',
    industries: ['银行', '非银金融'],
    direction: 'BULLISH',
    importance: 'HIGH',
    figures: [{ label: '存款准备金率', value: '0.5', unit: 'pct' }],
    subjects: [
      { code: 'SZ000001', name: '平安银行', industry: '银行' },
      { code: null, name: '未回联公司', industry: null },
    ],
    quote: '下调金融机构存款准备金率 0.5 个百分点',
    newsId: 1009,
    newsTitle: '央行宣布降准',
    newsUrl: 'https://example.com/n/1009',
    eventTime: '2026-09-22T07:30:00Z',
    ...overrides,
  };
}

function viewOf(
  items: EventCard[],
  total = items.length,
  nextBeforeId: number | null = null,
): EventStreamView {
  return { total, items, nextBeforeId };
}

/** 按路径前缀分发的 fetch mock。 */
function stubFetch(
  routes: Array<{ path: string; respond: () => ReturnType<typeof ok> | ReturnType<typeof fail> }>,
) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    for (const route of routes) {
      if (url.startsWith(route.path)) return route.respond();
    }
    return fail(404, 50000, `unexpected fetch: ${url}`);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  cleanup();
  localStorage.clear();
  window.location.hash = '';
});

describe('Events 事件流页（T127，#/events 第 17 页）', () => {
  it('默认拉取全量事件流（无筛选参数），卡片字段齐备：类型/方向/重要度/行业 chips/关键数字/标的/引用/外链/时间', async () => {
    const fetchMock = stubFetch([
      { path: '/api/v1/events', respond: () => ok(viewOf([cardOf()])) },
    ]);

    const { Events } = await import('@/pages/Events');
    render(<Events />);

    expect(await screen.findByTestId('event-card-9')).toBeInTheDocument();
    expect(screen.getByTestId('event-type-9')).toHaveTextContent('政策发布');
    expect(screen.getByTestId('event-direction-9')).toHaveTextContent('利好');
    expect(screen.getByTestId('event-importance-9')).toHaveTextContent('高');
    const industries = screen.getByTestId('event-industries-9');
    expect(within(industries).getByText('银行')).toBeInTheDocument();
    expect(within(industries).getByText('非银金融')).toBeInTheDocument();
    expect(screen.getByTestId('event-figures-9')).toHaveTextContent('存款准备金率: 0.5pct');
    expect(screen.getByTestId('event-time-9')).toHaveTextContent('2026-09-22');
    expect(screen.getByTestId('events-total')).toHaveTextContent('1');
    const firstCall = String(fetchMock.mock.calls[0][0]);
    expect(firstCall).toContain('/api/v1/events');
    expect(firstCall).not.toContain('type=');
    expect(firstCall).not.toContain('industry=');
  });

  it('方向徽章沿 A 股惯例配色：利好红 / 利空绿 / 中性灰', async () => {
    stubFetch([
      {
        path: '/api/v1/events',
        respond: () =>
          ok(
            viewOf([
              cardOf({ id: 1, direction: 'BULLISH' }),
              cardOf({ id: 2, direction: 'BEARISH' }),
              cardOf({ id: 3, direction: 'NEUTRAL' }),
            ]),
          ),
      },
    ]);

    const { Events } = await import('@/pages/Events');
    render(<Events />);

    await screen.findByTestId('event-card-1');
    expect(screen.getByTestId('event-direction-1').className).toContain('text-red-500');
    expect(screen.getByTestId('event-direction-2').className).toContain('text-green-500');
    expect(screen.getByTestId('event-direction-3').className).toContain('text-muted-foreground');
  });

  it('重要度视觉分层：HIGH 强于 MEDIUM 弱化 LOW（高 > 中 > 低）', async () => {
    stubFetch([
      {
        path: '/api/v1/events',
        respond: () =>
          ok(
            viewOf([
              cardOf({ id: 1, importance: 'HIGH' }),
              cardOf({ id: 2, importance: 'MEDIUM' }),
              cardOf({ id: 3, importance: 'LOW' }),
            ]),
          ),
      },
    ]);

    const { Events } = await import('@/pages/Events');
    render(<Events />);

    await screen.findByTestId('event-card-1');
    expect(screen.getByTestId('event-importance-1')).toHaveTextContent('高');
    expect(screen.getByTestId('event-importance-1').className).toContain('text-amber-300');
    expect(screen.getByTestId('event-importance-2')).toHaveTextContent('中');
    expect(screen.getByTestId('event-importance-2').className).toContain('text-amber-400');
    expect(screen.getByTestId('event-importance-3')).toHaveTextContent('低');
    expect(screen.getByTestId('event-importance-3').className).toContain('text-muted-foreground');
  });

  it('subjects 可点跳标的详情（code 非空 → #/subjects/:code；未回联仅留名不可点）', async () => {
    stubFetch([{ path: '/api/v1/events', respond: () => ok(viewOf([cardOf()])) }]);

    const { Events } = await import('@/pages/Events');
    render(<Events />);

    await screen.findByTestId('event-card-9');
    const linked = screen.getByTestId('event-subject-SZ000001');
    expect(linked).toHaveAttribute('href', '#/subjects/SZ000001');
    expect(linked).toHaveTextContent('平安银行');
    const subjects = screen.getByTestId('event-subjects-9');
    expect(within(subjects).getByText('未回联公司').closest('a')).toBeNull();
  });

  it('quote 原文引用展示 + newsUrl 新窗口外链', async () => {
    stubFetch([{ path: '/api/v1/events', respond: () => ok(viewOf([cardOf()])) }]);

    const { Events } = await import('@/pages/Events');
    render(<Events />);

    await screen.findByTestId('event-card-9');
    expect(screen.getByTestId('event-quote-9')).toHaveTextContent('下调金融机构存款准备金率 0.5 个百分点');
    const link = screen.getByTestId('event-link-9');
    expect(link).toHaveAttribute('href', 'https://example.com/n/1009');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noreferrer');
  });

  it('类型筛选变更回第 1 页：请求带 type 参数且不带 beforeId，清单重置', async () => {
    const fetchMock = stubFetch([
      { path: '/api/v1/events', respond: () => ok(viewOf([cardOf()], 1, null)) },
    ]);

    const { Events } = await import('@/pages/Events');
    render(<Events />);
    await screen.findByTestId('event-card-9');

    const user = userEvent.setup();
    await user.selectOptions(screen.getByTestId('events-filter-type'), 'POLICY_RELEASE');

    await waitFor(() => {
      const last = fetchMock.mock.calls[fetchMock.mock.calls.length - 1];
      expect(String(last[0])).toContain('type=POLICY_RELEASE');
      expect(String(last[0])).not.toContain('beforeId=');
    });
  });

  it('行业/重要度/方向筛选参数透传（下拉可选 31 申万行业 + 枚举）', async () => {
    const fetchMock = stubFetch([
      { path: '/api/v1/events', respond: () => ok(viewOf([], 0, null)) },
    ]);

    const { Events } = await import('@/pages/Events');
    render(<Events />);
    await screen.findByTestId('events-empty');

    const industrySelect = screen.getByTestId('events-filter-industry');
    // 31 申万行业 + 「全部」= 32 项
    expect(within(industrySelect).getAllByRole('option')).toHaveLength(32);
    expect(within(industrySelect).getByRole('option', { name: '银行' })).toBeInTheDocument();

    const user = userEvent.setup();
    await user.selectOptions(industrySelect, '银行');
    await waitFor(() =>
      expect(
        String(fetchMock.mock.calls[fetchMock.mock.calls.length - 1][0]),
      ).toContain('industry=' + encodeURIComponent('银行')),
    );

    await user.selectOptions(screen.getByTestId('events-filter-importance'), 'HIGH');
    await waitFor(() =>
      expect(
        String(fetchMock.mock.calls[fetchMock.mock.calls.length - 1][0]),
      ).toContain('importance=HIGH'),
    );

    await user.selectOptions(screen.getByTestId('events-filter-direction'), 'BEARISH');
    await waitFor(() =>
      expect(
        String(fetchMock.mock.calls[fetchMock.mock.calls.length - 1][0]),
      ).toContain('direction=BEARISH'),
    );
  });

  it('游标加载更多：满页给 nextBeforeId，点击带 beforeId 追加下一页', async () => {
    let call = 0;
    const fetchMock = vi.fn(async (_input: RequestInfo | URL) => {
      call += 1;
      if (call === 1) {
        return ok(viewOf([cardOf({ id: 9 })], 2, 9));
      }
      return ok(viewOf([cardOf({ id: 8 })], 2, null));
    });
    vi.stubGlobal('fetch', fetchMock);

    const { Events } = await import('@/pages/Events');
    render(<Events />);

    await screen.findByTestId('event-card-9');
    const more = screen.getByTestId('events-load-more');

    const user = userEvent.setup();
    await user.click(more);

    expect(await screen.findByTestId('event-card-8')).toBeInTheDocument();
    expect(screen.getByTestId('event-card-9')).toBeInTheDocument(); // 追加不清首屏
    expect(String(fetchMock.mock.calls[1][0])).toContain('beforeId=9');
    await waitFor(() => expect(screen.queryByTestId('events-load-more')).toBeNull()); // 尾页收起
  });

  it('尾页（nextBeforeId null）不渲染加载更多', async () => {
    stubFetch([{ path: '/api/v1/events', respond: () => ok(viewOf([cardOf()], 1, null)) }]);

    const { Events } = await import('@/pages/Events');
    render(<Events />);

    await screen.findByTestId('event-card-9');
    expect(screen.queryByTestId('events-load-more')).toBeNull();
  });

  it('空态：无事件引导文案（AI 管道 L2 配额产出说明）', async () => {
    stubFetch([{ path: '/api/v1/events', respond: () => ok(viewOf([], 0, null)) }]);

    const { Events } = await import('@/pages/Events');
    render(<Events />);

    expect(await screen.findByTestId('events-empty')).toBeInTheDocument();
    expect(screen.getByTestId('events-total')).toHaveTextContent('0');
  });

  it('错误态：首屏失败展示错误与重试，重试成功恢复列表', async () => {
    let call = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        call += 1;
        if (call === 1) return fail(500, 50000, '服务异常');
        return ok(viewOf([cardOf()]));
      }),
    );

    const { Events } = await import('@/pages/Events');
    render(<Events />);

    expect(await screen.findByTestId('events-error')).toHaveTextContent('服务异常');
    const user = userEvent.setup();
    await user.click(screen.getByTestId('events-retry'));

    expect(await screen.findByTestId('event-card-9')).toBeInTheDocument();
  });

  it('首屏加载骨架三态之一：加载中不出卡片不出空态', async () => {
    const pending = { resolve: null as ((value: ReturnType<typeof ok>) => void) | null };
    vi.stubGlobal(
      'fetch',
      vi.fn(
        () =>
          new Promise((resolve) => {
            pending.resolve = resolve;
          }),
      ),
    );

    const { Events } = await import('@/pages/Events');
    render(<Events />);

    expect(screen.getByTestId('events-loading')).toBeInTheDocument();
    expect(screen.queryByTestId('event-card-9')).toBeNull();
    expect(screen.queryByTestId('events-empty')).toBeNull();

    pending.resolve?.(ok(viewOf([cardOf()])));
    expect(await screen.findByTestId('event-card-9')).toBeInTheDocument();
  });

  it('quote 缺失的卡片不渲染引用块（可空字段不残留空壳）', async () => {
    stubFetch([
      {
        path: '/api/v1/events',
        respond: () => ok(viewOf([cardOf({ id: 7, quote: null })])),
      },
    ]);

    const { Events } = await import('@/pages/Events');
    render(<Events />);

    await screen.findByTestId('event-card-7');
    expect(screen.queryByTestId('event-quote-7')).toBeNull();
  });

  // —— T144（M17）：事件详情影响链区块 ——

  function impactView(overrides: Record<string, unknown> = {}) {
    return {
      eventId: 9,
      importance: 'HIGH',
      eligibility: 'CACHED',
      chains: [
        {
          id: 1,
          industry: '银行',
          direction: 'BULLISH',
          logicChain: '流动性宽松降低银行负债成本，信贷投放预期改善',
          basis: { newsId: 1009, signalNewsIds: [1009], quote: '下调存款准备金率 0.5 个百分点' },
          templateKey: 'POLICY_MONETARY',
          cacheState: 'AUTO',
          genMethod: 'TEMPLATE',
        },
        {
          id: 2,
          industry: '房地产',
          direction: 'BULLISH',
          logicChain: '资金面宽松支撑按揭利率下行与销售预期',
          basis: { newsId: 1009, signalNewsIds: [1009], quote: null },
          templateKey: 'POLICY_MONETARY',
          cacheState: 'AUTO',
          genMethod: 'TEMPLATE',
        },
      ],
      disclaimer: 'AI 分析仅供参考',
      ...overrides,
    };
  }

  it('影响链区块：首展拉取并渲染行业/方向/逻辑链 + 依据回溯展开 + 模板态与免责标注；再展不重复请求', async () => {
    const fetchMock = stubFetch([
      { path: '/api/v1/events/9/impact-chains', respond: () => ok(impactView()) },
      { path: '/api/v1/events', respond: () => ok(viewOf([cardOf()])) },
    ]);

    const { Events } = await import('@/pages/Events');
    render(<Events />);

    await screen.findByTestId('event-card-9');
    expect(screen.queryByTestId(`impact-chain-section-9`)).toBeNull(); // 未展开不渲染

    await userEvent.click(screen.getByTestId('impact-chain-toggle-9'));
    const section = await screen.findByTestId('impact-chain-section-9');
    expect(within(section).getByTestId('impact-chain-row-银行')).toHaveTextContent(
      '流动性宽松降低银行负债成本',
    );
    expect(within(section).getByTestId('impact-chain-row-银行')).toHaveTextContent('利好');
    expect(within(section).getByTestId('impact-chain-row-房地产')).toBeInTheDocument();
    expect(within(section).getByTestId('impact-chain-disclaimer-9')).toHaveTextContent(
      'AI 分析仅供参考',
    );
    expect(within(section).getByTestId('impact-chain-genmethod-9')).toHaveTextContent('模板');

    // 依据展开：引用原文回溯
    await userEvent.click(within(section).getByTestId('impact-chain-basis-银行'));
    expect(
      within(section).getByTestId('impact-chain-basis-detail-银行'),
    ).toHaveTextContent('下调存款准备金率 0.5 个百分点');

    // 收起再展开：读组件内缓存，不重复请求
    await userEvent.click(screen.getByTestId('impact-chain-toggle-9'));
    expect(screen.queryByTestId('impact-chain-section-9')).toBeNull();
    await userEvent.click(screen.getByTestId('impact-chain-toggle-9'));
    await screen.findByTestId('impact-chain-section-9');
    const chainCalls = fetchMock.mock.calls.filter((call) =>
      String(call[0]).includes('/events/9/impact-chains'),
    );
    expect(chainCalls).toHaveLength(1);
  });

  it('影响链区块：LOW 事件空态说明（不生成）', async () => {
    stubFetch([
      {
        path: '/api/v1/events/10/impact-chains',
        respond: () =>
          ok(
            impactView({
              eventId: 10,
              importance: 'LOW',
              eligibility: 'LOW_SKIPPED',
              chains: [],
            }),
          ),
      },
      {
        path: '/api/v1/events',
        respond: () => ok(viewOf([cardOf({ id: 10, importance: 'LOW' })])),
      },
    ]);

    const { Events } = await import('@/pages/Events');
    render(<Events />);

    await screen.findByTestId('event-card-10');
    await userEvent.click(screen.getByTestId('impact-chain-toggle-10'));
    expect(await screen.findByTestId('impact-chain-empty-10')).toHaveTextContent('低重要度事件');
  });

  it('影响链区块：请求失败错误态 + 重试可恢复', async () => {
    let failed = true;
    stubFetch([
      {
        path: '/api/v1/events/9/impact-chains',
        respond: () => (failed ? fail(500, 50000, '服务异常') : ok(impactView())),
      },
      { path: '/api/v1/events', respond: () => ok(viewOf([cardOf()])) },
    ]);

    const { Events } = await import('@/pages/Events');
    render(<Events />);

    await screen.findByTestId('event-card-9');
    await userEvent.click(screen.getByTestId('impact-chain-toggle-9'));
    expect(await screen.findByTestId('impact-chain-error-9')).toBeInTheDocument();

    failed = false;
    await userEvent.click(screen.getByTestId('impact-chain-retry-9'));
    expect(await screen.findByTestId('impact-chain-row-银行')).toBeInTheDocument();
  });

  // —— 落点聚焦（M20 T173 依据事件下钻：价值评分区块 detail.catalyst.entries[].eventId → #/events?focus=） ——

  it('focus 参数命中已加载页：该事件卡高亮环（value-score 下钻落点）', async () => {
    stubFetch([
      {
        path: '/api/v1/events',
        respond: () => ok(viewOf([cardOf({ id: 8 }), cardOf({ id: 9 })])),
      },
    ]);
    window.location.hash = '#/events?focus=9';

    const { Events } = await import('@/pages/Events');
    render(<Events />);

    expect(await screen.findByTestId('event-card-9')).toBeInTheDocument();
    const focusWrap = screen.getByTestId('event-focus-9');
    expect(focusWrap.className).toContain('ring');
    expect(screen.queryByTestId('event-focus-8')).toBeNull();
    expect(screen.getByTestId('event-card-8')).toBeInTheDocument();
  });

  it('无 focus 参数：零高亮环（存量直进行为零回归）', async () => {
    stubFetch([{ path: '/api/v1/events', respond: () => ok(viewOf([cardOf()])) }]);

    const { Events } = await import('@/pages/Events');
    render(<Events />);

    expect(await screen.findByTestId('event-card-9')).toBeInTheDocument();
    expect(screen.queryByTestId('event-focus-9')).toBeNull();
  });
});
