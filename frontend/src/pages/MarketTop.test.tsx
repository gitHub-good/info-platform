import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type {
  MarketTopItem,
  MarketTopRankView,
  MarketTopVersionSummary,
} from '@/types/marketTop';
import { resetReadingTrackerForTest } from '@/api/readingEvent';
import { formatDateTime } from '@/lib/format';
import type { ScoreWeightsView } from '@/types/valueScore';

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
    recentIncrement: null,
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

describe('MarketTop 方法论子路由（T185，#/market-top/methodology）', () => {
  function weightsOf(overrides: Partial<ScoreWeightsView> = {}): ScoreWeightsView {
    return {
      wCatalyst: 0.35,
      wConduction: 0.25,
      wFundamental: 0.2,
      wRisk: 0.2,
      wValuation: 0.0,
      catalystWindowDays: 10,
      assocWindowDays: 14,
      halfLifeDays: 5,
      k1Saturation: 3,
      k3Saturation: 3,
      btCatalystMin: 20,
      btConductionMin: 50,
      btRiskMin: 80,
      basis: 'vs-v1:w=0.35|0.25|0.20|0.20|0.00;bt=20|50|80',
      updatedAt: '2026-09-20T09:00:00Z',
      ...overrides,
    };
  }

  function configOf() {
    return {
      poolSize: 260,
      deepDiveLimit: 35,
      deepDiveCostCapRatio: 0.25,
      diveCostEstimateMicros: 120000,
      memberCoverageFloor: 0.8,
      updatedAt: '2026-09-20T09:00:00Z',
    };
  }

  afterEach(() => {
    window.location.hash = '';
  });

  it('入口与五段式：榜单页头「方法论」链接进入子路由，漏斗图解/因子定义/合成公式/降级语义/免责五段齐备', async () => {
    stubFetch([
      { path: '/api/v1/market-top/versions', respond: () => ok(versionsOf()) },
      { path: '/api/v1/market-top/config', respond: () => ok(configOf()) },
      { path: '/api/v1/market-top', respond: () => ok(viewOf([itemOf()])) },
      { path: '/api/v1/value-scores/weights', respond: () => ok(weightsOf()) },
      { path: '/api/v1/watchlists', respond: () => ok([]) },
      { path: '/api/v1/reading-events', respond: () => ok(null) },
    ]);

    const { MarketTop } = await import('@/pages/MarketTop');
    window.location.hash = '#/market-top/methodology';
    render(<MarketTop />);

    // 五段式齐备（T185 静态内容页）
    expect(await screen.findByTestId('market-top-methodology-funnel')).toBeInTheDocument();
    expect(screen.getByTestId('market-top-methodology-factors')).toBeInTheDocument();
    expect(screen.getByTestId('market-top-methodology-synthesis')).toHaveTextContent(
      'max(总分, 0.8 × 总分 + 0.2 × 深析结构分)',
    );
    expect(screen.getByTestId('market-top-methodology-degraded')).toHaveTextContent('COST_CAP');
    expect(screen.getByTestId('market-top-methodology-disclaimer')).toHaveTextContent('不构成投资建议');
    // 返回榜单入口常驻
    expect(screen.getByTestId('market-top-methodology-back')).toHaveAttribute('href', '#/market-top');
  });

  it('版本对齐断言：页面展示权重与阈值恒等于 weights GET 值（实时读非硬编码）', async () => {
    stubFetch([
      { path: '/api/v1/market-top/config', respond: () => ok(configOf()) },
      { path: '/api/v1/value-scores/weights', respond: () => ok(weightsOf()) },
    ]);

    const { MarketTop } = await import('@/pages/MarketTop');
    window.location.hash = '#/market-top/methodology';
    render(<MarketTop />);

    // 五维权重逐项 = GET 响应值（改权重即变——非硬编码红线）
    expect(await screen.findByTestId('market-top-methodology-weight-wCatalyst')).toHaveTextContent(
      '权重 0.35',
    );
    expect(screen.getByTestId('market-top-methodology-weight-wConduction')).toHaveTextContent(
      '权重 0.25',
    );
    expect(screen.getByTestId('market-top-methodology-weight-wValuation')).toHaveTextContent(
      '权重 0.00',
    );
    // 突破三阈值与窗口 = GET 响应值
    expect(screen.getByTestId('market-top-methodology-formula')).toHaveTextContent('≥ 20');
    expect(screen.getByTestId('market-top-methodology-formula')).toHaveTextContent('≥ 50');
    expect(screen.getByTestId('market-top-methodology-formula')).toHaveTextContent('≥ 80');
    expect(screen.getByTestId('market-top-methodology-formula')).toHaveTextContent('催化窗口 10 天');
    // 参数指纹脚注（版本对齐数据面）
    expect(screen.getByTestId('market-top-methodology-basis')).toHaveTextContent('vs-v1:w=0.35');
  });

  it('漏斗层数实时读 market.top 配置：粗筛池/深析额度/成本占比 = config GET 值', async () => {
    stubFetch([
      { path: '/api/v1/market-top/config', respond: () => ok(configOf()) },
      { path: '/api/v1/value-scores/weights', respond: () => ok(weightsOf()) },
    ]);

    const { MarketTop } = await import('@/pages/MarketTop');
    window.location.hash = '#/market-top/methodology';
    render(<MarketTop />);

    expect(await screen.findByTestId('market-top-methodology-pool')).toHaveTextContent('~260');
    expect(screen.getByTestId('market-top-methodology-dive')).toHaveTextContent('35');
    expect(screen.getByTestId('market-top-methodology-funnel')).toHaveTextContent('25%');
  });

  it('weights 加载失败：错误态 + 重试成功恢复', async () => {
    let calls = 0;
    stubFetch([
      { path: '/api/v1/market-top/config', respond: () => ok(configOf()) },
      {
        path: '/api/v1/value-scores/weights',
        respond: () => {
          calls += 1;
          return calls === 1 ? fail(500, 50000, '服务异常') : ok(weightsOf());
        },
      },
    ]);

    const { MarketTop } = await import('@/pages/MarketTop');
    window.location.hash = '#/market-top/methodology';
    render(<MarketTop />);

    expect(await screen.findByTestId('market-top-methodology-error')).toHaveTextContent('服务异常');
    await userEvent.click(screen.getByTestId('market-top-methodology-retry'));
    expect(await screen.findByTestId('market-top-methodology-factors')).toBeInTheDocument();
  });

  it('页内 hash 切换：方法论视图与榜单视图互切不重挂载外层（导航仍 1 项）', async () => {
    stubFetch([
      { path: '/api/v1/market-top/versions', respond: () => ok(versionsOf()) },
      { path: '/api/v1/market-top/config', respond: () => ok(configOf()) },
      { path: '/api/v1/market-top', respond: () => ok(viewOf([itemOf()])) },
      { path: '/api/v1/value-scores/weights', respond: () => ok(weightsOf()) },
      { path: '/api/v1/watchlists', respond: () => ok([]) },
      { path: '/api/v1/reading-events', respond: () => ok(null) },
    ]);

    const { MarketTop } = await import('@/pages/MarketTop');
    window.location.hash = '#/market-top';
    render(<MarketTop />);
    expect(await screen.findByTestId('market-top-card-SZ300024')).toBeInTheDocument();
    // 榜单页头「方法论」入口
    expect(screen.getByTestId('market-top-link-methodology')).toHaveAttribute(
      'href',
      '#/market-top/methodology',
    );

    window.location.hash = '#/market-top/methodology';
    expect(await screen.findByTestId('market-top-methodology-page')).toBeInTheDocument();
    expect(screen.queryByTestId('market-top-card-SZ300024')).toBeNull();

    window.location.hash = '#/market-top';
    expect(await screen.findByTestId('market-top-card-SZ300024')).toBeInTheDocument();
  });

  // —— M22 T192：页头双时间戳（时间戳双层语义——盘后全量 + 事件增量区分文案，晚者在上） ——

  it('页头双时间戳：有增量重评时双行齐备，增量晚于全量时晚者在上（故事 4 场景 3）', async () => {
    stubFetch(
      baseRoutes(
        viewOf([itemOf()], {
          version: 3,
          triggerSource: 'EVENT',
          recentIncrement: {
            version: 3,
            computedAt: '2026-09-26T11:33:02Z',
            triggerEvents: [
              { eventId: 4821, summary: '业绩预增', importance: 'HIGH' },
              { eventId: 4822, summary: '行业政策利好', importance: 'HIGH' },
            ],
          },
        }),
      ),
    );

    const { MarketTop } = await import('@/pages/MarketTop');
    render(<MarketTop />);
    await screen.findByTestId('market-top-dual-ts');

    // 盘后全量 = 当日最新 DAILY 版本 computedAt（versions 列表 v2 DAILY 10:10——非当前 EVENT 批 10:05）
    expect(screen.getByTestId('market-top-full-at')).toHaveTextContent(
      `盘后全量重算 ${formatDateTime('2026-09-26T10:10:00Z')}`,
    );
    const increment = screen.getByTestId('market-top-increment-at');
    expect(increment).toHaveTextContent('事件增量重评 v3');
    expect(increment).toHaveTextContent(formatDateTime('2026-09-26T11:33:02Z'));
    expect(increment.getAttribute('title')).toContain('#4821');
    expect(increment.getAttribute('title')).toContain('行业政策利好');
    // 时序正确：增量（11:33）晚于全量（10:10）→ 增量行在上
    const dual = screen.getByTestId('market-top-dual-ts');
    expect(dual.firstElementChild?.querySelector('[data-testid="market-top-increment-at"]')).not.toBeNull();
    expect(dual.lastElementChild?.querySelector('[data-testid="market-top-full-at"]')).not.toBeNull();
    // EVENT 触发来源展示名（版本归因可见）
    expect(screen.getByTestId('market-top-meta')).toHaveTextContent('事件驱动');
  });

  it('页头双时间戳：无增量重评（recentIncrement null）时仅全量行，无事件增量标注', async () => {
    stubFetch(baseRoutes(viewOf([itemOf()])));

    const { MarketTop } = await import('@/pages/MarketTop');
    render(<MarketTop />);
    await screen.findByTestId('market-top-dual-ts');

    expect(screen.getByTestId('market-top-full-at')).toHaveTextContent('盘后全量重算');
    expect(screen.queryByTestId('market-top-increment-at')).toBeNull();
  });

  it('数据延迟口径文案为双层口径（全量日频盘后 + 高重要事件分钟级增量）', async () => {
    stubFetch(baseRoutes(viewOf([itemOf()])));

    const { MarketTop } = await import('@/pages/MarketTop');
    render(<MarketTop />);

    const header = await screen.findByTestId('market-top-page');
    expect(header.querySelector('header')?.textContent).toContain('全量日频盘后');
    expect(header.querySelector('header')?.textContent).toContain('高重要事件分钟级增量');
  });

  // —— M22 T193：历史表现折叠区块（hits-v1 信号验证统计——可达/样本标注/免责三要素） ——

  function hitStatsOf() {
    return {
      basis:
        'hits-v1:maxVer;price=market_daily_snapshot;win=1/5/20;median=pctChg;sample=priced-only',
      asOf: '2026-09-28',
      disclaimer: '历史统计不构成收益承诺',
      windows: [
        {
          window: 'T+1',
          days: [
            { rankDate: '2026-09-25', topSize: 10, pricedSamples: 9, excluded: 1, upRatio: 0.667, medianPctChg: 1.2 },
            { rankDate: '2026-09-26', topSize: 10, pricedSamples: 10, excluded: 0, upRatio: 0.5, medianPctChg: -0.3 },
          ],
          agg: { days: 15, status: 'OK', upRatio: 0.58, medianPct: 0.9 },
        },
        { window: 'T+5', days: [], agg: { days: 0, status: 'INSUFFICIENT', upRatio: null, medianPct: null } },
        { window: 'T+20', days: [], agg: { days: 0, status: 'INSUFFICIENT', upRatio: null, medianPct: null } },
      ],
    };
  }

  it('历史表现：默认折叠零请求，展开首拉三窗与免责/口径常驻（可达性 + 免责）', async () => {
    const fetchMock = stubFetch([
      ...baseRoutes(viewOf([itemOf()])),
      { path: '/api/v1/market-top/hit-stats', respond: () => ok(hitStatsOf()) },
    ]);

    const { MarketTop } = await import('@/pages/MarketTop');
    render(<MarketTop />);
    await screen.findByTestId('market-top-hitstats');

    // 折叠态零请求（惰性——首拉挂起到展开）
    expect(
      fetchMock.mock.calls.some((call) => String(call[0]).includes('hit-stats')),
    ).toBe(false);

    await userEvent.setup().click(screen.getByTestId('market-top-hitstats-summary'));
    expect(await screen.findByTestId('market-top-hitstats-body')).toBeInTheDocument();
    expect(
      fetchMock.mock.calls.some((call) => String(call[0]).includes('hit-stats')),
    ).toBe(true);
    // 免责常驻 + 口径留档（hits-v1 与方法论页对齐）
    expect(screen.getByTestId('market-top-hitstats-disclaimer')).toHaveTextContent(
      '历史统计不构成收益承诺',
    );
    expect(screen.getByTestId('market-top-hitstats-basis')).toHaveTextContent('hits-v1');
    expect(screen.getByTestId('market-top-hitstats-basis')).toHaveTextContent('2026-09-28');
  });

  it('历史表现：OK 窗渲染上涨占比与中位涨跌 + 逐日样本 N/10 标注（剔除计数不隐藏）', async () => {
    stubFetch([
      ...baseRoutes(viewOf([itemOf()])),
      { path: '/api/v1/market-top/hit-stats', respond: () => ok(hitStatsOf()) },
    ]);

    const { MarketTop } = await import('@/pages/MarketTop');
    render(<MarketTop />);
    await screen.findByTestId('market-top-hitstats');
    await userEvent.setup().click(screen.getByTestId('market-top-hitstats-summary'));

    expect(await screen.findByTestId('market-top-hitstats-T+1-upratio')).toHaveTextContent(
      '上涨占比 58.0%',
    );
    expect(screen.getByTestId('market-top-hitstats-T+1-median')).toHaveTextContent('中位涨跌 0.90%');
    const day = await screen.findByTestId('market-top-hitstats-T+1-day-2026-09-25');
    expect(day).toHaveTextContent('样本 9/10');
    expect(day).toHaveTextContent('剔除 1');
    expect(day).toHaveTextContent('上涨 66.7%');
    expect(screen.getByTestId('market-top-hitstats-T+1-day-2026-09-26')).toHaveTextContent('样本 10/10');
  });

  it('历史表现：样本不足窗如实显示「样本积累中」（首跑校准条款——不硬凑）', async () => {
    stubFetch([
      ...baseRoutes(viewOf([itemOf()])),
      { path: '/api/v1/market-top/hit-stats', respond: () => ok(hitStatsOf()) },
    ]);

    const { MarketTop } = await import('@/pages/MarketTop');
    render(<MarketTop />);
    await screen.findByTestId('market-top-hitstats');
    await userEvent.setup().click(screen.getByTestId('market-top-hitstats-summary'));

    expect(await screen.findByTestId('market-top-hitstats-T+20-insufficient')).toHaveTextContent(
      '样本积累中',
    );
    expect(screen.queryByTestId('market-top-hitstats-T+20-upratio')).toBeNull();
    expect(screen.getByTestId('market-top-hitstats-T+20')).toHaveTextContent('样本日 0 天');
  });

  it('历史表现：加载失败就地提示可重试恢复（错误态不伤榜单主体）', async () => {
    let failed = true;
    stubFetch([
      ...baseRoutes(viewOf([itemOf()])),
      {
        path: '/api/v1/market-top/hit-stats',
        respond: () => (failed ? fail(500, 50000, '统计服务异常') : ok(hitStatsOf())),
      },
    ]);

    const { MarketTop } = await import('@/pages/MarketTop');
    render(<MarketTop />);
    await screen.findByTestId('market-top-card-SZ300024'); // 榜单主体不受影响
    await userEvent.setup().click(screen.getByTestId('market-top-hitstats-summary'));
    expect(await screen.findByTestId('market-top-hitstats-error')).toHaveTextContent('统计服务异常');

    failed = false;
    await userEvent.setup().click(screen.getByTestId('market-top-hitstats-retry'));
    expect(await screen.findByTestId('market-top-hitstats-body')).toBeInTheDocument();
  });

  it('历史表现：展开懒加载骨架先行（loading 态可见）', async () => {
    let resolveStats: (value: ReturnType<typeof ok>) => void = () => undefined;
    stubFetch([
      ...baseRoutes(viewOf([itemOf()])),
      {
        path: '/api/v1/market-top/hit-stats',
        respond: () => new Promise((resolve) => { resolveStats = resolve; }),
      },
    ]);

    const { MarketTop } = await import('@/pages/MarketTop');
    render(<MarketTop />);
    await screen.findByTestId('market-top-hitstats');
    await userEvent.setup().click(screen.getByTestId('market-top-hitstats-summary'));

    expect(await screen.findByTestId('market-top-hitstats-loading')).toBeInTheDocument();
    resolveStats(ok(hitStatsOf()));
    expect(await screen.findByTestId('market-top-hitstats-body')).toBeInTheDocument();
  });
});

// —— M29 T257 三市场切换：分市场独立榜单 + 价值维缺省徽章 + 深析不可用标注 + URL 持久化 ——

describe('MarketTop 全市场推荐页（T257）· 三市场切换', () => {
  /** 历史表现响应（hits-v1 镜像——hitStatsOf 同款精简）。 */
  function hkHitStatsOf() {
    return {
      basis: 'hits-v1:maxVer;price=market_daily_snapshot;win=1/5/20;median=pctChg;sample=priced-only',
      asOf: '2026-09-28',
      disclaimer: '历史统计不构成收益承诺',
      windows: [
        { window: 'T+1', days: [], agg: { days: 0, status: 'INSUFFICIENT', upRatio: null, medianPct: null } },
      ],
    };
  }

  /** 港股榜视图（market 回显 + dimensionMissing 维度裁剪留痕 + FACTOR_ONLY 卡）。 */
  function hkViewOf(): MarketTopRankView {
    return viewOf(
      [
        itemOf({
          subjectCode: 'HK00700',
          subjectName: '腾讯控股',
          generation: 'FACTOR_ONLY',
          diveMethod: null,
          changeType: 'NEW',
          prevRank: null,
        }),
      ],
      {
        market: 'HK',
        batch: {
          snapshotDate: '2026-09-26',
          computedAt: '2026-09-26T10:05:00Z',
          degraded: false,
          degradedReason: null,
          funnelStats: {
            snapshotRows: 2612,
            eligible: 300,
            excluded: { st: 0, noSignal: 2312 },
            poolSize: 300,
            divePlanned: 10,
            diveDone: 10,
            diveTemplate: 0,
            diveSkipped: 0,
            topSize: 10,
            dimensionMissing: {
              valuation: '本市场暂无价值评分因子',
              fundamental: '本市场暂无基本面因子（F3/F5 权重置 0 后再归一）',
            },
          },
          dropped: [],
          lastEvent: null,
        },
      },
    );
  }

  it('M29 切港股：榜单/版本/历史表现带 market=HK + URL 持久化 + 维度缺省徽章 + 深析不可用标注', async () => {
    const fetchMock = stubFetch([
      {
        path: '/api/v1/market-top/versions',
        respond: (url: string) => ok(url.includes('market=HK') ? versionsOf() : versionsOf()),
      },
      {
        path: '/api/v1/market-top/hit-stats',
        respond: () => ok(hkHitStatsOf()),
      },
      {
        path: '/api/v1/market-top',
        respond: (url: string) => ok(url.includes('market=HK') ? hkViewOf() : viewOf([itemOf()])),
      },
      { path: '/api/v1/watchlists', respond: () => ok([]) },
      { path: '/api/v1/reading-events', respond: () => ok(null) },
    ]);
    const { MarketTop } = await import('@/pages/MarketTop');
    const user = userEvent.setup();
    render(<MarketTop />);

    // 默认 A 股：请求带 market=A_SHARE（显式下发——后端缺省同值零回归）
    expect(await screen.findByTestId('market-top-card-SZ300024')).toBeInTheDocument();
    expect(String(fetchMock.mock.calls[0][0])).toContain('market=A_SHARE');

    // 切港股：URL 持久化 + 榜单/版本列表重查带 market=HK（分市场独立榜单——拍板四）
    await user.click(screen.getByTestId('market-tab-HK'));
    expect(window.location.hash).toContain('market=HK');
    expect(await screen.findByTestId('market-top-card-HK00700')).toBeInTheDocument();
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some((call) => String(call[0]).includes('/market-top?market=HK')),
      ).toBe(true),
    );
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some((call) => String(call[0]).includes('/market-top/versions?market=HK')),
      ).toBe(true),
    );

    // 价值维缺省徽章（拍板四：维度裁剪留痕不静默）
    expect(screen.getByTestId('market-top-dimension-missing-valuation')).toHaveTextContent(
      '本市场暂无价值评分因子',
    );
    expect(screen.getByTestId('market-top-dimension-missing-fundamental')).toHaveTextContent(
      '本市场暂无基本面因子',
    );

    // 港美股深析不可用标注（FACTOR_ONLY 卡如实文案）
    expect(screen.getByTestId('market-top-dive-factor-only')).toHaveTextContent(
      '港美股深析暂不可用，按因子分排序',
    );

    // 历史表现展开首拉带 market=HK
    await user.click(screen.getByTestId('market-top-hitstats-summary'));
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some((call) => String(call[0]).includes('/market-top/hit-stats?market=HK')),
      ).toBe(true),
    );
  });

  it('M29 Tab 间切换状态不丢：港股选日期 → 切美股 → 切回港股恢复日期选择（REQ 故事 8 场景 1）', async () => {
    stubFetch([
      { path: '/api/v1/market-top/versions', respond: () => ok(versionsOf()) },
      {
        path: '/api/v1/market-top',
        respond: (url: string) => {
          if (url.includes('market=HK')) return ok(hkViewOf());
          if (url.includes('market=US')) return ok(viewOf([itemOf({ subjectCode: 'USAAPL' })], { market: 'US' }));
          return ok(viewOf([itemOf()]));
        },
      },
      { path: '/api/v1/watchlists', respond: () => ok([]) },
      { path: '/api/v1/reading-events', respond: () => ok(null) },
    ]);
    const { MarketTop } = await import('@/pages/MarketTop');
    const user = userEvent.setup();
    render(<MarketTop />);

    // 切港股并选历史日期 2026-09-25
    await user.click(screen.getByTestId('market-tab-HK'));
    expect(await screen.findByTestId('market-top-card-HK00700')).toBeInTheDocument();
    await user.selectOptions(screen.getByTestId('market-top-date-select'), '2026-09-25');
    await waitFor(() =>
      expect(screen.getByTestId('market-top-date-select')).toHaveValue('2026-09-25'),
    );

    // 切美股再切回：港股日期选择恢复（状态不丢），重查仍带 market=HK&date=2026-09-25
    await user.click(screen.getByTestId('market-tab-US'));
    expect(await screen.findByTestId('market-top-card-USAAPL')).toBeInTheDocument();
    await user.click(screen.getByTestId('market-tab-HK'));
    expect(await screen.findByTestId('market-top-card-HK00700')).toBeInTheDocument();
    expect(screen.getByTestId('market-top-date-select')).toHaveValue('2026-09-25');
  });
});
