import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Overview } from '@/pages/Overview';
import type { OverviewView } from '@/types/overview';
import type { FeedItemView, FeedPage } from '@/types/feed';
import type {
  DailyRecommendationView,
  TopRecommendation,
} from '@/types/recommendation';
import type { IndustryHeatBoardView } from '@/types/industryHeat';
import type { EventStreamView } from '@/types/eventStream';
import type { FeedDashboardView } from '@/types/feedDashboard';
import type { PipelineStatusView } from '@/types/pipelineStatus';
import type { MarketTopItem, MarketTopRankView } from '@/types/marketTop';

// —— fetch mock：GET /api/v1/overview（聚合，可变异供重试路径复用） ——

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

function viewOf(overrides: Partial<OverviewView> = {}): OverviewView {
  return {
    llmToday: { tokenUsed: 8400, costMicros: 34_200, budgetTokens: 20_000, status: 'OK', error: null },
    anomalyToday: { count: 3, error: null },
    policy24h: {
      count: 12,
      latest: [{ id: 101, title: '关于人工智能的行动方案', publishedAt: '2026-09-21' }],
      error: null,
    },
    jobHealth: { windowRuns: 412, windowFailed: 0, unhealthyJobs: [], error: null },
    sourceHealth: [
      { sourceCode: 'QUOTE', mode: 'REAL', lastEventType: 'OK', lastEventAt: '2026-09-22T02:00:00Z', errors24h: 0 },
      { sourceCode: 'POLICY', mode: 'MOCK', lastEventType: null, lastEventAt: null, errors24h: 0 },
    ],
    sourceHealthError: null,
    ...overrides,
  };
}

// —— 概览页按 URL 路由的 fetch stub（页面并发请求 /overview 与 /feed/personal，
//    推荐卡另有 /recommendations/daily；子组件 effect 先于父组件，不能按调用序计数） ——

/** 推荐条目（feed 只读路径的 type=recommendation 条目：title=名称、summary=理由）。 */
function recFeedItem(overrides: Partial<FeedItemView> = {}): FeedItemView {
  return {
    id: 1,
    contentId: 'recommendation:SH600519',
    type: 'recommendation',
    title: '贵州茅台',
    summary: '信息面活跃度提升：公告与新闻热度居前',
    publishedAt: '2026-09-22T07:00:00Z',
    source: '每日推荐',
    url: null,
    subjectCode: 'SH600519',
    subjectName: '贵州茅台',
    matchReason: '每日推荐',
    keywords: [],
    ...overrides,
  };
}

function feedPageOf(
  overrides: Partial<FeedPage> & { items?: FeedItemView[] } = {},
): FeedPage {
  return { items: [], nextCursor: null, recommendationPending: false, ...overrides };
}

function topOf(overrides: Partial<TopRecommendation> = {}): TopRecommendation {
  return { subjectCode: 'SH600519', subjectName: '贵州茅台', reason: '信息面活跃度提升', rank: 1, ...overrides };
}

function dailyViewOf(
  overrides: Partial<DailyRecommendationView> & { topRecommend?: TopRecommendation[] } = {},
): DailyRecommendationView {
  return {
    status: 1,
    topRecommend: [topOf()],
    disclaimer: 'AI 生成，非投资建议',
    ...overrides,
  };
}

type StubResponse = ReturnType<typeof ok> | ReturnType<typeof fail>;
/** 响应器：同步返回或挂起 Promise（生成中占位测试用延迟放行）。 */
type StubResponder = () => StubResponse | Promise<StubResponse>;

// —— T223 概览重组数据工厂（热度/事件迁移块 + Top10 精华 + 健康状态条，复用既有 API 契约面） ——

function heatBoardOf(): IndustryHeatBoardView {
  return {
    window: 'D7',
    industries: [
      { industry: '电子', heatScore: 92, prevScore: 80, deltaPct: 15, newsCount: 120, eventCount: 8 },
      { industry: '计算机', heatScore: 85, prevScore: 88, deltaPct: -3.4, newsCount: 96, eventCount: 5 },
      { industry: '医药生物', heatScore: 70, prevScore: 70, deltaPct: 0, newsCount: 60, eventCount: 2 },
    ],
    basis: 'heat-v1:d7-window',
    snapshotAt: '2026-09-22T07:00:00Z',
    pipeline: { level: 'NORMAL' },
  };
}

function eventsOf(): EventStreamView {
  return {
    total: 3,
    items: [
      {
        id: 71,
        eventType: 'POLICY_RELEASE',
        summary: '工信部发布行业规范条件',
        industries: ['电子'],
        direction: 'BULLISH',
        importance: 'HIGH',
        figures: [],
        subjects: [],
        quote: null,
        newsId: 10,
        newsTitle: null,
        newsUrl: null,
        eventTime: '2026-09-22T04:00:00Z',
      },
    ],
    nextBeforeId: null,
  };
}

function marketItemOf(overrides: Partial<MarketTopItem> = {}): MarketTopItem {
  return {
    rankNo: 1,
    subjectId: 11,
    subjectCode: 'SH600519',
    subjectName: '贵州茅台',
    totalScore: 88.6,
    finalScore: 87.2,
    percentile: 92,
    breakthrough: false,
    factors: [],
    generation: 'FULL',
    diveMethod: null,
    diveSummary: null,
    diveDetail: null,
    evidenceCount: 5,
    lastEventDate: '2026-09-21',
    prevRank: 3,
    changeType: 'UP',
    computedAt: '2026-09-21T10:00:00Z',
    ...overrides,
  };
}

function rankViewOf(items: MarketTopItem[]): MarketTopRankView {
  return {
    rankDate: '2026-09-22',
    version: 1,
    triggerSource: 'DAILY',
    batch: {
      snapshotDate: '2026-09-21',
      computedAt: '2026-09-21T10:00:00Z',
      degraded: false,
      degradedReason: null,
      funnelStats: {},
      dropped: [],
      lastEvent: null,
    },
    items,
    disclaimer: 'AI 分析仅供参考，非投资建议',
    recentIncrement: null,
  };
}

function top5Of(): MarketTopItem[] {
  return [
    marketItemOf(),
    marketItemOf({ rankNo: 2, subjectId: 12, subjectCode: 'SZ002594', subjectName: '比亚迪', finalScore: 85.9, percentile: 90, prevRank: 1, changeType: 'DOWN' }),
  ];
}

function dashboardOf(): FeedDashboardView {
  return {
    global: {
      todayNewCount: 1732,
      todayDupCount: 40,
      activeSourceCount: 28,
      failedSourceCount: 0,
      latency: {
        p50Millis: 180000,
        p90Millis: 540000,
        sampleCount: 200,
        basis: 'incremental-only-v1:exclude-first-day+daily-sources',
        excludedSourceCodes: [],
      },
    },
    sources: [
      {
        sourceId: 1,
        sourceCode: 's1',
        name: '源一',
        category: '分类',
        adapterType: 'preset',
        intervalMinutes: 15,
        enabled: true,
        preset: true,
        deleted: false,
        staleSince: null,
        todayPollCount: 10,
        todayNewCount: 5,
        todayFailCount: 0,
        todayDupCount: 0,
        totalCount: 100,
        lastAttemptAt: null,
        lastSuccessAt: null,
        nextDueAt: null,
        backoffUntil: null,
        consecutiveFailures: 0,
        lastError: null,
        lastRoundDetail: null,
        runState: 'ok',
        abnormal: false,
      },
    ],
    failures: [],
  };
}

function pipelineOf(): PipelineStatusView {
  return {
    jobKey: 'NEWS_PIPELINE',
    level: 'NORMAL',
    todayCostMicros: 500_000,
    budgetMicros: 2_600_000,
    costBasis: 'cost-v2:m18-30src',
  };
}

/**
 * 默认：overview 正常 / feed 只读空页 / daily status=1 /
 * 迁移块（industry-heat / events）与新区块（market-top / feed-dashboard / pipeline/status）正常。
 */
function routeFetch(
  opts: {
    overview?: StubResponder;
    feed?: StubResponder;
    daily?: StubResponder;
    heat?: StubResponder;
    marketTop?: StubResponder;
  } = {},
) {
  return vi.fn(async (url: unknown) => {
    const path = String(url);
    if (path.includes('/recommendations/daily')) return (await opts.daily?.()) ?? ok(dailyViewOf());
    if (path.includes('/feed/personal')) return (await opts.feed?.()) ?? ok(feedPageOf());
    if (path.includes('/overview')) return (await opts.overview?.()) ?? ok(viewOf());
    if (path.includes('/market-top')) return (await opts.marketTop?.()) ?? ok(rankViewOf(top5Of()));
    if (path.includes('/pipeline/status')) return ok(pipelineOf());
    if (path.includes('/feed-dashboard')) return ok(dashboardOf());
    if (path.includes('/industry-heat')) return (await opts.heat?.()) ?? ok(heatBoardOf());
    if (path.includes('/events')) return ok(eventsOf());
    return fail(500, 50000, '未知端点');
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  vi.useRealTimers();
  cleanup();
  localStorage.clear();
  window.location.hash = '';
});

describe('Overview 概览重组 11→6（T223，V3.0）', () => {
  it('区块计数：6 块 = 今日推荐主位 + Top10 精华 + 最新事件 + 热度 Top5 + 今日异动 + 健康状态条', async () => {
    vi.stubGlobal('fetch', routeFetch());

    render(<Overview />);

    // 六块齐备（今日推荐卡自管三态，root testid rec-card）
    expect(await screen.findByTestId('rec-card')).toBeInTheDocument();
    expect(screen.getByTestId('top10-digest')).toBeInTheDocument();
    expect(screen.getByTestId('overview-events')).toBeInTheDocument();
    expect(screen.getByTestId('overview-heat')).toBeInTheDocument();
    expect(screen.getByTestId('stat-card-anomaly')).toBeInTheDocument();
    expect(screen.getByTestId('health-strip')).toBeInTheDocument();

    // 移除项不再渲染：WorkbenchPanel 四块（推荐摘要/大盘健康）+ 最新政策卡 + 平台健康三卡 + 旧区块容器
    expect(screen.queryByTestId('overview-workbench')).toBeNull();
    expect(screen.queryByTestId('workbench-recommendations')).toBeNull();
    expect(screen.queryByTestId('workbench-health')).toBeNull();
    expect(screen.queryByTestId('stat-card-policy')).toBeNull();
    expect(screen.queryByTestId('stat-card-llm-today')).toBeNull();
    expect(screen.queryByTestId('stat-card-job-health')).toBeNull();
    expect(screen.queryByTestId('stat-card-source-health')).toBeNull();
    expect(screen.queryByTestId('overview-platform')).toBeNull();
  });

  it('Top10 精华与榜单页同源对账：前 5 名名次/名称/终分/变动与首查数据一致（拍板五）', async () => {
    vi.stubGlobal('fetch', routeFetch());

    render(<Overview />);

    const first = await screen.findByTestId('top10-digest-row-SH600519');
    expect(first).toHaveTextContent('贵州茅台');
    expect(first).toHaveTextContent('87.2'); // finalScore 与榜单页 market-top-final 同源
    expect(first).toHaveTextContent('↑2'); // prevRank 3 → rankNo 1
    expect(screen.getByTestId('top10-digest-row-SZ002594')).toHaveTextContent('比亚迪');
    expect(screen.getByTestId('top10-digest-link')).toHaveAttribute('href', '#/market-top');
  });

  it('迁移块：最新事件（重要度徽章 + 摘要 + 时间）与热度 Top5（涨红跌绿）沿既有语义渲染', async () => {
    vi.stubGlobal('fetch', routeFetch());

    render(<Overview />);

    const events = await screen.findByTestId('overview-events');
    expect(events).toHaveTextContent('工信部发布行业规范条件');
    expect(screen.getByTestId('overview-event-71')).toBeInTheDocument();
    expect(screen.getByTestId('overview-events-link')).toHaveAttribute('href', '#/events');

    const heat = screen.getByTestId('overview-heat');
    expect(heat).toHaveTextContent('电子');
    expect(heat).toHaveTextContent('92 分');
    expect(screen.getByTestId('overview-heat-link')).toHaveAttribute('href', '#/industry-heat');
  });

  it('迁移块行级跳转保留：热度行带 industry 参数下钻、事件行跳事件流；今日异动整卡跳自选清单', async () => {
    vi.stubGlobal('fetch', routeFetch());

    render(<Overview />);

    await screen.findByTestId('overview-heat-row-电子');
    await userEvent.click(screen.getByTestId('overview-heat-row-电子'));
    expect(window.location.hash).toBe(`#/industry-heat?industry=${encodeURIComponent('电子')}`);

    window.location.hash = '';
    await userEvent.click(screen.getByTestId('overview-event-71'));
    expect(window.location.hash).toBe('#/events');

    expect(screen.getByTestId('stat-card-anomaly-link')).toHaveAttribute('href', '#/watchlists');
  });

  it('单块降级继承：热度块失败显示错误 + 重试且恢复，其余块与今日异动不受拖累', async () => {
    let heatFails = true;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: unknown) => {
        const path = String(url);
        if (path.includes('/recommendations/daily')) return ok(dailyViewOf());
        if (path.includes('/feed/personal')) return ok(feedPageOf());
        if (path.includes('/overview')) return ok(viewOf());
        if (path.includes('/market-top')) return ok(rankViewOf(top5Of()));
        if (path.includes('/pipeline/status')) return ok(pipelineOf());
        if (path.includes('/feed-dashboard')) return ok(dashboardOf());
        if (path.includes('/industry-heat')) {
          return heatFails ? fail(500, 50000, '热度服务异常') : ok(heatBoardOf());
        }
        if (path.includes('/events')) return ok(eventsOf());
        return fail(500, 50000, '未知端点');
      }),
    );

    render(<Overview />);

    expect(await screen.findByTestId('overview-heat-error')).toHaveTextContent('热度服务异常');
    expect(screen.getByTestId('overview-heat-retry')).toBeInTheDocument();
    // 其余块与卡片不受拖累
    expect(await screen.findByTestId('overview-event-71')).toBeInTheDocument();
    expect(screen.getByTestId('top10-digest-row-SH600519')).toBeInTheDocument();
    expect(screen.getByTestId('stat-card-anomaly')).toHaveTextContent('3 条');
    expect(await screen.findByTestId('health-strip')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull(); // 单块降级非阻断性错误

    heatFails = false;
    await userEvent.click(screen.getByTestId('overview-heat-retry'));
    await waitFor(() => expect(screen.getByTestId('overview-heat')).toHaveTextContent('电子'));
  });

  it('30 秒自动刷新 + document.hidden 暂停继承（迁移块与 Top10/状态条同节奏续拉）', async () => {
    vi.useFakeTimers();
    const fetchMock = routeFetch();
    vi.stubGlobal('fetch', fetchMock);
    const hiddenSpy = vi.spyOn(document, 'hidden', 'get');

    render(<Overview />);
    // fake timers 下 waitFor 挂起：小步推进冲刷微任务直至首份数据落地（沿 FeedDashboard 先例）
    for (let i = 0; i < 30 && screen.queryByTestId('overview-heat') == null; i++) {
      await vi.advanceTimersByTimeAsync(50);
    }
    expect(screen.getByTestId('overview-heat')).toBeInTheDocument();
    const heatCalls = () =>
      fetchMock.mock.calls.filter((call) => String(call[0]).includes('/industry-heat')).length;
    const overviewCalls = () =>
      fetchMock.mock.calls.filter((call) => String(call[0]).includes('/overview')).length;
    expect(heatCalls()).toBe(1);
    expect(overviewCalls()).toBe(1);

    // 可见时到期即拉取（迁移块 + 概览聚合同节奏）
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000);
    });
    expect(heatCalls()).toBe(2);
    expect(overviewCalls()).toBe(2);

    // 页面隐藏：跳过本轮
    hiddenSpy.mockReturnValue(true);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(31_000);
    });
    expect(heatCalls()).toBe(2);
    expect(overviewCalls()).toBe(2);
  });

  it('三态·loading：迁移块与 Top10 骨架占位（首帧不出现数据卡）', async () => {
    vi.stubGlobal('fetch', routeFetch());

    render(<Overview />);

    expect(screen.getByTestId('overview-heat-loading')).toBeInTheDocument();
    expect(screen.getByTestId('overview-events-loading')).toBeInTheDocument();
    expect(screen.getByTestId('top10-digest-loading')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('overview-heat')).toHaveTextContent('电子'));
  });

  it('三态·error：整页失败显示错误与重试，重试后恢复', async () => {
    let overviewCalls = 0;
    vi.stubGlobal(
      'fetch',
      routeFetch({
        overview: () => {
          overviewCalls += 1;
          return overviewCalls === 1 ? fail(500, 50000, '服务异常') : ok(viewOf());
        },
      }),
    );

    render(<Overview />);

    expect(await screen.findByTestId('overview-error')).toBeInTheDocument();
    expect(screen.getByTestId('overview-error')).toHaveTextContent('服务异常');

    await userEvent.click(screen.getByTestId('overview-retry'));

    await waitFor(() => expect(screen.getByTestId('stat-card-anomaly')).toBeInTheDocument());
    expect(screen.queryByTestId('overview-error')).toBeNull();
  });

  it('三态·empty：当日无数据显示 0，不报错', async () => {
    vi.stubGlobal(
      'fetch',
      routeFetch({
        overview: () =>
          ok(
            viewOf({
              anomalyToday: { count: 0, error: null },
            }),
          ),
      }),
    );

    render(<Overview />);

    await waitFor(() => expect(screen.getByTestId('stat-card-anomaly')).toBeInTheDocument());
    expect(screen.getByTestId('stat-card-anomaly')).toHaveTextContent('0 条');
    expect(screen.queryByRole('alert')).toBeNull();
  });
});

describe('Overview 今日推荐主位（保留零回归）', () => {
  it('推荐卡·就绪（feed 只读路径）：渲染 Top5（排名/代码+名称/理由首行/免责声明），不触发生成端点', async () => {
    const fetchMock = routeFetch({
      feed: () =>
        ok(
          feedPageOf({
            items: [
              recFeedItem(),
              recFeedItem({ id: 2, title: '中芯国际', summary: '多行理由\n第二行不应展示', subjectCode: 'SH688981', subjectName: '中芯国际' }),
            ],
          }),
        ),
    });
    vi.stubGlobal('fetch', fetchMock);

    render(<Overview />);

    const first = await screen.findByTestId('rec-item-1');
    expect(first).toHaveTextContent('SH600519');
    expect(first).toHaveTextContent('贵州茅台');
    expect(first).toHaveTextContent('信息面活跃度提升');
    expect(first).toHaveAttribute('href', '#/subjects/SH600519');
    const second = screen.getByTestId('rec-item-2');
    expect(second).toHaveTextContent('SH688981');
    expect(second).toHaveTextContent('中芯国际');
    // 理由只取首行
    expect(second).toHaveTextContent('多行理由');
    expect(second).not.toHaveTextContent('第二行不应展示');
    expect(screen.getByTestId('rec-disclaimer')).toHaveTextContent('AI 生成，非投资建议');
    // 挂载只读：不调触发式 /recommendations/daily（防 FAILED 日自动重试烧钱）
    const calledUrls = fetchMock.mock.calls.map((call) => String(call[0]));
    expect(calledUrls.some((url) => url.includes('/recommendations/daily'))).toBe(false);
  });

  it('推荐卡·未生成：recommendationPending 空态 + CTA「立即生成」→ 生成中占位 → status=1 渲染 Top5', async () => {
    // 生成端点挂起（服务端轮询至终态需数秒），先断言「生成中」占位再放行
    let resolveDaily: (value: ReturnType<typeof ok>) => void = () => {};
    const dailyPending = new Promise<ReturnType<typeof ok>>((resolve) => {
      resolveDaily = resolve;
    });
    const fetchMock = routeFetch({
      feed: () => ok(feedPageOf({ recommendationPending: true })),
      daily: () => dailyPending,
    });
    vi.stubGlobal('fetch', fetchMock);

    render(<Overview />);

    expect(await screen.findByTestId('rec-pending')).toHaveTextContent('今日推荐尚未生成');
    expect(fetchMock.mock.calls.some((call) => String(call[0]).includes('/recommendations/daily'))).toBe(
      false,
    );

    await userEvent.click(screen.getByTestId('rec-generate'));

    // 生成中占位（触发式端点在途，不阻塞其余卡片）
    expect(await screen.findByTestId('rec-generating')).toHaveTextContent('AI 生成中');
    expect(screen.getByTestId('stat-card-anomaly')).toHaveTextContent('3 条');

    await act(async () => {
      resolveDaily(
        ok(
          dailyViewOf({
            topRecommend: [topOf(), topOf({ subjectCode: 'SZ000001', subjectName: '平安银行', reason: '事件驱动', rank: 2 })],
          }),
        ),
      );
      await dailyPending;
    });

    const first = await screen.findByTestId('rec-item-1');
    expect(first).toHaveTextContent('SH600519');
    expect(first).toHaveTextContent('贵州茅台');
    expect(screen.getByTestId('rec-item-2')).toHaveTextContent('平安银行');
    // AI 生成成功不显示「规则排序」角标
    expect(screen.queryByTestId('rec-fallback-badge')).toBeNull();
  });

  it('推荐卡·规则兜底：status=2 渲染 Top5 并带「规则排序」角标', async () => {
    vi.stubGlobal(
      'fetch',
      routeFetch({
        feed: () => ok(feedPageOf({ recommendationPending: true })),
        daily: () => ok(dailyViewOf({ status: 2 })),
      }),
    );

    render(<Overview />);
    await screen.findByTestId('rec-pending');
    await userEvent.click(screen.getByTestId('rec-generate'));

    expect(await screen.findByTestId('rec-fallback-badge')).toHaveTextContent('规则排序');
    expect(screen.getByTestId('rec-item-1')).toHaveTextContent('贵州茅台');
  });

  it('推荐卡·空池两路：生成返回 status=3 与只读无推荐条目，均显示「去自选清单」CTA', async () => {
    vi.stubGlobal(
      'fetch',
      routeFetch({
        feed: () => ok(feedPageOf({ recommendationPending: true })),
        daily: () => ok(dailyViewOf({ status: 3, topRecommend: [] })),
      }),
    );

    render(<Overview />);
    await screen.findByTestId('rec-pending');
    await userEvent.click(screen.getByTestId('rec-generate'));

    const empty = await screen.findByTestId('rec-empty-pool');
    expect(empty).toHaveTextContent('自选池为空');
    expect(screen.getByTestId('rec-empty-pool-cta')).toHaveAttribute('href', '#/watchlists');
  });

  it('推荐卡·错误：只读判读失败显示错误，重试恢复', async () => {
    let feedFails = true;
    vi.stubGlobal(
      'fetch',
      routeFetch({
        feed: () => {
          if (feedFails) {
            feedFails = false;
            return fail(500, 50000, '推荐加载失败');
          }
          return ok(feedPageOf({ items: [recFeedItem()] }));
        },
      }),
    );

    render(<Overview />);

    expect(await screen.findByTestId('rec-error')).toHaveTextContent('推荐加载失败');
    // 卡级错误不拖累其余区块
    expect(await screen.findByTestId('stat-card-anomaly')).toHaveTextContent('3 条');

    await userEvent.click(screen.getByTestId('rec-retry'));
    await waitFor(() => expect(screen.getByTestId('rec-item-1')).toBeInTheDocument());
  });

  it('异动警示联动：行情源（QUOTE）最近事件异常 → 异动卡叠加警示条；正常 → 不出现（体检 E2）', async () => {
    vi.stubGlobal(
      'fetch',
      routeFetch({
        overview: () =>
          ok(
            viewOf({
              sourceHealth: [
                { sourceCode: 'QUOTE', mode: 'REAL', lastEventType: 'ERROR', lastEventAt: '2026-09-22T06:30:00Z', errors24h: 12 },
              ],
            }),
          ),
      }),
    );

    render(<Overview />);

    const anomalyCard = await screen.findByTestId('stat-card-anomaly');
    expect(anomalyCard).toHaveTextContent('3 条');
    expect(screen.getByTestId('anomaly-source-warning')).toHaveTextContent('行情源异常，异动监控受限');
  });

  it('异动警示联动·正常态：QUOTE 全绿不出现警示条', async () => {
    vi.stubGlobal('fetch', routeFetch());

    render(<Overview />);

    await screen.findByTestId('stat-card-anomaly');
    expect(screen.queryByTestId('anomaly-source-warning')).toBeNull();
  });
});
