import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type {
  MarketTopItem,
  MarketTopRankView,
  MarketTopVersionSummary,
} from '@/types/marketTop';
import { resetReadingTrackerForTest } from '@/api/readingEvent';

// —— fetch mock：对齐批 2 冻结契约（MarketTopController §4.7.1——GET /market-top + /versions，
//    加自选走既有 watchlists 三端点，埋点 POST /reading-events） ——

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

function factorsOf(): MarketTopItem['factors'] {
  return [
    { key: 'catalyst', name: '事件催化', score: 75.5, weight: 0.4 },
    { key: 'conduction', name: '行业传导', score: 60, weight: 0.2 },
    { key: 'fundamental', name: '基本面边际', score: 45, weight: 0.2 },
    { key: 'risk', name: '风险安全', score: 80, weight: 0.2 },
    { key: 'valuation', name: '估值水平', score: 50, weight: 0 },
  ];
}

function itemOf(overrides: Partial<MarketTopItem> = {}): MarketTopItem {
  return {
    rankNo: 1,
    subjectId: 101,
    subjectCode: 'SZ300024',
    subjectName: '机器人',
    totalScore: 58.4,
    finalScore: 60.1,
    percentile: 99.9,
    breakthrough: true,
    factors: factorsOf(),
    generation: 'FULL',
    diveMethod: 'LLM',
    diveSummary: '事件催化与行业传导共振，风险面可控。',
    diveDetail: {
      thesis: '近期重大合同落地驱动催化分跃升。',
      highlights: [
        { text: '签订重大合同', citations: [{ type: 'EVENT', id: 101 }] },
        { text: '行业景气上行', citations: [{ type: 'NEWS', id: 901 }] },
      ],
      risks: [{ text: '竞争加剧', citations: [{ type: 'EVENT', id: 103 }] }],
      citations: [
        { type: 'EVENT', id: 101 },
        { type: 'EVENT', id: 103 },
        { type: 'NEWS', id: 901 },
      ],
    },
    evidenceCount: 6,
    lastEventDate: '2026-09-25',
    prevRank: 3,
    changeType: 'UP',
    computedAt: '2026-09-26T10:05:00Z',
    ...overrides,
  };
}

function viewOf(items: MarketTopItem[], overrides: Partial<MarketTopRankView> = {}): MarketTopRankView {
  return {
    rankDate: '2026-09-26',
    version: 1,
    triggerSource: 'DAILY',
    batch: {
      snapshotDate: '2026-09-26',
      computedAt: '2026-09-26T10:05:00Z',
      degraded: false,
      degradedReason: null,
      funnelStats: {
        snapshotRows: 5221,
        eligible: 1820,
        excluded: { st: 201, noSignal: 3200 },
        poolSize: 300,
        divePlanned: 40,
        diveDone: 38,
        diveTemplate: 2,
        diveSkipped: 0,
        topSize: 10,
      },
      dropped: [],
      lastEvent: null,
    },
    items,
    disclaimer: '榜单为多因子信息整理与 AI 摘要，不构成投资建议',
    ...overrides,
  };
}

function versionsOf(): MarketTopVersionSummary[] {
  return [
    {
      rankDate: '2026-09-26',
      version: 2,
      triggerSource: 'DAILY',
      degraded: false,
      degradedReason: null,
      snapshotDate: '2026-09-26',
      topSize: 10,
      computedAt: '2026-09-26T10:10:00Z',
    },
    {
      rankDate: '2026-09-26',
      version: 1,
      triggerSource: 'DAILY',
      degraded: true,
      degradedReason: 'COST_CAP',
      snapshotDate: '2026-09-26',
      topSize: 10,
      computedAt: '2026-09-26T10:05:00Z',
    },
    {
      rankDate: '2026-09-25',
      version: 2,
      triggerSource: 'MANUAL',
      degraded: false,
      degradedReason: null,
      snapshotDate: '2026-09-25',
      topSize: 10,
      computedAt: '2026-09-25T10:10:00Z',
    },
    {
      rankDate: '2026-09-25',
      version: 1,
      triggerSource: 'DAILY',
      degraded: false,
      degradedReason: null,
      snapshotDate: '2026-09-25',
      topSize: 10,
      computedAt: '2026-09-25T10:05:00Z',
    },
  ];
}

interface RouteStub {
  path: string;
  respond: (
    url: string,
    init?: RequestInit,
  ) =>
    | ReturnType<typeof ok>
    | ReturnType<typeof fail>
    | Promise<Awaited<ReturnType<typeof ok>>>; // 永不 resolve 的挂起态（钉住加载骨架）
}

function stubFetch(routes: RouteStub[]) {
  const ordered = [...routes].sort((a, b) => b.path.length - a.path.length);
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    for (const route of ordered) {
      if (url.startsWith(route.path)) return route.respond(url, init);
    }
    return fail(404, 50000, `unexpected fetch: ${url}`);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

/** 三端点基线路由（榜单/版本/清单——多数用例共用）。 */
function baseRoutes(view: MarketTopRankView) {
  return [
    { path: '/api/v1/market-top/versions', respond: () => ok(versionsOf()) },
    { path: '/api/v1/market-top', respond: () => ok(view) },
    { path: '/api/v1/watchlists', respond: () => ok([]) },
    { path: '/api/v1/reading-events', respond: () => ok(null) },
  ];
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  resetReadingTrackerForTest();
  cleanup();
  localStorage.clear();
  window.location.hash = '';
});

describe('MarketTop 全市场推荐页（T184，#/market-top 第 20 页）', () => {
  it('默认拉取：卡片全套要素齐备（排名/标的跳详情/总分与合成分/百分位/突破/五维条/深析摘要/依据事件数/最近事件/变动徽章/加自选/免责常驻）', async () => {
    const fetchMock = stubFetch(baseRoutes(viewOf([itemOf()])));

    const { MarketTop } = await import('@/pages/MarketTop');
    render(<MarketTop />);

    const card = await screen.findByTestId('market-top-card-SZ300024');
    expect(card).toBeInTheDocument();
    expect(screen.getByTestId('market-top-rank-1')).toBeInTheDocument();
    expect(screen.getByTestId('market-top-subject-SZ300024')).toHaveAttribute(
      'href',
      '#/subjects/SZ300024',
    );
    expect(screen.getByTestId('market-top-total')).toHaveTextContent('58.4');
    expect(screen.getByTestId('market-top-final')).toHaveTextContent('60.1');
    expect(screen.getByTestId('market-top-percentile')).toHaveTextContent('99.9');
    expect(screen.getByTestId('market-top-breakthrough')).toHaveTextContent('有突破');
    for (const key of ['catalyst', 'conduction', 'fundamental', 'risk', 'valuation']) {
      expect(screen.getByTestId(`market-top-factor-${key}`)).toBeInTheDocument();
    }
    expect(screen.getByTestId('market-top-dive-summary')).toHaveTextContent('事件催化与行业传导共振');
    expect(screen.getByTestId('market-top-evidence')).toHaveTextContent('依据事件 6 条');
    expect(screen.getByTestId('market-top-last-event')).toHaveTextContent('2026-09-25');
    expect(screen.getByTestId('market-top-change')).toHaveTextContent('UP 3→1');
    expect(screen.getByTestId('market-top-addwatch-SZ300024')).toBeInTheDocument();
    expect(screen.getByTestId('market-top-disclaimer')).toHaveTextContent('不构成投资建议');
    // 请求线：榜单缺省无参数（最新有榜单日最大版本）+ 版本列表 + 自选清单预取
    const rankCall = String(fetchMock.mock.calls[0][0]);
    expect(rankCall).toContain('/market-top');
    expect(rankCall).not.toContain('date=');
    expect(rankCall).not.toContain('version=');
  });

  it('恰 10 张榜单卡渲染（Top3 金银铜色系 / 4+ 灰），页头生成信息与互链入口齐备', async () => {
    const items = Array.from({ length: 10 }, (_, idx) =>
      itemOf({
        rankNo: idx + 1,
        subjectId: 100 + idx,
        subjectCode: `SZ30002${idx}`,
        subjectName: `标的${idx + 1}`,
        breakthrough: false,
        changeType: 'SAME',
        prevRank: idx + 1,
      }),
    );
    stubFetch(baseRoutes(viewOf(items)));

    const { MarketTop } = await import('@/pages/MarketTop');
    render(<MarketTop />);

    await screen.findAllByTestId('market-top-list');
    for (let idx = 1; idx <= 10; idx++) {
      expect(screen.getByTestId(`market-top-rank-${idx}`)).toBeInTheDocument();
    }
    // Top3 金银铜 / 4+ 灰（色系裁量沿 token）
    expect(screen.getByTestId('market-top-rank-1').className).toContain('text-amber-300');
    expect(screen.getByTestId('market-top-rank-2').className).toContain('text-slate-300');
    expect(screen.getByTestId('market-top-rank-3').className).toContain('text-orange-400');
    expect(screen.getByTestId('market-top-rank-4').className).toContain('text-muted-foreground');
    // 页头生成信息 + 互链
    expect(screen.getByTestId('market-top-meta')).toHaveTextContent('快照日 2026-09-26');
    expect(screen.getByTestId('market-top-meta')).toHaveTextContent('每日定时');
    expect(screen.getByTestId('market-top-link-recommendations')).toHaveAttribute(
      'href',
      '#/recommendations',
    );
    expect(screen.getByTestId('market-top-link-overview')).toHaveAttribute('href', '#/overview');
  });

  it('FACTOR_ONLY 卡：标注「因子分排序（深析额度已满）」，无展开按钮', async () => {
    stubFetch(
      baseRoutes(
        viewOf([
          itemOf({
            rankNo: 2,
            generation: 'FACTOR_ONLY',
            diveMethod: null,
            diveSummary: null,
            diveDetail: null,
            changeType: 'NEW',
            prevRank: null,
          }),
        ]),
      ),
    );

    const { MarketTop } = await import('@/pages/MarketTop');
    render(<MarketTop />);

    expect(await screen.findByTestId('market-top-dive-factor-only')).toHaveTextContent(
      '因子分排序（深析额度已满）',
    );
    expect(screen.queryByTestId('market-top-dive-toggle')).toBeNull();
    expect(screen.getByTestId('market-top-change')).toHaveTextContent('NEW');
  });

  it('FULL 卡展开深析：论点 + 亮点/风险 + 引用计数徽章 + 引用 chip 下钻（EVENT → 事件流 focus / NEWS → 资讯库）', async () => {
    stubFetch(baseRoutes(viewOf([itemOf()])));

    const { MarketTop } = await import('@/pages/MarketTop');
    render(<MarketTop />);
    await screen.findByTestId('market-top-card-SZ300024');

    expect(screen.queryByTestId('market-top-dive-detail')).toBeNull();
    await userEvent.click(screen.getByTestId('market-top-dive-toggle'));

    expect(screen.getByTestId('market-top-dive-thesis')).toHaveTextContent('重大合同落地');
    expect(screen.getByTestId('market-top-dive-highlights')).toHaveTextContent('签订重大合同');
    expect(screen.getByTestId('market-top-dive-risks')).toHaveTextContent('竞争加剧');
    expect(screen.getByTestId('market-top-dive-citations')).toHaveTextContent('3');
    expect(screen.getByTestId('market-top-citation-EVENT-101')).toHaveAttribute(
      'href',
      '#/events?focus=101',
    );
    expect(screen.getByTestId('market-top-citation-NEWS-901')).toHaveAttribute(
      'href',
      '#/news-library',
    );
  });

  it('变动徽章四态配色：NEW emerald / UP amber / DOWN rose / SAME 灰', async () => {
    stubFetch(
      baseRoutes(
        viewOf([
          itemOf({ rankNo: 1, subjectCode: 'SZ1', subjectId: 1, changeType: 'NEW', prevRank: null }),
          itemOf({ rankNo: 2, subjectCode: 'SZ2', subjectId: 2, changeType: 'UP', prevRank: 5 }),
          itemOf({ rankNo: 3, subjectCode: 'SZ3', subjectId: 3, changeType: 'DOWN', prevRank: 1 }),
          itemOf({ rankNo: 4, subjectCode: 'SZ4', subjectId: 4, changeType: 'SAME', prevRank: 4 }),
        ]),
      ),
    );

    const { MarketTop } = await import('@/pages/MarketTop');
    render(<MarketTop />);

    const cards = await screen.findAllByTestId(/^market-top-card-/);
    expect(cards).toHaveLength(4);
    const badgeOf = (code: string) =>
      within(screen.getByTestId(`market-top-card-${code}`)).getByTestId('market-top-change');
    expect(badgeOf('SZ1').className).toContain('text-emerald-400');
    expect(badgeOf('SZ2')).toHaveTextContent('UP 5→2');
    expect(badgeOf('SZ2').className).toContain('text-amber-400');
    expect(badgeOf('SZ3').className).toContain('text-rose-400');
    expect(badgeOf('SZ4').className).toContain('text-muted-foreground');
  });

  it('降级横幅 COST_CAP：黄系配色与固定文案', async () => {
    stubFetch(
      baseRoutes(
        viewOf([itemOf()], {
          batch: {
            snapshotDate: '2026-09-26',
            computedAt: '2026-09-26T10:05:00Z',
            degraded: true,
            degradedReason: 'COST_CAP',
            funnelStats: {},
            dropped: [],
            lastEvent: null,
          },
        }),
      ),
    );

    const { MarketTop } = await import('@/pages/MarketTop');
    render(<MarketTop />);

    const banner = await screen.findByTestId('market-top-degraded');
    expect(banner).toHaveTextContent('深析额度触顶，部分标的按因子分排序');
    expect(banner.className).toContain('text-amber-400');
  });

  it('降级横幅 LLM_FAILURE：红系配色', async () => {
    stubFetch(
      baseRoutes(
        viewOf([itemOf()], {
          batch: {
            snapshotDate: '2026-09-26',
            computedAt: '2026-09-26T10:05:00Z',
            degraded: true,
            degradedReason: 'LLM_FAILURE',
            funnelStats: {},
            dropped: [],
            lastEvent: null,
          },
        }),
      ),
    );

    const { MarketTop } = await import('@/pages/MarketTop');
    render(<MarketTop />);

    const banner = await screen.findByTestId('market-top-degraded');
    expect(banner).toHaveTextContent('深析连续失败已暂停');
    expect(banner.className).toContain('text-rose-400');
  });

  it('漏斗摘要徽章链：全量 5221 › 粗筛池 300 › 深析候选 40 › Top10（title 带全程计数）', async () => {
    stubFetch(baseRoutes(viewOf([itemOf()])));

    const { MarketTop } = await import('@/pages/MarketTop');
    render(<MarketTop />);

    const funnel = await screen.findByTestId('market-top-funnel');
    expect(screen.getByTestId('market-top-funnel-snapshotRows')).toHaveTextContent('5221');
    expect(screen.getByTestId('market-top-funnel-poolSize')).toHaveTextContent('300');
    expect(screen.getByTestId('market-top-funnel-divePlanned')).toHaveTextContent('40');
    expect(screen.getByTestId('market-top-funnel-topSize')).toHaveTextContent('10');
    expect(funnel.getAttribute('title')).toContain('排除 ST 201');
    expect(funnel.getAttribute('title')).toContain('深析完成 38');
  });

  it('跌出名单折叠展示：默认收起，展开可见标的与昨日名次', async () => {
    stubFetch(
      baseRoutes(
        viewOf([itemOf()], {
          batch: {
            snapshotDate: '2026-09-26',
            computedAt: '2026-09-26T10:05:00Z',
            degraded: false,
            degradedReason: null,
            funnelStats: {},
            dropped: [{ code: 'SH600XXX', name: '跌出标的', prevRank: 7 }],
            lastEvent: null,
          },
        }),
      ),
    );

    const { MarketTop } = await import('@/pages/MarketTop');
    render(<MarketTop />);

    const panel = await screen.findByTestId('market-top-dropped');
    expect(panel).toHaveTextContent('本期跌出 Top10（1 只）');
    expect(screen.getByTestId('market-top-dropped-SH600XXX')).toHaveTextContent('跌出标的');
    expect(panel).toHaveTextContent('昨日第 7 名');
  });

  it('日期与版本选择联动：选日期重拉 date=，选版本重拉 date=&version=', async () => {
    // 按查询参数回放：date=2026-09-25 → 该日 v1 视图；version=2 → v2 视图
    const respondRank = (url: string) => {
      const hasVersion2 = /[?&]version=2/.test(url);
      const hasDate25 = /[?&]date=2026-09-25/.test(url);
      if (hasVersion2) return ok(viewOf([itemOf()], { version: 2 }));
      if (hasDate25) {
        return ok(
          viewOf([itemOf()], {
            rankDate: '2026-09-25',
            version: 1,
            batch: {
              snapshotDate: '2026-09-25',
              computedAt: '2026-09-25T10:05:00Z',
              degraded: false,
              degradedReason: null,
              funnelStats: {},
              dropped: [],
              lastEvent: null,
            },
          }),
        );
      }
      return ok(viewOf([itemOf()]));
    };
    const fetchMock = stubFetch([
      { path: '/api/v1/market-top/versions', respond: () => ok(versionsOf()) },
      { path: '/api/v1/market-top', respond: (url) => respondRank(url) },
      { path: '/api/v1/watchlists', respond: () => ok([]) },
      { path: '/api/v1/reading-events', respond: () => ok(null) },
    ]);

    const { MarketTop } = await import('@/pages/MarketTop');
    render(<MarketTop />);
    await screen.findByTestId('market-top-card-SZ300024');

    // 选择器同步到实际命中版本（v1）
    const dateSelect = screen.getByTestId('market-top-date-select') as HTMLSelectElement;
    expect(dateSelect.value).toBe('2026-09-26');
    const versionSelect = screen.getByTestId('market-top-version-select') as HTMLSelectElement;
    expect(versionSelect.value).toBe('1');
    // 版本下拉含该日两版本
    expect(within(versionSelect).getAllByRole('option').map((o) => (o as HTMLOptionElement).value))
      .toEqual(expect.arrayContaining(['1', '2']));

    await userEvent.selectOptions(dateSelect, '2026-09-25');
    await waitFor(() => {
      expect(screen.getByTestId('market-top-meta')).toHaveTextContent('快照日 2026-09-25');
      const dateCalls = fetchMock.mock.calls
        .map((call) => String(call[0]))
        .filter((url) => url.startsWith('/api/v1/market-top?'));
      expect(dateCalls.at(-1)).toContain('date=2026-09-25');
      expect(dateCalls.at(-1)).not.toContain('version=');
    });

    await userEvent.selectOptions(screen.getByTestId('market-top-version-select'), '2');
    await waitFor(() => {
      const versionCalls = fetchMock.mock.calls
        .map((call) => String(call[0]))
        .filter((url) => url.startsWith('/api/v1/market-top?'));
      expect(versionCalls.at(-1)).toContain('date=2026-09-25');
      expect(versionCalls.at(-1)).toContain('version=2');
    });
  });

  it('空态（30089 无任何榜单）：引导「每日 18:00 生成」，不渲染错误', async () => {
    stubFetch([
      { path: '/api/v1/market-top/versions', respond: () => ok([]) },
      { path: '/api/v1/market-top', respond: () => fail(404, 30089, '该日无榜单数据: date=<最新>') },
      { path: '/api/v1/watchlists', respond: () => ok([]) },
    ]);

    const { MarketTop } = await import('@/pages/MarketTop');
    render(<MarketTop />);

    expect(await screen.findByTestId('market-top-empty')).toHaveTextContent('每日 18:00 生成');
    expect(screen.queryByTestId('market-top-error')).toBeNull();
  });

  it('错误态（非 30089）：展示错误信息，重试成功恢复', async () => {
    let calls = 0;
    stubFetch([
      { path: '/api/v1/market-top/versions', respond: () => ok([]) },
      {
        path: '/api/v1/market-top',
        respond: () => {
          calls += 1;
          return calls === 1 ? fail(400, 30090, '非法日期（需 yyyy-MM-dd）: xx') : ok(viewOf([itemOf()]));
        },
      },
      { path: '/api/v1/watchlists', respond: () => ok([]) },
    ]);

    const { MarketTop } = await import('@/pages/MarketTop');
    render(<MarketTop />);

    expect(await screen.findByTestId('market-top-error')).toHaveTextContent('非法日期');
    await userEvent.click(screen.getByTestId('market-top-retry'));
    expect(await screen.findByTestId('market-top-card-SZ300024')).toBeInTheDocument();
  });

  it('一键加自选：无清单自动建「默认清单」→ 加标的；成功后按钮态「已自选」并同点落 MARKET_TOP_ACT 埋点', async () => {
    const fetchMock = stubFetch([
      { path: '/api/v1/market-top/versions', respond: () => ok(versionsOf()) },
      { path: '/api/v1/market-top', respond: () => ok(viewOf([itemOf()])) },
      { path: '/api/v1/watchlists/1/items', respond: () => ok({ id: 9, subjectId: 101, anomalyThreshold: 3, status: 1 }) },
      {
        path: '/api/v1/watchlists',
        respond: (_url, init) => {
          if (init?.method === 'POST') {
            return ok({ id: 1, name: '默认清单', remark: null, status: 1, items: [] });
          }
          return ok([]);
        },
      },
      { path: '/api/v1/reading-events', respond: () => ok(null) },
    ]);

    const { MarketTop } = await import('@/pages/MarketTop');
    render(<MarketTop />);
    await screen.findByTestId('market-top-card-SZ300024');

    await userEvent.click(screen.getByTestId('market-top-addwatch-SZ300024'));

    expect(await screen.findByTestId('market-top-inwatch-SZ300024')).toHaveTextContent('已自选');
    // 建默认清单 + 加标的（subjectId=101）
    const createCall = fetchMock.mock.calls.find(
      (call) => String(call[0]) === '/api/v1/watchlists' && call[1]?.method === 'POST',
    );
    expect(String(createCall?.[1]?.body)).toContain('默认清单');
    const addCall = fetchMock.mock.calls.find(
      (call) => String(call[0]) === '/api/v1/watchlists/1/items',
    );
    expect(String(addCall?.[1]?.body)).toContain('"subjectId":101');
    // ACT 埋点与加自选成功同点（M16 先例）
    await waitFor(() => {
      const actCalls = fetchMock.mock.calls.filter(
        (call) => String(call[0]).includes('/reading-events'),
      );
      const act = actCalls.find((call) =>
        String(call[1]?.body).includes('"contentType":"MARKET_TOP_ACT"'),
      );
      expect(act).toBeDefined();
      expect(String(act?.[1]?.body)).toContain('"subjectCode":"SZ300024"');
    });
  });

  it('加自选幂等：标的已在清单直接显示「已自选」零请求；服务端 30011 已在清单按成功处理', async () => {
    let addCalls = 0;
    stubFetch([
      { path: '/api/v1/market-top/versions', respond: () => ok(versionsOf()) },
      {
        path: '/api/v1/market-top',
        respond: () =>
          ok(viewOf([itemOf({ subjectId: 101 }), itemOf({ rankNo: 2, subjectId: 102, subjectCode: 'SZ2' })])),
      },
      {
        path: '/api/v1/watchlists/7/items',
        respond: () => {
          addCalls += 1;
          return addCalls === 1
            ? fail(409, 30011, '标的已在清单')
            : ok({ id: 10, subjectId: 102, anomalyThreshold: 3, status: 1 });
        },
      },
      {
        path: '/api/v1/watchlists',
        respond: () =>
          ok([
            {
              id: 7,
              name: '我的清单',
              remark: null,
              status: 1,
              items: [{ id: 8, subjectId: 101, anomalyThreshold: 3, status: 1 }],
            },
          ]),
      },
      { path: '/api/v1/reading-events', respond: () => ok(null) },
    ]);

    const { MarketTop } = await import('@/pages/MarketTop');
    render(<MarketTop />);

    // 预置清单内标的（subjectId=101）直接「已自选」，无加自选按钮
    expect(await screen.findByTestId('market-top-inwatch-SZ300024')).toBeInTheDocument();
    expect(screen.queryByTestId('market-top-addwatch-SZ300024')).toBeNull();
    // 另一标的 30011（已在清单）按成功处理——幂等红线
    await userEvent.click(screen.getByTestId('market-top-addwatch-SZ2'));
    expect(await screen.findByTestId('market-top-inwatch-SZ2')).toHaveTextContent('已自选');
    expect(screen.queryByTestId('market-top-watch-error-SZ2')).toBeNull();
  });

  it('加自选失败：就地提示可重试，不打断榜单', async () => {
    let addCalls = 0;
    stubFetch([
      { path: '/api/v1/market-top/versions', respond: () => ok(versionsOf()) },
      { path: '/api/v1/market-top', respond: () => ok(viewOf([itemOf()])) },
      {
        path: '/api/v1/watchlists/7/items',
        respond: () => {
          addCalls += 1;
          return fail(404, 30001, '标的不存在');
        },
      },
      {
        path: '/api/v1/watchlists',
        respond: () => ok([{ id: 7, name: '我的清单', remark: null, status: 1, items: [] }]),
      },
    ]);

    const { MarketTop } = await import('@/pages/MarketTop');
    render(<MarketTop />);
    await screen.findByTestId('market-top-card-SZ300024');

    await userEvent.click(screen.getByTestId('market-top-addwatch-SZ300024'));

    expect(await screen.findByTestId('market-top-watch-error-SZ300024')).toHaveTextContent(
      '标的不存在',
    );
    expect(screen.getByTestId('market-top-addwatch-SZ300024')).toBeInTheDocument();
    expect(addCalls).toBe(1);
  });

  it('曝光埋点：榜单拉取后按卡 fire-and-forget 上报 MARKET_TOP_VIEW（会话内同版本同卡一次）', async () => {
    const fetchMock = stubFetch(baseRoutes(viewOf([itemOf()])));

    const { MarketTop } = await import('@/pages/MarketTop');
    render(<MarketTop />);
    await screen.findByTestId('market-top-card-SZ300024');

    await waitFor(() => {
      const viewCalls = fetchMock.mock.calls.filter(
        (call) =>
          String(call[0]).includes('/reading-events') &&
          String(call[1]?.body).includes('"contentType":"MARKET_TOP_VIEW"'),
      );
      expect(viewCalls).toHaveLength(1);
      expect(String(viewCalls[0][1]?.body)).toContain('"contentRef":"2026-09-26:v1:r1"');
    });
  });

  it('不足 10：空位如实标注原因，不伪造占位卡', async () => {
    stubFetch(
      baseRoutes(
        viewOf([
          itemOf(),
          itemOf({ rankNo: 2, subjectId: 102, subjectCode: 'SZ2', changeType: 'SAME', prevRank: 2 }),
        ]),
      ),
    );

    const { MarketTop } = await import('@/pages/MarketTop');
    render(<MarketTop />);

    expect(await screen.findByTestId('market-top-shortfall')).toHaveTextContent('本期入榜 2 只');
    const cards = screen.getAllByTestId(/^market-top-card-/);
    expect(cards).toHaveLength(2);
  });

  it('加载骨架态：请求未返回前渲染骨架不渲染卡片', async () => {
    stubFetch([
      { path: '/api/v1/market-top/versions', respond: () => ok(versionsOf()) },
      {
        path: '/api/v1/market-top',
        respond: () => new Promise<ReturnType<typeof ok>>(() => undefined), // 永不返回——钉住加载态
      },
      { path: '/api/v1/watchlists', respond: () => ok([]) },
    ]);

    const { MarketTop } = await import('@/pages/MarketTop');
    render(<MarketTop />);

    expect(await screen.findByTestId('market-top-loading')).toBeInTheDocument();
    expect(screen.queryByTestId('market-top-list')).toBeNull();
  });
});

