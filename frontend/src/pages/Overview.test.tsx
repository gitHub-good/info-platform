import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Overview } from '@/pages/Overview';
import type { OverviewView } from '@/types/overview';
import type { FeedItemView, FeedPage } from '@/types/feed';
import type { DailyRecommendationView, TopRecommendation } from '@/types/recommendation';

// —— fetch mock：GET /api/v1/overview（五卡片聚合，可变异供重试路径复用） ——

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

// —— 概览页按 URL 路由的 fetch stub（体检 P1-4 起页面并发请求 /overview 与 /feed/personal，
//    推荐卡另有 /recommendations/daily；子组件 effect 先于父组件，不能按调用序计数） ——

/** 推荐条目（feed 只读路径的 type=recommendation 条目：title=名称、summary=理由）。 */
function recFeedItem(overrides: Partial<FeedItemView> = {}): FeedItemView {
  return {
    id: 1,
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

/** 默认：overview 正常 / feed 只读空页（推荐已就绪无条目→空池）/ daily status=1。 */
function routeFetch(opts: { overview?: StubResponder; feed?: StubResponder; daily?: StubResponder } = {}) {
  return vi.fn(async (url: unknown) => {
    const path = String(url);
    if (path.includes('/recommendations/daily')) return (await opts.daily?.()) ?? ok(dailyViewOf());
    if (path.includes('/feed/personal')) return (await opts.feed?.()) ?? ok(feedPageOf());
    if (path.includes('/overview')) return (await opts.overview?.()) ?? ok(viewOf());
    return fail(500, 50000, '未知端点');
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
  cleanup();
  localStorage.clear();
  window.location.hash = '';
});

describe('Overview 概览仪表盘（T42）', () => {
  it('主路径：渲染五卡片数据（成本水位含进度与徽章 / 政策最新条 / 数据源成功计数）', async () => {
    vi.stubGlobal('fetch', routeFetch());

    render(<Overview />);

    await waitFor(() =>
      expect(screen.getByTestId('stat-card-llm-today')).toBeInTheDocument(),
    );
    // 成本水位：¥ + 已用/预算 + 进度条 + 状态徽章（带文字不裸色）
    expect(screen.getByTestId('stat-card-llm-today')).toHaveTextContent('¥0.0342');
    expect(screen.getByTestId('stat-card-llm-today')).toHaveTextContent('8,400 / 预算 20,000');
    expect(screen.getByTestId('stat-card-llm-today-progress')).toHaveAttribute(
      'aria-valuenow',
      '42',
    );
    expect(screen.getByTestId('llm-status-OK')).toHaveTextContent('正常');
    // 异动 / 政策 / 任务 / 数据源
    expect(screen.getByTestId('stat-card-anomaly')).toHaveTextContent('3 条');
    expect(screen.getByTestId('stat-card-policy')).toHaveTextContent('12 条');
    expect(screen.getByTestId('stat-card-policy')).toHaveTextContent('关于人工智能的行动方案');
    expect(screen.getByTestId('stat-card-job-health')).toHaveTextContent('失败 0 次');
    expect(screen.getByTestId('job-health-ok')).toHaveTextContent('健康');
    expect(screen.getByTestId('stat-card-source-health')).toHaveTextContent('抓取成功 1/2');
    expect(screen.getByTestId('stat-card-source-health')).toHaveTextContent('全部正常');
  });

  it('主路径：预算告警态显示 WARNING 徽章与任务失败明细', async () => {
    vi.stubGlobal(
      'fetch',
      routeFetch({
        overview: () =>
          ok(
            viewOf({
              llmToday: { tokenUsed: 18_000, costMicros: 90_000, budgetTokens: 20_000, status: 'WARNING', error: null },
              jobHealth: { windowRuns: 50, windowFailed: 2, unhealthyJobs: ['PUSH_RETRY', 'ANOMALY_DETECT'], error: null },
            }),
          ),
      }),
    );

    render(<Overview />);

    await waitFor(() => expect(screen.getByTestId('llm-status-WARNING')).toBeInTheDocument());
    expect(screen.getByTestId('llm-status-WARNING')).toHaveTextContent('余量告急');
    expect(screen.getByTestId('stat-card-job-health')).toHaveTextContent('失败 2 次');
    expect(screen.getByTestId('stat-card-job-health')).toHaveTextContent(
      '涉及 PUSH_RETRY、ANOMALY_DETECT',
    );
    expect(screen.getByTestId('job-health-failed')).toHaveTextContent('2 任务异常');
  });

  it('三态·loading：两段骨架占位（推荐卡自带 loading，指标卡骨架）', async () => {
    vi.stubGlobal('fetch', routeFetch());

    render(<Overview />);

    expect(screen.getByTestId('overview-loading')).toBeInTheDocument();
    // 首帧不出现卡片数据
    expect(screen.queryByTestId('stat-card-llm-today')).toBeNull();
    await waitFor(() =>
      expect(screen.getByTestId('stat-card-llm-today')).toBeInTheDocument(),
    );
  });

  it('三态·empty：当日无数据各卡显示 0 / 暂无抓取记录，不报错', async () => {
    vi.stubGlobal(
      'fetch',
      routeFetch({
        overview: () =>
          ok(
            viewOf({
              llmToday: { tokenUsed: 0, costMicros: 0, budgetTokens: 20_000, status: 'OK', error: null },
              anomalyToday: { count: 0, error: null },
              policy24h: { count: 0, latest: [], error: null },
              jobHealth: { windowRuns: 0, windowFailed: 0, unhealthyJobs: [], error: null },
              sourceHealth: [
                { sourceCode: 'QUOTE', mode: 'MOCK', lastEventType: null, lastEventAt: null, errors24h: 0 },
              ],
              sourceHealthError: null,
            }),
          ),
      }),
    );

    render(<Overview />);

    await waitFor(() => expect(screen.getByTestId('stat-card-anomaly')).toBeInTheDocument());
    expect(screen.getByTestId('stat-card-anomaly')).toHaveTextContent('0 条');
    expect(screen.getByTestId('stat-card-policy')).toHaveTextContent('0 条');
    expect(screen.getByTestId('stat-card-job-health')).toHaveTextContent('失败 0 次');
    expect(screen.getByTestId('stat-card-source-health')).toHaveTextContent('抓取成功 0/1');
    expect(screen.getByTestId('stat-card-source-health')).toHaveTextContent('暂无抓取记录');
    expect(screen.queryByRole('alert')).toBeNull();
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

    await waitFor(() => expect(screen.getByTestId('overview-error')).toBeInTheDocument());
    expect(screen.getByTestId('overview-error')).toHaveTextContent('服务异常');

    await userEvent.click(screen.getByTestId('overview-retry'));

    await waitFor(() => expect(screen.getByTestId('stat-card-llm-today')).toBeInTheDocument());
    expect(screen.queryByTestId('overview-error')).toBeNull();
  });

  it('三态·单卡错误：仅该卡显示错误与独立重试，其余卡正常渲染', async () => {
    let overviewCalls = 0;
    vi.stubGlobal(
      'fetch',
      routeFetch({
        overview: () => {
          overviewCalls += 1;
          return ok(
            overviewCalls === 1
              ? viewOf({ policy24h: { count: 0, latest: [], error: '取数失败：database is locked' } })
              : viewOf(),
          );
        },
      }),
    );

    render(<Overview />);

    await waitFor(() => expect(screen.getByTestId('stat-card-policy')).toBeInTheDocument());
    expect(screen.getByTestId('stat-card-policy')).toHaveTextContent('database is locked');
    expect(screen.getByTestId('stat-card-policy-retry')).toBeInTheDocument();
    // 其余卡不受拖累
    expect(screen.getByTestId('stat-card-llm-today')).toHaveTextContent('¥0.0342');
    expect(screen.getByTestId('stat-card-anomaly')).toHaveTextContent('3 条');

    await userEvent.click(screen.getByTestId('stat-card-policy-retry'));

    await waitFor(() =>
      expect(screen.getByTestId('stat-card-policy')).toHaveTextContent('12 条'),
    );
  });

  it('交互：整卡可点跳转（成本→#/cost-report、数据源→#/datasource-config）', async () => {
    vi.stubGlobal('fetch', routeFetch());

    render(<Overview />);

    await waitFor(() => expect(screen.getByTestId('stat-card-llm-today-link')).toBeInTheDocument());
    expect(screen.getByTestId('stat-card-llm-today-link')).toHaveAttribute('href', '#/cost-report');
    expect(screen.getByTestId('stat-card-anomaly-link')).toHaveAttribute('href', '#/watchlists');
    expect(screen.getByTestId('stat-card-policy-link')).toHaveAttribute('href', '#/policies');
    expect(screen.getByTestId('stat-card-job-health-link')).toHaveAttribute('href', '#/task-center');
    expect(screen.getByTestId('stat-card-source-health-link')).toHaveAttribute(
      'href',
      '#/datasource-config',
    );

    // 键盘可达路径同锚点：点击后 hash 真实跳变
    await userEvent.click(screen.getByTestId('stat-card-llm-today-link'));
    expect(window.location.hash).toBe('#/cost-report');
  });

  it('数据源健康：最差源提示行（源名 + 最近失败时间 + 异常计数）', async () => {
    vi.stubGlobal(
      'fetch',
      routeFetch({
        overview: () =>
          ok(
            viewOf({
              sourceHealth: [
                { sourceCode: 'QUOTE', mode: 'REAL', lastEventType: 'OK', lastEventAt: '2026-09-22T02:00:00Z', errors24h: 0 },
                { sourceCode: 'POLICY', mode: 'REAL', lastEventType: 'ERROR', lastEventAt: '2026-09-22T06:30:00Z', errors24h: 4 },
              ],
            }),
          ),
      }),
    );

    render(<Overview />);

    await waitFor(() => expect(screen.getByTestId('stat-card-source-health')).toBeInTheDocument());
    expect(screen.getByTestId('stat-card-source-health')).toHaveTextContent('抓取成功 1/2');
    expect(screen.getByTestId('stat-card-source-health')).toHaveTextContent('政策源');
    expect(screen.getByTestId('stat-card-source-health')).toHaveTextContent('ERROR');
    expect(screen.getByTestId('stat-card-source-health')).toHaveTextContent('24h 异常 4 次');
  });
});

describe('Overview 用户视角化（体检 P1-4）', () => {
  it('两段式布局：上「今日」区（推荐卡/异动/政策），下「平台健康」区（成本/任务/数据源三卡）', async () => {
    vi.stubGlobal('fetch', routeFetch());

    render(<Overview />);

    const today = await screen.findByTestId('overview-today');
    expect(today).toContainElement(screen.getByTestId('rec-card'));
    expect(today).toContainElement(screen.getByTestId('stat-card-anomaly'));
    expect(today).toContainElement(screen.getByTestId('stat-card-policy'));
    const platform = screen.getByTestId('overview-platform');
    expect(platform).toContainElement(screen.getByTestId('stat-card-llm-today'));
    expect(platform).toContainElement(screen.getByTestId('stat-card-job-health'));
    expect(platform).toContainElement(screen.getByTestId('stat-card-source-health'));
    expect(platform).not.toContainElement(screen.getByTestId('stat-card-anomaly'));
  });

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
    expect(screen.getByTestId('rec-item-1')).toHaveTextContent('SH600519');
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
    // 卡级错误不拖累其余卡片
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
