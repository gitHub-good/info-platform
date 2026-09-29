import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { heatCellShade, HEAT_SCALE_MAX_PCT } from '@/lib/format';
import type {
  IndustryHeatMapCell,
  IndustryHeatMapView,
  IndustryMainlineDetailView,
  IndustryMainlineView,
  MainlineItem,
  MainlineLeaderCard,
} from '@/types/industryMainline';

// —— fetch mock：对齐 T244 冻结契约（IndustryMainlineController §4.5——heat-map / mainline /
//    {industry}/detail / config GET+PATCH / recompute POST 六端点） ——

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

function stubFetch(
  routes: { path: string; respond: (url: string, init?: RequestInit) => ReturnType<typeof ok> | ReturnType<typeof fail> }[],
) {
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

function heatCellOf(industry: string, overrides: Partial<IndustryHeatMapCell> = {}): IndustryHeatMapCell {
  return {
    industry,
    pctDay: 1.23,
    pctD5: 2.5,
    upCount: 40,
    downCount: 12,
    mainNetFlow: 1_234_000_000,
    totalMv: 2_500_000_000_000,
    aggMethod: 'TENCENT_DIRECT',
    leaderStock: { code: '601579', name: '会稽山', pct: 9.99 },
    ...overrides,
  };
}

function heatViewOf(cells: IndustryHeatMapCell[], overrides: Partial<IndustryHeatMapView> = {}): IndustryHeatMapView {
  return {
    snapshotDate: '2026-09-28',
    source: 'tencent-rank',
    quoteTime: '2026-09-28T15:00:02+08:00',
    stale: false,
    industries: cells,
    ...overrides,
  };
}

function leaderOf(rank = 1, overrides: Partial<MainlineLeaderCard> = {}): MainlineLeaderCard {
  return {
    rank,
    rankLabel: rank === 1 ? '龙一' : rank === 2 ? '龙二' : '龙三',
    subjectId: 88 + rank,
    subjectCode: '600519',
    subjectName: '贵州茅台',
    score: 87.3,
    dim: {
      attention: { score: 95, mentions: 12, eventCount: 3, eventWeighted: 7 },
      value: { score: 82, totalScore: 76.5, snapshotDate: '2026-09-28', dataFlags: [] },
      price: { score: 71, pctDay: 0.56, pctD5: 2.1, flag: '' },
    },
    basis: { eventIds: [10231, 10344], factorSnapshotDate: '2026-09-28', riskEvents: 0, divergenceNote: null },
    attention: {
      lhb30d: 3,
      lhbLatest: { date: '2026-09-01', reason: '日涨幅偏离值达到7%' },
      chgDirection: '净增持',
      chgCount: 2,
      queryTime: '2026-09-28T18:30:01Z',
      state: 'OK',
    },
    disclaimer: '关注度排名，非投资建议，不构成买卖依据',
    ...overrides,
  };
}

function mainlineItemOf(overrides: Partial<MainlineItem> = {}): MainlineItem {
  return {
    rankNo: 1,
    industry: '食品饮料',
    mainScore: 88.6,
    dimDetail: {
      price: { score: 92, rank: 1, raw: 3.2 },
      heat: { score: 78, rank: 3, raw: 156 },
      event: { score: 85, rank: 2, raw: 7 },
    },
    persistentDays: 4,
    heatRank: 3,
    divergence: null,
    leaders: [leaderOf(1), leaderOf(2, { subjectName: '五粮液', subjectCode: '000858' }), leaderOf(3, { subjectName: '泸州老窖', subjectCode: '000568' })],
    basis: 'mainline-v1:wp=0.4,wh=0.3,we=0.3',
    computedAt: '2026-09-28T18:30:05+08:00',
    ...overrides,
  };
}

function mainlineViewOf(items: MainlineItem[], overrides: Partial<IndustryMainlineView> = {}): IndustryMainlineView {
  return {
    rankDate: '2026-09-28',
    version: 2,
    triggerSource: 'DAILY',
    snapshotDate: '2026-09-28',
    degraded: false,
    degradedReason: null,
    basis: 'mainline-v1:wp=0.4',
    computedAt: '2026-09-28T18:30:05+08:00',
    items,
    ...overrides,
  };
}

function detailViewOf(overrides: Partial<IndustryMainlineDetailView> = {}): IndustryMainlineDetailView {
  return {
    industry: '食品饮料',
    snapshotDate: '2026-09-28',
    source: 'eastmoney-push2',
    quoteTime: '2026-09-28T15:00:02+08:00',
    stale: false,
    pctDay: 1.23,
    pctD5: 2.5,
    upCount: 40,
    downCount: 12,
    mainNetFlow: 1_234_000_000,
    totalMv: 2_500_000_000_000,
    aggMethod: 'BOARD_WEIGHTED',
    leaderStock: { code: '601579', name: '会稽山', pct: 9.99 },
    boards: [
      { boardName: '白酒', pctDay: 2.1, upCount: 18, downCount: 3, mainNetFlow: 900_000_000, totalMv: 1_800_000_000_000 },
      { boardName: '乳品', pctDay: -0.3, upCount: 5, downCount: 9, mainNetFlow: -120_000_000, totalMv: 400_000_000_000 },
    ],
    constituents: null,
    leaders: [leaderOf(1)],
    memberCount: 52,
    ...overrides,
  };
}

function configOf() {
  return {
    mainline: {
      wp: 0.4,
      wh: 0.3,
      we: 0.3,
      priceWinDay: 0.6,
      priceWinD5: 0.4,
      heatH24: 0.5,
      heatD7: 0.3,
      heatDelta: 0.2,
      topN: 5,
      persistMinDays: 2,
      persistWindowDays: 5,
      topThirdRank: 10,
      divergenceHeatRank: 15,
      updatedAt: '2026-09-28T10:00:00Z',
    },
    leader: {
      wa: 0.5,
      wv: 0.3,
      wq: 0.2,
      mentionDays: 7,
      topN: 3,
      qDay: 0.6,
      qD5: 0.4,
      updatedAt: '2026-09-28T10:00:00Z',
    },
  };
}

/** 两区块基线路由（热力图 + 榜单——多数用例共用）。 */
function baseRoutes(heat: IndustryHeatMapView, mainline: IndustryMainlineView) {
  return [
    { path: '/api/v1/industry-heat-map', respond: () => ok(heat) },
    { path: '/api/v1/industry-mainline', respond: () => ok(mainline) },
  ];
}

/** 下钻路由（industry 经 encodeURIComponent 编码进路径——与 api 层真实请求一致）。 */
function detailRoute(detail: IndustryMainlineDetailView) {
  return {
    path: `/api/v1/industry-mainline/${encodeURIComponent(detail.industry)}/detail`,
    respond: () => ok(detail),
  };
}

async function renderPage() {
  const { IndustryMainline } = await import('@/pages/IndustryMainline');
  return render(<IndustryMainline />);
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  cleanup();
  localStorage.clear();
  window.location.hash = '';
});

describe('IndustryMainline 行业主线页（T245，#/industry-mainline 第 18 页）', () => {
  it('热力图渲染：31 格 CSS Grid + 快照 meta 脚注 + 色阶图例（-5%~+5% 渐变条）', async () => {
    const cells = Array.from({ length: 31 }, (_, i) => heatCellOf(`行业${i}`));
    stubFetch(baseRoutes(heatViewOf(cells), mainlineViewOf([mainlineItemOf()])));

    await renderPage();

    const grid = await screen.findByTestId('heat-map-grid');
    expect(grid).toBeInTheDocument();
    for (let i = 0; i < 31; i++) {
      expect(screen.getByTestId(`heat-cell-行业${i}`)).toBeInTheDocument();
    }
    // 图例渐变条 + 快照 meta
    expect(screen.getByTestId('heat-legend')).toHaveTextContent('-5%');
    expect(screen.getByTestId('heat-legend')).toHaveTextContent('+5%');
    expect(screen.getByTestId('heat-meta')).toHaveTextContent('2026-09-28');
    // 榜单区块同帧渲染（互不阻塞）
    expect(await screen.findByTestId('mainline-list')).toBeInTheDocument();
  });

  it('色阶映射：涨红跌绿 + 色深随 |pctDay| 线性 + ±5% 封顶', async () => {
    stubFetch(
      baseRoutes(
        heatViewOf([
          heatCellOf('大涨', { pctDay: 4.5 }),
          heatCellOf('小涨', { pctDay: 0.8 }),
          heatCellOf('下跌', { pctDay: -3.2 }),
        ]),
        mainlineViewOf([mainlineItemOf()]),
      ),
    );

    await renderPage();

    const up = (await screen.findByTestId('heat-cell-大涨')).style.backgroundColor;
    const upWeak = screen.getByTestId('heat-cell-小涨').style.backgroundColor;
    const down = screen.getByTestId('heat-cell-下跌').style.backgroundColor;
    const alpha = (c: string) => Number(c.match(/rgba?\([^)]*,\s*([\d.]+)\)/)?.[1] ?? 1);
    expect(up).toContain('239, 68, 68'); // red-500（方向轨同源）
    expect(down).toContain('34, 197, 94'); // green-500（A 股跌绿）
    expect(alpha(up)).toBeGreaterThan(alpha(upWeak)); // 色深线性
  });

  it('hover 详情：格子 title 携带全部信息（行业/当日/5日/涨跌家数/主力净流入/总市值/领涨股）', async () => {
    stubFetch(baseRoutes(heatViewOf([heatCellOf('食品饮料')]), mainlineViewOf([mainlineItemOf()])));

    await renderPage();

    const cell = await screen.findByTestId('heat-cell-食品饮料');
    const title = cell.getAttribute('title') ?? '';
    expect(title).toContain('食品饮料');
    expect(title).toContain('当日 +1.23%');
    expect(title).toContain('5日 +2.50%');
    expect(title).toContain('涨 40 家');
    expect(title).toContain('跌 12 家');
    expect(title).toContain('主力净流入');
    expect(title).toContain('总市值');
    expect(title).toContain('领涨股 会稽山');
  });

  it('stale 黄标：meta.stale=true 显示「数据截至」黄标，stale=false 不显示', async () => {
    stubFetch(
      baseRoutes(heatViewOf([heatCellOf('食品饮料')], { stale: true, quoteTime: '2026-09-28T11:30:00+08:00' }), mainlineViewOf([mainlineItemOf()])),
    );

    await renderPage();

    const badge = await screen.findByTestId('heat-stale-badge');
    expect(badge).toHaveTextContent('数据截至');
  });

  it('热力图盘中轮询：60s 自动刷新（页面节奏，方案 §4.6——轮询频率 ≠ 采集频率）', async () => {
    vi.useFakeTimers();
    try {
      const fetchMock = stubFetch(baseRoutes(heatViewOf([heatCellOf('食品饮料')]), mainlineViewOf([mainlineItemOf()])));
      const { IndustryMainline } = await import('@/pages/IndustryMainline');
      render(<IndustryMainline refreshMs={1_000} />);
      await vi.advanceTimersByTimeAsync(500);
      const callsAfterFirst = fetchMock.mock.calls.filter((c) => String(c[0]).includes('/industry-heat-map')).length;
      expect(callsAfterFirst).toBe(1);
      await vi.advanceTimersByTimeAsync(1_000);
      const callsAfterTick = fetchMock.mock.calls.filter((c) => String(c[0]).includes('/industry-heat-map')).length;
      expect(callsAfterTick).toBe(2); // 轮询刷新热力图（榜单日频不轮询）
      const mainlineCalls = fetchMock.mock.calls.filter((c) => String(c[0]).includes('/industry-mainline?') || String(c[0]).endsWith('/industry-mainline')).length;
      expect(mainlineCalls).toBe(1); // 榜单不随热力图轮询重拉
    } finally {
      vi.useRealTimers();
    }
  });

  it('榜单卡：排名徽章/行业名/主线分/三维迷你条/持续性/热度排名/背离标注/互链齐备', async () => {
    stubFetch(
      baseRoutes(
        heatViewOf([heatCellOf('食品饮料')]),
        mainlineViewOf([
          mainlineItemOf(),
          mainlineItemOf({
            rankNo: 2,
            industry: '电子',
            mainScore: 71.2,
            divergence: 'PRICE_HOT_HEAT_COLD',
            heatRank: 18,
            dimDetail: {
              price: { score: 95, rank: 1, raw: 4.1 },
              heat: { score: 30, rank: 20, raw: 40 },
              event: { score: 60, rank: 8, raw: 3 },
            },
          }),
        ]),
      ),
    );

    await renderPage();

    const list = await screen.findByTestId('mainline-list');
    expect(list).toBeInTheDocument();
    expect(screen.getByTestId('mainline-card-1')).toHaveTextContent('食品饮料');
    expect(screen.getByTestId('mainline-card-1')).toHaveTextContent('88.6');
    expect(screen.getByTestId('mainline-card-1')).toHaveTextContent('持续 4 天');
    expect(screen.getByTestId('mainline-card-1')).toHaveTextContent('热度第 3');
    for (const dim of ['price', 'heat', 'event']) {
      expect(screen.getByTestId(`mainline-dim-1-${dim}`)).toBeInTheDocument();
    }
    // 背离标注：PRICE_HOT_HEAT_COLD → 黄「价热讯冷」
    expect(screen.getByTestId('mainline-divergence-2')).toHaveTextContent('价热讯冷');
    // 依据三路互链：热度→#/industry-heat、事件→#/events
    expect(screen.getByTestId('mainline-link-heat-1')).toHaveAttribute('href', '#/industry-heat');
    expect(screen.getByTestId('mainline-meta')).toHaveTextContent('2026-09-28');
  });

  it('龙头展开：龙一/二/三名次徽章 + 综合分 + 三维分解 + 主力徽章 + 事件引用可点 + 免责常驻', async () => {
    stubFetch(baseRoutes(heatViewOf([heatCellOf('食品饮料')]), mainlineViewOf([mainlineItemOf()])));

    await renderPage();

    await screen.findByTestId('mainline-card-1');
    await userEvent.click(screen.getByTestId('mainline-leaders-toggle-1'));

    const card = await screen.findByTestId('leader-card-1');
    expect(card).toHaveTextContent('龙一');
    expect(card).toHaveTextContent('贵州茅台');
    expect(card).toHaveTextContent('87.3');
    expect(card).toHaveTextContent('资讯关注度');
    expect(card).toHaveTextContent('价值评分');
    expect(card).toHaveTextContent('价格动量');
    // 主力徽章：龙虎榜次数 + 增减持方向
    expect(screen.getByTestId('leader-attention-1')).toHaveTextContent('龙虎榜(30日) 3 次');
    expect(screen.getByTestId('leader-attention-1')).toHaveTextContent('净增持 2 次');
    // 事件引用：eventIds 可点跳事件流
    expect(screen.getByTestId('leader-event-link-1-10231')).toHaveAttribute('href', '#/events?focus=10231');
    // 免责一行常驻
    expect(screen.getByTestId('leader-disclaimer-1')).toHaveTextContent('非投资建议');
    // 龙二/龙三同渲染
    expect(screen.getByTestId('leader-card-2')).toHaveTextContent('龙二');
    expect(screen.getByTestId('leader-card-3')).toHaveTextContent('龙三');
  });

  it('主力徽章 UNAVAILABLE：attention.state=UNAVAILABLE 显示「暂无数据」不阻塞', async () => {
    const item = mainlineItemOf({
      leaders: [leaderOf(1, { attention: { lhb30d: null, lhbLatest: null, chgDirection: null, chgCount: null, queryTime: null, state: 'UNAVAILABLE' } })],
    });
    stubFetch(baseRoutes(heatViewOf([heatCellOf('食品饮料')]), mainlineViewOf([item])));

    await renderPage();

    await screen.findByTestId('mainline-card-1');
    await userEvent.click(screen.getByTestId('mainline-leaders-toggle-1'));
    expect(await screen.findByTestId('leader-attention-1')).toHaveTextContent('暂无数据');
  });

  it('下钻（通道 A）：点击热力图格子弹出行业详情——行情行 + 板块列表 + 龙头完整信息 + 成员数', async () => {
    const fetchMock = stubFetch([
      ...baseRoutes(heatViewOf([heatCellOf('食品饮料')]), mainlineViewOf([mainlineItemOf()])),
      detailRoute(detailViewOf()),
    ]);

    await renderPage();

    await screen.findByTestId('heat-cell-食品饮料');
    await userEvent.click(screen.getByTestId('heat-cell-食品饮料'));

    const detail = await screen.findByTestId('mainline-detail-dialog');
    expect(detail).toHaveTextContent('食品饮料');
    expect(detail).toHaveTextContent('当日');
    expect(detail).toHaveTextContent('白酒');
    expect(detail).toHaveTextContent('乳品');
    expect(detail).toHaveTextContent('成员 52');
    expect(detail).toHaveTextContent('龙一');
    const detailCall = fetchMock.mock.calls.map((c) => String(c[0])).find((u) => u.includes('/detail'));
    expect(detailCall).toContain(encodeURIComponent('食品饮料'));
  });

  it('下钻（通道 B）：形态 B 无板块列表，显示成分股涨跌 + 领涨股', async () => {
    stubFetch([
      ...baseRoutes(heatViewOf([heatCellOf('食品饮料')]), mainlineViewOf([mainlineItemOf()])),
      detailRoute(
        detailViewOf({
          source: 'tencent-rank',
          aggMethod: 'TENCENT_DIRECT',
          boards: null,
          constituents: [
            { code: '601579', name: '会稽山', pctChange: 9.99 },
            { code: '600600', name: '青岛啤酒', pctChange: -1.2 },
          ],
        }),
      ),
    ]);

    await renderPage();

    await screen.findByTestId('heat-cell-食品饮料');
    await userEvent.click(screen.getByTestId('heat-cell-食品饮料'));

    const detail = await screen.findByTestId('mainline-detail-dialog');
    expect(detail).toHaveTextContent('会稽山');
    expect(detail).toHaveTextContent('青岛啤酒');
    expect(detail).toHaveTextContent('领涨股');
  });

  it('下钻入口二：榜单行业名同样打开详情 Dialog', async () => {
    stubFetch([
      ...baseRoutes(heatViewOf([heatCellOf('食品饮料')]), mainlineViewOf([mainlineItemOf()])),
      detailRoute(detailViewOf()),
    ]);

    await renderPage();

    await screen.findByTestId('mainline-card-1');
    await userEvent.click(screen.getByTestId('mainline-industry-link-1'));

    expect(await screen.findByTestId('mainline-detail-dialog')).toBeInTheDocument();
  });

  it('重算：POST recompute 受理 → 刷新榜单 + 成功提示', async () => {
    const fetchMock = stubFetch([
      ...baseRoutes(heatViewOf([heatCellOf('食品饮料')]), mainlineViewOf([mainlineItemOf()], { version: 1 })),
      { path: '/api/v1/industry-mainline/recompute', respond: () => ok({ rankDate: '2026-09-28', topSize: 5, detail: '31 行业 · 门槛通过 8' }) },
    ]);

    await renderPage();

    await screen.findByTestId('mainline-card-1');
    await userEvent.click(screen.getByTestId('mainline-recompute'));

    const post = fetchMock.mock.calls.find((c) => String(c[0]).includes('/recompute'));
    expect(post?.[1]?.method).toBe('POST');
    expect(await screen.findByTestId('mainline-recompute-done')).toHaveTextContent('重算完成');
    expect(await screen.findByTestId('mainline-card-1')).toBeInTheDocument();
  });

  it('配置 Dialog：GET 预填 → 改 topN → PATCH 全量 + expectedUpdatedAt → 保存成功关闭', async () => {
    const config = configOf();
    const fetchMock = stubFetch([
      ...baseRoutes(heatViewOf([heatCellOf('食品饮料')]), mainlineViewOf([mainlineItemOf()])),
      { path: '/api/v1/industry-mainline/config', respond: (_url: string, init?: RequestInit) => (init?.method === 'PATCH' ? ok(configOf()) : ok(config)) },
    ]);

    await renderPage();

    await screen.findByTestId('mainline-card-1');
    await userEvent.click(screen.getByTestId('mainline-config-open'));

    const topNInput = await screen.findByTestId('mainline-config-topN');
    expect(topNInput).toHaveValue('5'); // GET 预填
    await userEvent.clear(topNInput);
    await userEvent.type(topNInput, '4');
    await userEvent.click(screen.getByTestId('mainline-config-save'));

    await waitFor(() => {
      const patch = fetchMock.mock.calls.find(
        (c) => String(c[0]).includes('/config') && c[1]?.method === 'PATCH',
      );
      expect(patch).toBeDefined();
      const body = JSON.parse(String(patch?.[1]?.body));
      expect(body.topN).toBe(4);
      expect(body.expectedUpdatedAt).toBe('2026-09-28T10:00:00Z');
      expect(body.wp).toBe(0.4); // 全量替换
    });
    await waitFor(() => expect(screen.queryByTestId('mainline-config-dialog')).not.toBeInTheDocument());
  });

  it('配置校验：topN 非法（>5）前端先拦，不发 PATCH', async () => {
    const fetchMock = stubFetch([
      ...baseRoutes(heatViewOf([heatCellOf('食品饮料')]), mainlineViewOf([mainlineItemOf()])),
      { path: '/api/v1/industry-mainline/config', respond: () => ok(configOf()) },
    ]);

    await renderPage();

    await screen.findByTestId('mainline-card-1');
    await userEvent.click(screen.getByTestId('mainline-config-open'));

    const topNInput = await screen.findByTestId('mainline-config-topN');
    await userEvent.clear(topNInput);
    await userEvent.type(topNInput, '9');
    await userEvent.click(screen.getByTestId('mainline-config-save'));

    expect(await screen.findByTestId('mainline-config-error')).toBeInTheDocument();
    const patched = fetchMock.mock.calls.some(
      (c) => String(c[0]).includes('/config') && c[1]?.method === 'PATCH',
    );
    expect(patched).toBe(false);
  });

  it('三态互不拖垮（正向）：热力图 500 错误 → 热力图区块错误重试 + 榜单照常渲染', async () => {
    stubFetch([
      { path: '/api/v1/industry-heat-map', respond: () => fail(500, 50000, '服务异常') },
      { path: '/api/v1/industry-mainline', respond: () => ok(mainlineViewOf([mainlineItemOf()])) },
    ]);

    await renderPage();

    expect(await screen.findByTestId('heat-error')).toBeInTheDocument();
    expect(await screen.findByTestId('mainline-list')).toBeInTheDocument();
  });

  it('三态互不拖垮（反向）：榜单 30094 无榜 → 榜单空态 + 热力图照常渲染', async () => {
    stubFetch([
      { path: '/api/v1/industry-heat-map', respond: () => ok(heatViewOf([heatCellOf('食品饮料')])) },
      { path: '/api/v1/industry-mainline', respond: () => fail(404, 30094, '该日无主线榜单') },
    ]);

    await renderPage();

    expect(await screen.findByTestId('mainline-empty')).toBeInTheDocument();
    expect(await screen.findByTestId('heat-cell-食品饮料')).toBeInTheDocument();
  });

  it('热力图空态：30093 全空 → EmptyState 引导（非错误）', async () => {
    stubFetch([
      { path: '/api/v1/industry-heat-map', respond: () => fail(404, 30093, '行业行情快照无数据') },
      { path: '/api/v1/industry-mainline', respond: () => ok(mainlineViewOf([mainlineItemOf()])) },
    ]);

    await renderPage();

    expect(await screen.findByTestId('heat-empty')).toBeInTheDocument();
    expect(screen.queryByTestId('heat-error')).not.toBeInTheDocument();
  });

  it('degraded 榜单：降级徽章 + 原因文案如实标注', async () => {
    stubFetch(
      baseRoutes(
        heatViewOf([heatCellOf('食品饮料')], { source: 'tencent-rank' }),
        mainlineViewOf([mainlineItemOf()], { degraded: true, degradedReason: 'DIMENSION_MISSING' }),
      ),
    );

    await renderPage();

    expect(await screen.findByTestId('mainline-degraded')).toBeInTheDocument();
    // 页脚数据源标注（行情源 + 资金面）
    expect(screen.getByTestId('mainline-footnote')).toHaveTextContent('腾讯');
    expect(screen.getByTestId('mainline-footnote')).toHaveTextContent('datacenter');
  });
});

describe('heatCellShade 色阶纯函数（T245，方向轨单点导出）', () => {
  it('涨红跌绿：与 directionTextClass 轨同源色值', () => {
    expect(heatCellShade(2).backgroundColor).toContain('239, 68, 68');
    expect(heatCellShade(-2).backgroundColor).toContain('34, 197, 94');
  });

  it('色深线性 + ±5% 封顶', () => {
    const alpha = (pct: number) => {
      const c = heatCellShade(pct).backgroundColor;
      return Number(c.match(/,\s*([\d.]+)\)$/)![1]);
    };
    expect(alpha(0.5)).toBeLessThan(alpha(2.5));
    expect(alpha(2.5)).toBeLessThan(alpha(HEAT_SCALE_MAX_PCT));
    expect(alpha(7)).toBe(alpha(HEAT_SCALE_MAX_PCT)); // 封顶
    expect(alpha(-7)).toBe(alpha(-HEAT_SCALE_MAX_PCT));
  });

  it('零轴近透明 + 文字深浅自动切换（浅底深字 / 深底白字）', () => {
    expect(heatCellShade(0).backgroundColor).toBe('transparent');
    expect(heatCellShade(null).backgroundColor).toBe('transparent');
    // 深色（红/绿高饱和）→ 白字
    expect(heatCellShade(5).color).toBe('#ffffff');
    expect(heatCellShade(-5).color).toBe('#ffffff');
  });
});

// —— M29 T257 三市场切换：四接口带 market + bootstrap/龙头占位/币种符号 + URL 持久化 ——

describe('IndustryMainline 行业主线页（T257）· 三市场切换', () => {
  it('M29 切美股：heat-map/mainline 带 market=US + URL 持久化 + 口径标注/原币符号回显', async () => {
    const usHeat = heatViewOf(
      [heatCellOf('软件与信息服务', { pctDay: -0.78, totalMv: 4_935_600, mainNetFlow: null })],
      {
        market: 'US',
        industrySystem: '美股：东财行业分类（归并 ≤40，来源 F10 BELONG_INDUSTRY）',
        currency: 'USD',
        source: 'hkus-aggregate',
        quoteTime: '2026-09-29T04:00:01-04:00',
      },
    );
    const usMainline = mainlineViewOf(
      [
        mainlineItemOf({
          industry: '软件与信息服务',
          leaders: [],
          leadersAvailable: false,
          leaderUnavailableReason: '港美股龙头分析暂未支持（依赖基本面因子体系）',
        }),
      ],
      {
        market: 'US',
        industrySystem: '美股：东财行业分类（归并 ≤40）',
        bootstrap: true,
        basis: 'mainline-v1:m2',
      },
    );
    const fetchMock = stubFetch([
      { path: '/api/v1/industry-heat-map', respond: () => ok(usHeat) },
      { path: '/api/v1/industry-mainline', respond: () => ok(usMainline) },
    ]);
    const user = userEvent.setup();
    await renderPage();

    // 默认 A 股请求带 market=A_SHARE（显式下发——后端缺省同值零回归）
    expect(await screen.findByTestId('mainline-card-1')).toBeInTheDocument();
    expect(String(fetchMock.mock.calls[0][0])).toContain('market=A_SHARE');

    // 切美股：URL 持久化 + 两接口重查带 market=US
    await user.click(screen.getByTestId('market-tab-US'));
    expect(window.location.hash).toContain('market=US');
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some((call) => String(call[0]).includes('/industry-heat-map?market=US')),
      ).toBe(true),
    );
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some((call) => String(call[0]).includes('/industry-mainline?market=US')),
      ).toBe(true),
    );

    // 口径标注 + 原币符号（拍板二/六）+ 冷启动徽章（拍板五）
    expect(await screen.findByTestId('heat-meta')).toHaveTextContent('美股：东财行业分类');
    expect(screen.getByTestId('heat-meta')).toHaveTextContent('$');
    expect(screen.getByTestId('mainline-meta')).toHaveTextContent('美股：东财行业分类');
    expect(screen.getByTestId('mainline-bootstrap')).toHaveTextContent('冷启动');

    // 港美股龙头占位不静默（W1）——不渲染龙头展开按钮
    expect(screen.queryByTestId('mainline-leaders-toggle-1')).toBeNull();
    expect(screen.getByTestId('mainline-leaders-unavailable-1')).toHaveTextContent(
      '港美股龙头分析暂未支持',
    );

    // 市值 hover title 带原币符号（拍板六：原币不折算）
    const cell = await screen.findByTestId('heat-cell-软件与信息服务');
    expect(cell).toHaveAttribute('title');
    expect(cell.getAttribute('title')).toContain('$');
  });

  it('M29 切港股下钻：detail 带 market=HK（同名行业消歧）+ 龙头占位 + 市值 HK$ 符号', async () => {
    const hkDetail = detailViewOf({
      market: 'HK',
      industrySystem: '港股：东财行业分类（31 直采）',
      industry: '软件服务',
      currency: 'HKD',
      totalMv: 4_935_600,
      boards: null,
      constituents: null,
      leaders: [],
      leadersAvailable: false,
      leaderUnavailableReason: '港美股龙头分析暂未支持（依赖基本面因子体系）',
      memberCount: 120,
    });
    const fetchMock = stubFetch([
      { path: '/api/v1/industry-heat-map', respond: () => ok(heatViewOf([heatCellOf('软件服务')])) },
      {
        path: '/api/v1/industry-mainline',
        respond: () =>
          ok(
            mainlineViewOf(
              [mainlineItemOf({ industry: '软件服务', leaders: [], leadersAvailable: false })],
              { market: 'HK' },
            ),
          ),
      },
      { path: '/api/v1/industry-mainline/%E8%BD%AF%E4%BB%B6%E6%9C%8D%E5%8A%A1/detail', respond: () => ok(hkDetail) },
    ]);
    const user = userEvent.setup();
    window.location.hash = '#/industry-mainline?market=HK';
    await renderPage();

    // 直达初值 = 港股（挂载读 ?market=）
    expect(await screen.findByTestId('market-tab-HK')).toHaveAttribute('aria-selected', 'true');
    await user.click(screen.getByTestId('mainline-industry-link-1'));

    expect(await screen.findByTestId('mainline-detail-dialog')).toBeInTheDocument();
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(
          (call) => String(call[0]).includes('/detail?market=HK'),
        ),
      ).toBe(true),
    );
    // 龙头占位说明（不静默留白）+ 总市值 HK$ 原币符号
    expect(screen.getByTestId('detail-leaders-unavailable')).toHaveTextContent('港美股龙头分析暂未支持');
    expect(screen.getByTestId('detail-quote')).toHaveTextContent('HK$');
  });
});
