import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FeedDashboard } from '@/pages/FeedDashboard';
import type { FeedDashboardView } from '@/types/feedDashboard';

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
    ...overrides,
  };
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

    expect(window.location.hash).toBe('#/info-sources?source=mw_topstories');
  });

  it('30 秒自动刷新：不可见时暂停、可见时到期即拉取', async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn(async () => ok(fullView()));
    vi.stubGlobal('fetch', fetchMock);
    const hiddenSpy = vi.spyOn(document, 'hidden', 'get');

    render(<FeedDashboard />);
    await vi.advanceTimersByTimeAsync(500);
    expect(fetchMock).toHaveBeenCalledTimes(1);

    // 页面隐藏：定时器到期跳过本轮
    hiddenSpy.mockReturnValue(true);
    await vi.advanceTimersByTimeAsync(31_000);
    expect(fetchMock).toHaveBeenCalledTimes(1);

    hiddenSpy.mockReturnValue(false);
    await vi.advanceTimersByTimeAsync(31_000);
    expect(fetchMock).toHaveBeenCalledTimes(2);
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
    for (let i = 0; i < 20 && fetchMock.mock.calls.length < 2; i++) {
      await vi.advanceTimersByTimeAsync(50);
    }
    expect(fetchMock).toHaveBeenCalledTimes(2);
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
    expect(window.location.hash).toBe('#/info-sources');
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
});
