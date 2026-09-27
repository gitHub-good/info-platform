import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FeedDashboard } from '@/pages/FeedDashboard';
import type { FeedDashboardView } from '@/types/feedDashboard';
import type { NorthStarView } from '@/types/northStar';

// —— fetch mock：GET /feed-dashboard 三区块视图（对齐后端 FeedDashboardView 契约） ——

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

function rowOf(overrides: Partial<FeedDashboardView['sources'][number]> = {}) {
  return {
    sourceId: 1,
    sourceCode: 'jin10_flash',
    name: '金十数据·快讯',
    category: '快讯',
    adapterType: 'json_api' as const,
    intervalMinutes: 5,
    enabled: true,
    preset: true,
    deleted: false,
    todayPollCount: 120,
    todayNewCount: 45,
    todayFailCount: 0,
    todayDupCount: 6,
    totalCount: 1200,
    lastAttemptAt: '2026-09-22T07:58:00Z',
    lastSuccessAt: '2026-09-22T07:58:00Z',
    nextDueAt: '2026-09-22T08:03:00Z',
    backoffUntil: null,
    consecutiveFailures: 0,
    lastError: null,
    lastRoundDetail: 'new=2; dup=0; pages=1; backfill=none',
    runState: 'ok' as const,
    abnormal: false,
    staleSince: null,
    ...overrides,
  };
}

/** 北极星六指标视图（ns-v1 全达标形态；对齐后端 NorthStarView 契约）。 */
function northStarView(overrides: Partial<NorthStarView> = {}): NorthStarView {
  return {
    basis: 'ns-v1',
    generatedAt: '2026-09-22T08:00:00Z',
    latency: {
      p50Millis: 180000,
      p90Millis: 540000,
      sampleCount: 220,
      status: 'MET',
      basis: 'incremental-only-v1:exclude-first-day+daily-sources',
    },
    coverage: {
      coverageRatio: 28 / 31,
      hitIndustries: 28,
      totalIndustries: 31,
      classifiedToday: 500,
      status: 'MET',
    },
    stableSources: { stableCount: 30, enabledCount: 31, windowDays: 7, status: 'MET' },
    dailyIntake: { todayNew: 2100, avg7d: 2050.4, status: 'MET' },
    adoptRate: { adoptRate: 0.36, exposure: 50, adopted: 18, status: 'MET', basis: 'adopt-v1' },
    costGuard: {
      usageRatio: 0.385,
      todayCostMicros: 1_000_000,
      budgetMicros: 2_600_000,
      perItemMicros: 476.2,
      costBasis: 'cost-v2:m18-30src',
      status: 'MET',
    },
    intakeTrend: Array.from({ length: 7 }, (_, i) => ({
      date: `2026-09-1${6 + i}`,
      count: 2000 + i * 10,
    })),
    ...overrides,
  };
}

/** URL 路由 fetch 桩：/north-star → 北极星视图，其余 → 大盘三区块视图。 */
function routedMock(
  northStar: unknown = northStarView(),
  dashboard: unknown = fullView(),
): ReturnType<typeof vi.fn> {
  return vi.fn(async (input: RequestInfo | URL) =>
    ok(String(input).includes('north-star') ? northStar : dashboard),
  );
}

function fullView(): FeedDashboardView {
  return {
    global: {
      todayNewCount: 48,
      todayDupCount: 9,
      activeSourceCount: 12,
      failedSourceCount: 1,
      latency: {
        p50Millis: 180000,
        p90Millis: 540000,
        sampleCount: 220,
        basis: 'incremental-only-v1:exclude-first-day+daily-sources',
        excludedSourceCodes: ['ndrc_policy', 'csrc_news', 'stats_release', 'em_macro_indicators'],
      },
    },
    sources: [
      rowOf({
        sourceId: 2,
        sourceCode: 'mw_topstories',
        name: 'MarketWatch·头条',
        category: '国际',
        adapterType: 'rss',
        intervalMinutes: 30,
        todayPollCount: 19,
        todayNewCount: 3,
        todayFailCount: 2,
        totalCount: 640,
        lastAttemptAt: '2026-09-22T07:59:00Z',
        lastSuccessAt: '2026-09-22T06:00:00Z',
        consecutiveFailures: 2,
        backoffUntil: '2026-09-22T08:29:00Z',
        lastError: 'feeds.content.dowjones.io 连接超时',
        runState: 'backoff',
        abnormal: true,
      }),
      rowOf(),
    ],
    failures: [
      {
        sourceCode: 'mw_topstories',
        sourceName: 'MarketWatch·头条',
        occurredAt: '2026-09-22T07:59:00Z',
        errorSummary: 'consecutiveFailures=2; feeds.content.dowjones.io 连接超时',
        origin: 'event',
      },
    ],
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  vi.useRealTimers();
  cleanup();
  localStorage.clear();
  window.location.hash = '';
});

describe('FeedDashboard 抓取大盘页（T116）', () => {
  it('渲染全局统计卡四指标与感知延迟 P50/P90 徽章（含样本量与仅增量轮口径标注）', async () => {
    const fetchMock = vi.fn(async () => ok(fullView()));
    vi.stubGlobal('fetch', fetchMock);

    render(<FeedDashboard />);

    expect(await screen.findByTestId('dashboard-stat-today-new')).toHaveTextContent('48');
    expect(screen.getByTestId('dashboard-stat-today-dup')).toHaveTextContent('9');
    expect(screen.getByTestId('dashboard-stat-active')).toHaveTextContent('12');
    expect(screen.getByTestId('dashboard-stat-failed')).toHaveTextContent('1');
    expect(screen.getByTestId('dashboard-latency-p50')).toHaveTextContent('3 分钟');
    expect(screen.getByTestId('dashboard-latency-p90')).toHaveTextContent('9 分钟');
    expect(screen.getByTestId('dashboard-latency-sample')).toHaveTextContent('220');
    expect(screen.getByTestId('dashboard-latency-basis')).toHaveTextContent('仅增量轮');
  });

  it('感知延迟无样本时显示占位不显示 0', async () => {
    const view = fullView();
    view.global.latency.p50Millis = null;
    view.global.latency.p90Millis = null;
    view.global.latency.sampleCount = 0;
    vi.stubGlobal('fetch', vi.fn(async () => ok(view)));

    render(<FeedDashboard />);

    expect(await screen.findByTestId('dashboard-latency-p50')).toHaveTextContent('—');
    expect(screen.getByTestId('dashboard-latency-p90')).toHaveTextContent('—');
  });

  it('源维度表：异常态置顶、状态徽章五态语义、今日新增/累计列齐备', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ok(fullView())));

    render(<FeedDashboard />);

    const table = await screen.findByTestId('dashboard-source-table');
    const rows = within(table).getAllByTestId(/^dashboard-source-row-/);
    expect(rows[0]).toHaveAttribute('data-code', 'mw_topstories'); // 异常（退避中）置顶
    expect(within(rows[0]).getByTestId('dashboard-source-status-mw_topstories')).toHaveTextContent(
      '退避中',
    );
    expect(within(rows[1]).getByTestId('dashboard-source-status-jin10_flash')).toHaveTextContent(
      '正常',
    );
    expect(within(rows[1]).getByTestId('dashboard-source-today-jin10_flash')).toHaveTextContent(
      '45',
    );
    expect(within(rows[1]).getByTestId('dashboard-source-total-jin10_flash')).toHaveTextContent(
      '1200',
    );
    expect(within(rows[0]).getByTestId('dashboard-source-error-mw_topstories')).toHaveTextContent(
      '连接超时',
    );
  });

  it('失败列表：点击条目跳源管理页并带源定位参数', async () => {
    const user = userEvent.setup();
    vi.stubGlobal('fetch', vi.fn(async () => ok(fullView())));

    render(<FeedDashboard />);

    const failure = await screen.findByTestId('dashboard-failure-0');
    await user.click(within(failure).getByTestId('dashboard-failure-jump-0'));

    expect(window.location.hash).toBe('#/sources?source=mw_topstories');
  });

  it('30 秒自动刷新：不可见时暂停、可见时到期即拉取（大盘 + 北极星双请求同节奏）', async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn(async () => ok(fullView()));
    vi.stubGlobal('fetch', fetchMock);
    const hiddenSpy = vi.spyOn(document, 'hidden', 'get');

    render(<FeedDashboard />);
    await vi.advanceTimersByTimeAsync(500);
    expect(fetchMock).toHaveBeenCalledTimes(2); // 大盘 + 北极星各一

    // 页面隐藏：定时器到期跳过本轮
    hiddenSpy.mockReturnValue(true);
    await vi.advanceTimersByTimeAsync(31_000);
    expect(fetchMock).toHaveBeenCalledTimes(2);

    hiddenSpy.mockReturnValue(false);
    await vi.advanceTimersByTimeAsync(31_000);
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });

  it('手动刷新按钮触发拉取并展示「更新于 N 秒前」', async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn(async () => ok(fullView()));
    vi.stubGlobal('fetch', fetchMock);

    render(<FeedDashboard />);
    // 等待首份数据落地（「尚未刷新」→「更新于 N 秒前」）
    for (
      let i = 0;
      i < 20 && !screen.getByTestId('dashboard-last-refresh').textContent?.includes('更新于');
      i++
    ) {
      await vi.advanceTimersByTimeAsync(50);
    }
    expect(screen.getByTestId('dashboard-last-refresh')).toHaveTextContent('更新于 0 秒前');

    // fake timers 下用 fireEvent 同步派发点击（userEvent 依赖真实定时器）
    fireEvent.click(screen.getByTestId('dashboard-refresh'));
    for (let i = 0; i < 20 && fetchMock.mock.calls.length < 4; i++) {
      await vi.advanceTimersByTimeAsync(50);
    }
    expect(fetchMock).toHaveBeenCalledTimes(4); // 大盘 + 北极星双请求随手动刷新
    expect(screen.getByTestId('dashboard-last-refresh')).toHaveTextContent('更新于 0 秒前');

    await vi.advanceTimersByTimeAsync(5_000);
    // 秒级计时推进：文案离开 0 秒（刷新循环耗时不定，断言非零秒而非精确值）
    expect(screen.getByTestId('dashboard-last-refresh').textContent).toMatch(/更新于 [1-9]\d* 秒前/);
  });

  it('加载中骨架与错误态重试（三态）', async () => {
    let shouldFail = true;
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => (shouldFail ? fail(500, 50000, '服务异常') : ok(fullView()))),
    );
    const user = userEvent.setup();

    const { rerender } = render(<FeedDashboard />);
    expect(screen.getByTestId('dashboard-loading')).toBeInTheDocument();

    expect(await screen.findByTestId('dashboard-error')).toBeInTheDocument();
    shouldFail = false;
    await user.click(screen.getByTestId('dashboard-retry'));

    expect(await screen.findByTestId('dashboard-stat-today-new')).toHaveTextContent('48');
    void rerender;
  });

  it('空态：无源时显示引导去源管理页（不白屏不报错）', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ok({ global: fullView().global, sources: [], failures: [] })),
    );
    const user = userEvent.setup();

    render(<FeedDashboard />);

    const empty = await screen.findByTestId('dashboard-empty');
    expect(empty).toBeInTheDocument();
    await user.click(screen.getByTestId('dashboard-empty-link'));
    expect(window.location.hash).toBe('#/sources');
  });

  it('归档源默认折叠，勾选后可见', async () => {
    const view = fullView();
    view.sources.push(
      rowOf({
        sourceId: 9,
        sourceCode: 't116_gone',
        name: '已归档源',
        deleted: true,
        enabled: false,
        runState: 'disabled',
        todayNewCount: 0,
        totalCount: 10,
      }),
    );
    vi.stubGlobal('fetch', vi.fn(async () => ok(view)));
    const user = userEvent.setup();

    render(<FeedDashboard />);

    expect(await screen.findByTestId('dashboard-source-row-jin10_flash')).toBeInTheDocument();
    expect(screen.queryByTestId('dashboard-source-row-t116_gone')).toBeNull();

    await user.click(screen.getByTestId('dashboard-show-archived'));
    expect(screen.getByTestId('dashboard-source-row-t116_gone')).toBeInTheDocument();
  });

  it('失败列表为空时展示空占位', async () => {
    const view = fullView();
    view.failures = [];
    vi.stubGlobal('fetch', vi.fn(async () => ok(view)));

    render(<FeedDashboard />);

    expect(await screen.findByTestId('dashboard-failures-empty')).toBeInTheDocument();
  });

  it('对账一致性呈现：全局今日入库等于源表行求和（契约自检）', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ok(fullView())));

    render(<FeedDashboard />);

    await screen.findByTestId('dashboard-source-table');
    const todayNew = Number(
      (await screen.findByTestId('dashboard-stat-today-new')).getAttribute('data-value'),
    );
    const rowSum = screen
      .getAllByTestId(/^dashboard-source-today-/)
      .reduce((sum, el) => sum + Number(el.textContent), 0);
    expect(todayNew).toBe(rowSum);
  });

  it('接口失败后自动刷新恢复数据（错误态不粘死）', async () => {
    vi.useFakeTimers();
    let shouldFail = true;
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => (shouldFail ? fail(500, 50000, '服务异常') : ok(fullView()))),
    );

    render(<FeedDashboard />);
    for (let i = 0; i < 20 && screen.queryByTestId('dashboard-error') === null; i++) {
      await vi.advanceTimersByTimeAsync(50);
    }
    expect(screen.getByTestId('dashboard-error')).toBeInTheDocument();

    shouldFail = false;
    await vi.advanceTimersByTimeAsync(31_000);

    expect(screen.getByTestId('dashboard-stat-today-new')).toHaveTextContent('48');
  });

  it('M15 T128 增量：源维度行 staleSince 非空显示「疑似停更」徽章（含起始日），null 不渲染', async () => {
    const view = fullView();
    view.sources[1] = rowOf({ staleSince: '2026-09-15' });
    vi.stubGlobal('fetch', vi.fn(async () => ok(view)));

    render(<FeedDashboard />);

    await screen.findByTestId('dashboard-source-row-jin10_flash');
    const badge = screen.getByTestId('dashboard-source-stale-jin10_flash');
    expect(badge).toHaveTextContent('疑似停更');
    expect(badge).toHaveTextContent('2026-09-15');
    expect(screen.queryByTestId('dashboard-source-stale-mw_topstories')).toBeNull();
  });

  // —— M18 T158/T155 增量：北极星区块 + >30 源形态 ——

  it('M18 T158 北极星区块：六指标卡 + ns-v1 口径徽章 + 达标徽章 + 7 天迷你趋势', async () => {
    vi.stubGlobal('fetch', routedMock());

    render(<FeedDashboard />);

    expect(await screen.findByTestId('north-star-section')).toBeInTheDocument();
    expect(screen.getByTestId('north-star-basis')).toHaveTextContent('ns-v1');
    expect(screen.getByTestId('north-star-latency')).toHaveTextContent('3 分钟');
    expect(screen.getByTestId('north-star-latency')).toHaveTextContent('P90 9 分钟');
    expect(screen.getByTestId('north-star-coverage')).toHaveTextContent('90.3%');
    expect(screen.getByTestId('north-star-coverage')).toHaveTextContent('31 行业命中 28/31');
    expect(screen.getByTestId('north-star-stable')).toHaveTextContent('30');
    expect(screen.getByTestId('north-star-stable')).toHaveTextContent('现役启用 31');
    expect(screen.getByTestId('north-star-intake')).toHaveTextContent('2050');
    expect(screen.getByTestId('north-star-adopt')).toHaveTextContent('36.0%');
    expect(screen.getByTestId('north-star-adopt')).toHaveTextContent('曝光 50');
    expect(screen.getByTestId('north-star-cost')).toHaveTextContent('38.5%');
    expect(screen.getByTestId('north-star-cost')).toHaveTextContent('¥0.0005');
    expect(screen.getByTestId('north-star-cost')).toHaveTextContent('cost-v2:m18-30src');
    expect(screen.getByTestId('north-star-latency-status')).toHaveTextContent('达标');
    expect(screen.getAllByTestId(/^north-star-trend-/)).toHaveLength(7);
  });

  it('北极星样本不足走校准条款徽章（INSUFFICIENT 不误报达标）', async () => {
    const ns = northStarView();
    ns.latency = {
      ...ns.latency,
      p50Millis: null,
      p90Millis: null,
      sampleCount: 0,
      status: 'INSUFFICIENT',
    };
    ns.adoptRate = {
      ...ns.adoptRate,
      adoptRate: null,
      exposure: 0,
      adopted: 0,
      status: 'INSUFFICIENT',
    };
    vi.stubGlobal('fetch', routedMock(ns));

    render(<FeedDashboard />);

    expect(await screen.findByTestId('north-star-latency')).toHaveTextContent('—');
    expect(screen.getByTestId('north-star-latency-status')).toHaveTextContent('样本不足');
    expect(screen.getByTestId('north-star-adopt')).toHaveTextContent('—');
    expect(screen.getByTestId('north-star-adopt-status')).toHaveTextContent('样本不足');
  });

  it('北极星加载失败单区块降级：大盘三区块照常、区块内可重试', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) =>
      String(input).includes('north-star') ? fail(500, 50000, '服务异常') : ok(fullView()));
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();

    render(<FeedDashboard />);

    expect(await screen.findByTestId('north-star-error')).toBeInTheDocument();
    expect(await screen.findByTestId('dashboard-stat-today-new')).toHaveTextContent('48');
    await user.click(screen.getByTestId('north-star-retry'));
    expect(
      fetchMock.mock.calls.filter((call) => String(call[0]).includes('north-star')),
    ).toHaveLength(2);
  });

  it('>30 源形态：行数超阈值启用纵向滚动容器，31 行全部渲染不退化', async () => {
    const view = fullView();
    view.sources = Array.from({ length: 31 }, (_, i) =>
      rowOf({ sourceId: i + 1, sourceCode: `src_${i + 1}`, name: `源${i + 1}` }),
    );
    vi.stubGlobal('fetch', routedMock(undefined, view));

    render(<FeedDashboard />);

    expect(await screen.findByTestId('dashboard-source-scroll')).toBeInTheDocument();
    expect(screen.getAllByTestId(/^dashboard-source-row-/)).toHaveLength(31);
  });

  it('源表行数低于阈值不启用滚动容器（>30 形态不误开）', async () => {
    vi.stubGlobal('fetch', routedMock());

    render(<FeedDashboard />);

    await screen.findByTestId('dashboard-source-table');
    expect(screen.queryByTestId('dashboard-source-scroll')).toBeNull();
  });
});

// —— V2.4 T214（REQ-20260928-20 拍板三）：三处数字可点弹框 + 对账断言 ——

import { shanghaiToday } from '@/pages/FeedDashboard';
import type { NewsLibraryItem, NewsLibraryPagedView } from '@/types/newsItem';

function newsItemOf(id: number): NewsLibraryItem {
  return {
    id,
    sourceId: 1,
    sourceCode: 'jin10_flash',
    sourceName: '金十数据·快讯',
    title: `大盘下钻条目 ${id}`,
    summary: null,
    url: 'https://example.com/n',
    author: null,
    publishedAt: '2026-09-22T01:31:00Z',
    fetchedAt: '2026-09-22T01:31:30Z',
    l0Result: 'PASS',
    l0Detail: null,
    l1Main: '银行',
    l1Confidence: 0.9,
    lowConfidence: false,
    nearDupMasterId: null,
    nearDupMasterUrl: null,
  };
}

/** /news-items 页码视图（total 可注入——对账断言的被点数字对齐值）。 */
function newsPaged(total: number, count = 2): NewsLibraryPagedView {
  return {
    items: Array.from({ length: count }, (_, i) => newsItemOf(i + 1)),
    total,
    page: 1,
    size: 20,
  };
}

/** 三路由桩：north-star / news-items（total 对齐被点数字）/ 其余大盘视图。 */
function dialogMock(newsTotal: number, onPage2?: () => NewsLibraryPagedView) {
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('north-star')) return ok(northStarView());
    if (url.includes('/news-items')) {
      if (onPage2 && url.includes('page=2')) return ok(onPage2());
      return ok(newsPaged(newsTotal));
    }
    return ok(fullView());
  });
}

function newsItemsCalls(fetchMock: ReturnType<typeof vi.fn>): string[] {
  return fetchMock.mock.calls.map(String).filter((u) => u.includes('/news-items'));
}

describe('FeedDashboard 大盘三处数字弹框（V2.4 T214，REQ-20260928-20 拍板三）', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    vi.useRealTimers();
    cleanup();
    localStorage.clear();
    window.location.hash = '';
  });

  it('对账① 全局「今日总入库」可点：弹框 l0=ALL + 入库日=今日 + 全部源，标题 total == 被点数字', async () => {
    const fetchMock = dialogMock(48); // 48 = fullView().global.todayNewCount（对齐）
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    render(<FeedDashboard />);

    const card = await screen.findByTestId('dashboard-stat-today-new');
    const clicked = Number(card.getAttribute('data-value'));
    await user.click(screen.getByTestId('dashboard-stat-today-new-value'));

    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('今日入库 · 全部源');
    expect(dialog).toHaveTextContent(`共 ${clicked} 条`);
    // 请求口径：l0=ALL + fetchedFrom/To=今日（上海日）+ page=1，无 sourceId
    const call = newsItemsCalls(fetchMock).at(-1) ?? '';
    expect(call).toContain('l0=ALL');
    expect(call).toContain(`fetchedFrom=${shanghaiToday()}`);
    expect(call).toContain(`fetchedTo=${shanghaiToday()}`);
    expect(call).toContain('page=1');
    expect(call).not.toContain('sourceId=');
  });

  it('对账② 源行「今日新增」可点：sourceId + 入库日=今日 + l0=ALL，标题 total == 行数字', async () => {
    const fetchMock = dialogMock(3); // 3 = mw_topstories 行今日新增
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    render(<FeedDashboard />);

    await screen.findByTestId('dashboard-source-row-mw_topstories');
    const clicked = Number(
      screen.getByTestId('dashboard-source-today-mw_topstories').textContent,
    );
    await user.click(screen.getByTestId('dashboard-source-today-mw_topstories'));

    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('今日入库 · MarketWatch·头条');
    expect(dialog).toHaveTextContent(`共 ${clicked} 条`);
    const call = newsItemsCalls(fetchMock).at(-1) ?? '';
    expect(call).toContain('sourceId=2');
    expect(call).toContain('l0=ALL');
    expect(call).toContain(`fetchedFrom=${shanghaiToday()}`);
    expect(call).not.toContain('publishedFrom=');
  });

  it('对账③ 源行「累计条数」可点：sourceId + l0=ALL 无时间窗，标题 total == 行数字', async () => {
    const fetchMock = dialogMock(1200); // 1200 = jin10_flash 行累计
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    render(<FeedDashboard />);

    await screen.findByTestId('dashboard-source-row-jin10_flash');
    const clicked = Number(
      screen.getByTestId('dashboard-source-total-jin10_flash').textContent,
    );
    await user.click(screen.getByTestId('dashboard-source-total-jin10_flash'));

    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('累计条目 · 金十数据·快讯');
    expect(dialog).toHaveTextContent(`共 ${clicked} 条`);
    const call = newsItemsCalls(fetchMock).at(-1) ?? '';
    expect(call).toContain('sourceId=1');
    expect(call).toContain('l0=ALL');
    expect(call).not.toContain('fetchedFrom=');
    expect(call).not.toContain('fetchedTo=');
  });

  it('不可点面防蔓延：去重拦截/活跃源数/失败源数/感知延迟 hover title 明示不支持下钻', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ok(fullView())));
    const user = userEvent.setup();
    render(<FeedDashboard />);

    await screen.findByTestId('dashboard-stat-today-new');
    expect(screen.getByTestId('dashboard-stat-today-dup')).toHaveAttribute(
      'title',
      '去重拦截为未入库条目，无可列实体，不支持点击下钻',
    );
    expect(screen.getByTestId('dashboard-stat-active')).toHaveAttribute(
      'title',
      '活跃源数非条目集合（源表运行态统计），不支持点击下钻',
    );
    expect(screen.getByTestId('dashboard-stat-failed')).toHaveAttribute(
      'title',
      '失败源数非条目集合（源表运行态统计），不支持点击下钻',
    );
    expect(screen.getByTestId('dashboard-stat-latency')).toHaveAttribute(
      'title',
      '感知延迟非条目集合（入库-发布延迟分布），不支持点击下钻',
    );
    // 活跃源数不可点：数字为纯文本（无按钮包裹）
    expect(screen.getByTestId('dashboard-stat-active').textContent).toContain('12');
  });

  it('弹框分页：翻页请求 page=2、条数切换请求 size=10&page=1（页码模式）', async () => {
    const fetchMock = dialogMock(45, () => newsPaged(45, 2));
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    render(<FeedDashboard />);

    await screen.findByTestId('dashboard-source-row-jin10_flash');
    await user.click(screen.getByTestId('dashboard-source-total-jin10_flash'));
    await screen.findByTestId('dashboard-items-item-1');
    expect(screen.getByTestId('dashboard-items-pagination-total')).toHaveTextContent('共 45 条');

    await user.click(screen.getByTestId('dashboard-items-pagination-page-2'));
    await waitFor(() =>
      expect(newsItemsCalls(fetchMock).at(-1)).toContain('page=2'),
    );

    await user.selectOptions(
      screen.getByTestId('dashboard-items-pagination-size'),
      '10',
    );
    await waitFor(() => {
      const last = newsItemsCalls(fetchMock).at(-1) ?? '';
      expect(last).toContain('size=10');
      expect(last).toContain('page=1');
    });
  });

  it('弹框关闭三通道：Esc / 关闭钮 / 遮罩任一可关，重开状态复位（page=1）', async () => {
    const fetchMock = dialogMock(45);
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    render(<FeedDashboard />);

    await screen.findByTestId('dashboard-source-row-jin10_flash');
    await user.click(screen.getByTestId('dashboard-source-total-jin10_flash'));
    await screen.findByTestId('dashboard-items-item-1');
    await user.click(screen.getByTestId('dashboard-items-pagination-page-2'));
    await waitFor(() =>
      expect(newsItemsCalls(fetchMock).at(-1)).toContain('page=2'),
    );

    // Esc 关闭
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).toBeNull();

    // 重开：page 复位回 1（非上次翻页页码）
    await user.click(screen.getByTestId('dashboard-source-total-jin10_flash'));
    await screen.findByTestId('dashboard-items-item-1');
    expect(newsItemsCalls(fetchMock).at(-1)).toContain('page=1');

    // 关闭钮 + 遮罩（两条通道同语义，一次覆盖）
    await user.click(screen.getByTestId('dialog-close'));
    expect(screen.queryByRole('dialog')).toBeNull();
    await user.click(screen.getByTestId('dashboard-source-today-jin10_flash'));
    await screen.findByTestId('dashboard-items-item-1');
    await user.click(screen.getByTestId('dialog-backdrop'));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('弹框条目行渲染：标题外链 / 源徽章 / 抓取时间 / L0·L1 徽章；空态口径文案', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('north-star')) return ok(northStarView());
      if (url.includes('/news-items')) return ok({ items: [], total: 0, page: 1, size: 20 });
      return ok(fullView());
    });
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    render(<FeedDashboard />);

    // 空态：该口径下暂无条目（total=0 与被点数字对账为 0 的情形）
    await screen.findByTestId('dashboard-stat-today-new');
    await user.click(screen.getByTestId('dashboard-stat-today-new-value'));
    expect(await screen.findByTestId('dashboard-items-empty')).toHaveTextContent(
      '该口径下暂无条目',
    );
    expect(screen.getByTestId('dialog')).toHaveTextContent('共 0 条');
    await user.click(screen.getByTestId('dialog-close'));

    // 条目行字段（切换 total>0 桩；卸载首实例防 testid 重复）
    cleanup();
    const fetchMock2 = dialogMock(2);
    vi.stubGlobal('fetch', fetchMock2);
    render(<FeedDashboard />);
    await screen.findByTestId('dashboard-source-row-jin10_flash');
    await user.click(screen.getByTestId('dashboard-source-total-jin10_flash'));
    const link = await screen.findByTestId('dashboard-items-title-1');
    expect(link).toHaveAttribute('href', 'https://example.com/n');
    expect(screen.getByTestId('dashboard-items-item-1')).toHaveTextContent('金十数据·快讯');
    expect(screen.getByTestId('dashboard-items-item-1')).toHaveTextContent('抓取');
    expect(screen.getByTestId('dashboard-items-item-1')).toHaveTextContent('通过');
  });

  it('弹框错误态 + 重试恢复（不吞错误，不阻塞大盘）', async () => {
    let shouldFail = true;
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('north-star')) return ok(northStarView());
      if (url.includes('/news-items')) {
        return shouldFail
          ? fail(500, 50000, '服务异常')
          : ok(newsPaged(48));
      }
      return ok(fullView());
    });
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    render(<FeedDashboard />);

    await screen.findByTestId('dashboard-stat-today-new');
    await user.click(screen.getByTestId('dashboard-stat-today-new-value'));
    expect(await screen.findByTestId('dashboard-items-error')).toBeInTheDocument();

    shouldFail = false;
    await user.click(screen.getByTestId('dashboard-items-retry'));
    expect(await screen.findByTestId('dashboard-items-item-1')).toBeInTheDocument();
  });

  it('弹框尾「去资讯库深查」带参跳转（sourceId + l0=ALL 预填）', async () => {
    const fetchMock = dialogMock(1200);
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    render(<FeedDashboard />);

    await screen.findByTestId('dashboard-source-row-jin10_flash');
    await user.click(screen.getByTestId('dashboard-source-total-jin10_flash'));
    const deep = await screen.findByTestId('dashboard-items-deep-link');
    expect(deep).toHaveAttribute('href', '#/news-library?sourceId=1&l0=ALL');
  });

  it('弹框独立请求：大盘 30s 自动刷新不重发弹框 news-items 请求', async () => {
    vi.useFakeTimers();
    const fetchMock = dialogMock(48);
    vi.stubGlobal('fetch', fetchMock);
    render(<FeedDashboard />);
    // 等首份数据落地（fake timers：小步推进冲刷微任务，既有测试同款手法；同步查询 + fireEvent）
    for (
      let i = 0;
      i < 40 && !screen.queryByTestId('dashboard-stat-today-new-value');
      i++
    ) {
      await vi.advanceTimersByTimeAsync(50);
    }
    fireEvent.click(screen.getByTestId('dashboard-stat-today-new-value'));
    for (
      let i = 0;
      i < 40 && !screen.queryByTestId('dashboard-items-item-1');
      i++
    ) {
      await vi.advanceTimersByTimeAsync(50);
    }
    const before = newsItemsCalls(fetchMock).length;

    // 大盘 30s 轮询到期：feed-dashboard + north-star 重发，news-items 不变
    await vi.advanceTimersByTimeAsync(31_000);
    expect(newsItemsCalls(fetchMock).length).toBe(before);
    expect(
      fetchMock.mock.calls.filter((call) => String(call[0]).includes('/feed-dashboard')).length,
    ).toBeGreaterThanOrEqual(2);
  });
});

// —— V2.4 T215（REQ-20260928-20 拍板四）：失败列表折叠 + 恢复过滤脚注 ——

function failuresView(count: number, hiddenRecovered?: number): FeedDashboardView {
  const view = fullView();
  view.failures = Array.from({ length: count }, (_, i) => ({
    sourceCode: i === 0 ? 'mw_topstories' : `t215_fail_${i}`,
    sourceName: i === 0 ? 'MarketWatch·头条' : `失败源 ${i}`,
    occurredAt: `2026-09-22T07:5${i % 10}:00Z`,
    errorSummary: `FeedFetchException: 超时 #${i}`,
    origin: i % 2 === 0 ? ('event' as const) : ('state' as const),
  }));
  if (hiddenRecovered != null) view.failuresHiddenRecovered = hiddenRecovered;
  return view;
}

describe('FeedDashboard 失败列表折叠（V2.4 T215，REQ-20260928-20 拍板四）', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    cleanup();
    localStorage.clear();
    window.location.hash = '';
  });

  it('收起态默认最多 3 条：5 条失败仅前 3 渲染，「展开全部 5 条」开关可见', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ok(failuresView(5))));
    render(<FeedDashboard />);

    expect(await screen.findByTestId('dashboard-failure-0')).toBeInTheDocument();
    expect(screen.getByTestId('dashboard-failure-2')).toBeInTheDocument();
    expect(screen.queryByTestId('dashboard-failure-3')).toBeNull();
    expect(screen.queryByTestId('dashboard-failure-4')).toBeNull();
    expect(screen.getByTestId('dashboard-failures-toggle')).toHaveTextContent('展开全部 5 条');
  });

  it('展开全部：5 条全渲染 + 限高滚动容器（沿 >30 源形态先例），再点收起回 3 条', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ok(failuresView(5))));
    const user = userEvent.setup();
    render(<FeedDashboard />);

    await screen.findByTestId('dashboard-failure-0');
    await user.click(screen.getByTestId('dashboard-failures-toggle'));

    expect(screen.getByTestId('dashboard-failure-4')).toBeInTheDocument();
    expect(screen.getByTestId('dashboard-failures-scroll')).toBeInTheDocument();
    expect(screen.getByTestId('dashboard-failures-scroll').className).toContain('max-h');
    expect(screen.getByTestId('dashboard-failures-toggle')).toHaveTextContent('收起');

    await user.click(screen.getByTestId('dashboard-failures-toggle'));
    expect(screen.queryByTestId('dashboard-failure-3')).toBeNull();
    expect(screen.queryByTestId('dashboard-failures-scroll')).toBeNull();
  });

  it('≤3 条不渲染折叠开关（无多余交互件）', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ok(failuresView(3))));
    render(<FeedDashboard />);

    expect(await screen.findByTestId('dashboard-failure-2')).toBeInTheDocument();
    expect(screen.queryByTestId('dashboard-failures-toggle')).toBeNull();
  });

  it('展开态「去处置」跳转语义保留（#/sources?source={code} 定位）', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ok(failuresView(5))));
    const user = userEvent.setup();
    render(<FeedDashboard />);

    await screen.findByTestId('dashboard-failure-0');
    await user.click(screen.getByTestId('dashboard-failures-toggle'));
    await user.click(screen.getByTestId('dashboard-failure-jump-4'));

    expect(window.location.hash).toBe('#/sources?source=t215_fail_4');
  });

  it('恢复过滤脚注：failuresHiddenRecovered>0 呈现「已隐藏 N 条（留痕可查）」', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ok(failuresView(2, 7))));
    render(<FeedDashboard />);

    expect(await screen.findByTestId('dashboard-failures-hidden-recovered')).toHaveTextContent(
      '已隐藏恢复/停用源失败 7 条（留痕可查）',
    );
  });

  it('恢复过滤脚注防御：旧载荷无 failuresHiddenRecovered 字段不渲染脚注', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ok(failuresView(2))));
    render(<FeedDashboard />);

    await screen.findByTestId('dashboard-failure-1');
    expect(screen.queryByTestId('dashboard-failures-hidden-recovered')).toBeNull();
  });
});
