import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { EventCard, EventStreamPageView } from '@/types/eventStream';

// —— fetch mock：对齐后端 EventStreamController 双模式契约（M25 T220：page 出现即页码模式
//    {total, items, page, size} offset 语义；T224 前端接入 M9 分页） ——

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

function pageViewOf(
  items: EventCard[],
  total = items.length,
  page = 1,
  size = 20,
): EventStreamPageView {
  return { total, items, page, size };
}

/** 按路径前缀分发的 fetch mock。 */
function stubFetch(
  routes: Array<{ path: string; respond: (url: string) => ReturnType<typeof ok> | ReturnType<typeof fail> }>,
) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    for (const route of routes) {
      if (url.startsWith(route.path)) return route.respond(url);
    }
    return fail(404, 50000, `unexpected fetch: ${url}`);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

/** 相对路径 URL → page/size 参数读取（http 层走相对路径，不能 new URL）。 */
function paramOf(url: string, key: string): string | null {
  const qs = url.split('?')[1] ?? '';
  return new URLSearchParams(qs).get(key);
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  cleanup();
  localStorage.clear();
  window.location.hash = '';
});

describe('Events 事件流页（T127 + T224 分页化，#/events）', () => {
  it('默认首页请求 page=1&size=20（页码模式）：卡片字段齐备，「共 N 条」与分页 total 一致，游标形态退役', async () => {
    const fetchMock = stubFetch([
      { path: '/api/v1/events', respond: () => ok(pageViewOf([cardOf()], 30)) },
    ]);

    const { Events } = await import('@/pages/Events');
    render(<Events />);

    expect(await screen.findByTestId('event-card-9')).toBeInTheDocument();
    // T230 抽查：统一页头（h1 + 副标题）
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('事件流');
    expect(screen.getByTestId('page-header-subtitle')).toHaveTextContent('按市场/类型/行业/重要度/方向筛选');
    expect(screen.getByTestId('event-type-9')).toHaveTextContent('政策发布');
    expect(screen.getByTestId('event-direction-9')).toHaveTextContent('利好');
    expect(screen.getByTestId('event-importance-9')).toHaveTextContent('高');
    expect(screen.getByTestId('event-time-9')).toHaveTextContent('2026-09-22');
    // 页头「共 N 条」与分页条 total 同源一致
    expect(screen.getByTestId('events-total')).toHaveTextContent('30');
    expect(screen.getByTestId('events-pagination-total')).toHaveTextContent('30');
    expect(screen.getByTestId('events-pagination-page-indicator')).toHaveTextContent('1 / 2');
    // 请求线：页码模式（page/size），游标退役（无 beforeId / 无「加载更多」按钮）
    const firstCall = String(fetchMock.mock.calls[0][0]);
    expect(firstCall).toContain('/api/v1/events');
    expect(paramOf(firstCall, 'page')).toBe('1');
    expect(paramOf(firstCall, 'size')).toBe('20');
    expect(firstCall).not.toContain('beforeId=');
    expect(screen.queryByTestId('events-load-more')).toBeNull();
    // 无筛选参数
    expect(firstCall).not.toContain('type=');
    expect(firstCall).not.toContain('industry=');
  });

  it('方向徽章沿 A 股惯例配色：利好红 / 利空绿 / 中性灰', async () => {
    stubFetch([
      {
        path: '/api/v1/events',
        respond: () =>
          ok(
            pageViewOf([
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
    // T229 抽查：方向徽章走方向轨单点 directionToneClass（利好红 / 利空绿 / 中性灰）
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
            pageViewOf([
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
    stubFetch([{ path: '/api/v1/events', respond: () => ok(pageViewOf([cardOf()])) }]);
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
    stubFetch([{ path: '/api/v1/events', respond: () => ok(pageViewOf([cardOf()])) }]);
    const { Events } = await import('@/pages/Events');
    render(<Events />);

    await screen.findByTestId('event-card-9');
    expect(screen.getByTestId('event-quote-9')).toHaveTextContent('下调金融机构存款准备金率 0.5 个百分点');
    const link = screen.getByTestId('event-link-9');
    expect(link).toHaveAttribute('href', 'https://example.com/n/1009');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noreferrer');
  });

  it('翻页：点击第 2 页 → page=2 请求，数据替换（非追加），页码指示与卡片联动', async () => {
    const fetchMock = stubFetch([
      {
        path: '/api/v1/events',
        respond: (url) => {
          const page = paramOf(url, 'page') ?? '1';
          return page === '2'
            ? ok(pageViewOf([cardOf({ id: 8, summary: '第二页事件' })], 30, 2))
            : ok(pageViewOf([cardOf({ id: 9 })], 30, 1));
        },
      },
    ]);

    const { Events } = await import('@/pages/Events');
    render(<Events />);

    await screen.findByTestId('event-card-9');
    const user = userEvent.setup();
    await user.click(screen.getByTestId('events-pagination-page-2'));

    expect(await screen.findByTestId('event-card-8')).toBeInTheDocument();
    // 数据替换：第 1 页卡片不再渲染（非游标追加）
    expect(screen.queryByTestId('event-card-9')).toBeNull();
    expect(screen.getByTestId('events-pagination-page-indicator')).toHaveTextContent('2 / 2');
    const lastCall = String(fetchMock.mock.calls.at(-1)?.[0]);
    expect(paramOf(lastCall, 'page')).toBe('2');
    expect(lastCall).not.toContain('beforeId=');
  });

  it('翻页失败：列表数据保留 + 错误可见可重试，重试成功后落到目标页', async () => {
    let page2Fails = true;
    stubFetch([
      {
        path: '/api/v1/events',
        respond: (url) => {
          if ((paramOf(url, 'page') ?? '1') === '2') {
            if (page2Fails) return fail(500, 50000, '翻页服务异常');
            return ok(pageViewOf([cardOf({ id: 8 })], 30, 2));
          }
          return ok(pageViewOf([cardOf({ id: 9 })], 30, 1));
        },
      },
    ]);

    const { Events } = await import('@/pages/Events');
    render(<Events />);

    await screen.findByTestId('event-card-9');
    const user = userEvent.setup();
    await user.click(screen.getByTestId('events-pagination-page-2'));

    expect(await screen.findByTestId('events-pagination-error')).toHaveTextContent('加载第 2 页失败');
    // 在途失败不清列表（第 1 页数据保留）
    expect(screen.getByTestId('event-card-9')).toBeInTheDocument();

    page2Fails = false;
    await user.click(screen.getByTestId('events-pagination-retry'));
    expect(await screen.findByTestId('event-card-8')).toBeInTheDocument();
    expect(screen.getByTestId('events-pagination-page-indicator')).toHaveTextContent('2 / 2');
  });

  it('空页防御回退：末页数据收缩（items 空 + total>0）→ 静默重发末页落地（M9 §5.3）', async () => {
    let firstPageLoads = 0;
    const fetchMock = stubFetch([
      {
        path: '/api/v1/events',
        respond: (url) => {
          const page = paramOf(url, 'page') ?? '1';
          if (page === '2') {
            // 请求第 2 页时数据已收缩：空列表 + total=15（只剩 1 页）→ 触发回退
            return ok(pageViewOf([], 15, 2));
          }
          firstPageLoads += 1;
          // 首查 2 页（可点开第 2 页），回退重发时按收缩后 total 落地
          return ok(pageViewOf([cardOf({ id: 9 })], firstPageLoads === 1 ? 30 : 15, 1));
        },
      },
    ]);

    const { Events } = await import('@/pages/Events');
    render(<Events />);

    await screen.findByTestId('event-card-9');
    const user = userEvent.setup();
    await user.click(screen.getByTestId('events-pagination-page-2'));

    // 回退落地末页：第 1 页卡片可见、无翻页错误，最终请求收敛回 page=1
    expect(await screen.findByTestId('event-card-9')).toBeInTheDocument();
    await waitFor(() =>
      expect(paramOf(String(fetchMock.mock.calls.at(-1)?.[0]), 'page')).toBe('1'),
    );
    expect(screen.getByTestId('events-pagination-page-indicator')).toHaveTextContent('1 / 1');
    expect(screen.queryByTestId('events-pagination-error')).toBeNull();
    expect(screen.queryByTestId('events-empty')).toBeNull();
  });

  it('条数切换：size 10/20/50 → 回第 1 页带新 size 重查', async () => {
    const fetchMock = stubFetch([
      { path: '/api/v1/events', respond: () => ok(pageViewOf([cardOf()], 120)) },
    ]);

    const { Events } = await import('@/pages/Events');
    render(<Events />);

    await screen.findByTestId('event-card-9');
    const user = userEvent.setup();
    await user.click(screen.getByTestId('events-pagination-page-3'));
    await waitFor(() =>
      expect(paramOf(String(fetchMock.mock.calls.at(-1)?.[0]), 'page')).toBe('3'),
    );

    await user.selectOptions(screen.getByTestId('events-pagination-size'), '50');
    await waitFor(() => {
      const last = String(fetchMock.mock.calls.at(-1)?.[0]);
      expect(paramOf(last, 'page')).toBe('1'); // 条数切换回第 1 页
      expect(paramOf(last, 'size')).toBe('50');
    });
  });

  it('筛选变更回第 1 页骨架重查：第 2 页上切筛选 → page=1 带参请求，列表重置', async () => {
    const fetchMock = stubFetch([
      {
        path: '/api/v1/events',
        respond: (url) => {
          const page = paramOf(url, 'page') ?? '1';
          return page === '2'
            ? ok(pageViewOf([cardOf({ id: 8 })], 30, 2))
            : ok(pageViewOf([cardOf({ id: 9 })], 30, 1));
        },
      },
    ]);

    const { Events } = await import('@/pages/Events');
    render(<Events />);

    await screen.findByTestId('event-card-9');
    const user = userEvent.setup();
    await user.click(screen.getByTestId('events-pagination-page-2'));
    await screen.findByTestId('event-card-8');

    await user.selectOptions(screen.getByTestId('events-filter-type'), 'POLICY_RELEASE');
    await waitFor(() => {
      const last = String(fetchMock.mock.calls.at(-1)?.[0]);
      expect(paramOf(last, 'page')).toBe('1'); // 回第 1 页
      expect(last).toContain('type=POLICY_RELEASE');
      expect(last).not.toContain('beforeId=');
    });
    expect(await screen.findByTestId('event-card-9')).toBeInTheDocument();
  });

  it('行业/重要度/方向筛选参数透传（下拉可选 31 申万行业 + 枚举）', async () => {
    const fetchMock = stubFetch([
      { path: '/api/v1/events', respond: () => ok(pageViewOf([], 0)) },
    ]);

    const { Events } = await import('@/pages/Events');
    render(<Events />);
    await screen.findByTestId('events-empty');

    const industrySelect = screen.getByTestId('events-filter-industry');
    expect(within(industrySelect).getAllByRole('option')).toHaveLength(32);
    expect(within(industrySelect).getByRole('option', { name: '银行' })).toBeInTheDocument();

    const user = userEvent.setup();
    await user.selectOptions(industrySelect, '银行');
    await waitFor(() =>
      expect(
        String(fetchMock.mock.calls.at(-1)?.[0]),
      ).toContain('industry=' + encodeURIComponent('银行')),
    );

    await user.selectOptions(screen.getByTestId('events-filter-importance'), 'HIGH');
    await waitFor(() =>
      expect(String(fetchMock.mock.calls.at(-1)?.[0])).toContain('importance=HIGH'),
    );

    await user.selectOptions(screen.getByTestId('events-filter-direction'), 'BEARISH');
    await waitFor(() =>
      expect(String(fetchMock.mock.calls.at(-1)?.[0])).toContain('direction=BEARISH'),
    );
  });

  it('空态：无事件引导文案（AI 管道 L2 配额产出说明），分页条不渲染', async () => {
    stubFetch([{ path: '/api/v1/events', respond: () => ok(pageViewOf([], 0)) }]);
    const { Events } = await import('@/pages/Events');
    render(<Events />);

    expect(await screen.findByTestId('events-empty')).toBeInTheDocument();
    // T228 抽查：统一空态组件结构（标题 + 引导描述）
    expect(screen.getByTestId('empty-state-title')).toHaveTextContent('暂无事件');
    expect(screen.getByTestId('empty-state-description')).toHaveTextContent('可稍后刷新或放宽筛选');
    expect(screen.getByTestId('events-total')).toHaveTextContent('0');
    expect(screen.queryByTestId('events-pagination-root')).toBeNull();
  });

  it('错误态：首屏失败展示错误与重试，重试成功恢复列表', async () => {
    let call = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        call += 1;
        if (call === 1) return fail(500, 50000, '服务异常');
        return ok(pageViewOf([cardOf()]));
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
            pending.resolve = resolve as (value: ReturnType<typeof ok>) => void;
          }),
      ),
    );

    const { Events } = await import('@/pages/Events');
    render(<Events />);

    expect(screen.getByTestId('events-loading')).toBeInTheDocument();
    expect(screen.queryByTestId('event-card-9')).toBeNull();
    expect(screen.queryByTestId('events-empty')).toBeNull();

    pending.resolve?.(ok(pageViewOf([cardOf()])));
    expect(await screen.findByTestId('event-card-9')).toBeInTheDocument();
  });

  it('quote 缺失的卡片不渲染引用块（可空字段不残留空壳）', async () => {
    stubFetch([
      {
        path: '/api/v1/events',
        respond: () => ok(pageViewOf([cardOf({ id: 7, quote: null })])),
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
      { path: '/api/v1/events', respond: () => ok(pageViewOf([cardOf()])) },
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
        respond: () => ok(pageViewOf([cardOf({ id: 10, importance: 'LOW' })])),
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
      { path: '/api/v1/events', respond: () => ok(pageViewOf([cardOf()])) },
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

  // —— 落点聚焦（M20 T173；T224 从简裁量留档：页码模式下聚焦当前页命中行，不定向跨页回溯） ——

  it('focus 参数命中当前页：该事件卡高亮环（value-score 下钻落点，分页化后保留）', async () => {
    stubFetch([
      {
        path: '/api/v1/events',
        respond: () => ok(pageViewOf([cardOf({ id: 8 }), cardOf({ id: 9 })])),
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
    stubFetch([{ path: '/api/v1/events', respond: () => ok(pageViewOf([cardOf()])) }]);
    const { Events } = await import('@/pages/Events');
    render(<Events />);

    expect(await screen.findByTestId('event-card-9')).toBeInTheDocument();
    expect(screen.queryByTestId('event-focus-9')).toBeNull();
  });
});

// —— M29 T257 三市场切换：market 过滤参数 + 行业分组容错消费 + URL 持久化 ——

describe('Events 事件流页（T257）· 三市场切换', () => {
  /** A 股默认（无 market 参数）全量 + 港股带分组响应（§5.4 契约增量）。 */
  function hkCardOf(): EventCard {
    return cardOf({
      id: 21,
      summary: '港交所印花税调整，港股交投活跃度回升',
      industries: ['软件服务'],
      subjects: [{ code: 'HK00388', name: '香港交易所', industry: '软件服务' }],
      newsId: 1021,
    });
  }

  it('M29 A股缺省零回归：请求不带 market 参数（§5.4 缺省不过滤）', async () => {
    const fetchMock = stubFetch([{ path: '/api/v1/events', respond: () => ok(pageViewOf([cardOf()])) }]);
    const { Events } = await import('@/pages/Events');
    render(<Events />);

    expect(await screen.findByTestId('event-card-9')).toBeInTheDocument();
    const firstCall = String(fetchMock.mock.calls[0][0]);
    expect(firstCall).not.toContain('market=');
    // 三市场 Tab 齐备不隐藏（拍板三）
    expect(screen.getByTestId('market-tab-A_SHARE')).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByTestId('market-tab-HK')).toBeInTheDocument();
    expect(screen.getByTestId('market-tab-US')).toBeInTheDocument();
  });

  it('M29 切港股：请求带 market=HK + 行业筛选随枚举集清空回第 1 页 + URL 持久化 + 行业分组消费', async () => {
    const fetchMock = stubFetch([
      {
        path: '/api/v1/events',
        respond: (url: string) => {
          if (url.includes('market=HK')) {
            return ok({
              ...pageViewOf([hkCardOf()], 1),
              industryFilterGroups: [
                { market: 'A_SHARE', industries: ['银行', '电子'] },
                { market: 'HK', industries: ['软件服务', '地产建筑业'] },
                { market: 'US', industries: ['银行', '软件与信息服务'] },
              ],
            });
          }
          return ok(pageViewOf([cardOf()], 30));
        },
      },
    ]);
    const { Events } = await import('@/pages/Events');
    const user = userEvent.setup();
    render(<Events />);

    // A 股默认：行业下拉回退 SW 31（响应无分组——后端在途容错）
    expect(await screen.findByTestId('event-card-9')).toBeInTheDocument();
    const industrySelect = screen.getByTestId('events-filter-industry');
    expect(within(industrySelect).getByRole('option', { name: '银行' })).toBeInTheDocument();

    // 行业筛选选 A 股口径「银行」→ 请求带 industry=银行
    await user.selectOptions(industrySelect, '银行');
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some((call) => paramOf(String(call[0]), 'industry') === '银行'),
      ).toBe(true),
    );

    // 切港股：URL 持久化 + 请求带 market=HK + 行业筛选清空（枚举集切换）+ 回第 1 页
    await user.click(screen.getByTestId('market-tab-HK'));
    expect(window.location.hash).toContain('market=HK');
    await screen.findByTestId('event-card-21');
    const hkCalls = fetchMock.mock.calls
      .map((call) => String(call[0]))
      .filter((url) => url.includes('market=HK'));
    expect(hkCalls.length).toBeGreaterThan(0);
    for (const url of hkCalls) {
      expect(paramOf(url, 'page')).toBe('1');
      expect(paramOf(url, 'industry')).toBe(null); // 枚举集切换清空（银行非港股枚举）
    }

    // 行业下拉随响应分组切换为港股枚举（拍板二：三市场口径不混排）
    expect(within(industrySelect).queryByRole('option', { name: '软件服务' })).toBeInTheDocument();
    expect(within(industrySelect).queryByRole('option', { name: '地产建筑业' })).toBeInTheDocument();
  });

  it('M29 ?market=HK 深链直达：挂载初值即港股（刷新保持），首次请求带 market=HK', async () => {
    const fetchMock = stubFetch([
      { path: '/api/v1/events', respond: () => ok(pageViewOf([hkCardOf()], 1)) },
    ]);
    window.location.hash = '#/events?market=HK';
    const { Events } = await import('@/pages/Events');
    render(<Events />);

    expect(await screen.findByTestId('event-card-21')).toBeInTheDocument();
    expect(screen.getByTestId('market-tab-HK')).toHaveAttribute('aria-selected', 'true');
    expect(String(fetchMock.mock.calls[0][0])).toContain('market=HK');
  });

  it('M29 港股空结果：如实空态 + 原因标注（拍板三），响应无分组不报错（容错回退）', async () => {
    stubFetch([
      {
        path: '/api/v1/events',
        respond: (url: string) =>
          url.includes('market=HK') ? ok(pageViewOf([], 0)) : ok(pageViewOf([cardOf()], 30)),
      },
    ]);
    const { Events } = await import('@/pages/Events');
    const user = userEvent.setup();
    render(<Events />);

    expect(await screen.findByTestId('event-card-9')).toBeInTheDocument();
    await user.click(screen.getByTestId('market-tab-HK'));
    const empty = await screen.findByTestId('events-empty');
    expect(empty).toHaveTextContent('暂无事件');
    expect(empty).toHaveTextContent('港美股资讯源接入逐步积累');
  });
});
