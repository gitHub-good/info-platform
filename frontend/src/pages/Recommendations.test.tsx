import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { RecommendationCardItem, RecommendationCardPageView } from '@/types/recommendation';
import { resetReadingTrackerForTest } from '@/api/readingEvent';

// —— fetch mock：对齐后端 RecommendationCardController 契约（M16 方案 §4.8：
//    GET /recommendations 卡片流 + POST /{id}/feedback + POST /{id}/read + POST /reading-events 埋点） ——

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

function cardOf(overrides: Partial<RecommendationCardItem> = {}): RecommendationCardItem {
  return {
    id: 9,
    eventId: 9009,
    eventType: 'BUYBACK_CHANGE',
    importance: 'HIGH',
    direction: 'BULLISH',
    level: 'P1',
    industries: ['食品饮料'],
    subjects: [
      { code: 'SH600519', name: '贵州茅台', industry: '食品饮料', inWatchlist: false },
      { code: null, name: '未回联公司', industry: null, inWatchlist: false },
    ],
    logicChain: '贵州茅台公告回购计划——该事件直接涉及你关注的标的贵州茅台。',
    summary: '贵州茅台公告回购计划，拟回购金额不超过30亿元',
    figures: [{ label: '回购金额上限', value: '30', unit: '亿元' }],
    quote: '拟回购金额不超过30亿元',
    newsId: 8009,
    newsTitle: '贵州茅台拟回购不超30亿元',
    newsUrl: 'https://example.com/n/8009',
    eventTime: '2026-09-22T07:30:00Z',
    pushStatus: 'PUSHED',
    pushedAt: '2026-09-22T07:31:00Z',
    createdAt: '2026-09-22T07:30:30Z',
    read: false,
    muted: false,
    feedbackAction: null,
    ...overrides,
  };
}

function viewOf(
  items: RecommendationCardItem[],
  total = items.length,
  page = 1,
  size = 20,
): RecommendationCardPageView {
  return { total, items, page, size };
}

/** 相对路径 URL → page/size 参数读取（http 层走相对路径，不能 new URL）。 */
function paramOf(url: string, key: string): string | null {
  const qs = url.split('?')[1] ?? '';
  return new URLSearchParams(qs).get(key);
}

/** 列表端点请求线（曝光埋点 POST 混入 calls 时只看卡片流请求）。 */
function listCallsOf(fetchMock: ReturnType<typeof stubFetch>): string[] {
  return fetchMock.mock.calls.map((call) => String(call[0])).filter((url) => url.includes('/recommendations?'));
}

interface RouteStub {
  path: string;
  respond: (url: string, init?: RequestInit) => ReturnType<typeof ok> | ReturnType<typeof fail>;
}

function stubFetch(routes: RouteStub[]) {
  // 长前缀优先匹配：/recommendations/9/feedback 不被 /recommendations 卡片流路由抢先命中
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

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  resetReadingTrackerForTest();
  cleanup();
  localStorage.clear();
  window.location.hash = '';
});

describe('Recommendations 推荐中心页（T135，#/recommendations 第 18 页）', () => {
  it('默认拉取卡片流：事件头徽章（类型/方向/重要度/层级）/逻辑链突出/标的区 chips（加自选入口）/关键数字/引用外链/时间齐备', async () => {
    const fetchMock = stubFetch([
      { path: '/api/v1/recommendations', respond: () => ok(viewOf([cardOf()])) },
    ]);

    const { Recommendations } = await import('@/pages/Recommendations');
    render(<Recommendations />);

    expect(await screen.findByTestId('rec-card-9')).toBeInTheDocument();
    expect(screen.getByTestId('rec-type-9')).toHaveTextContent('回购');
    expect(screen.getByTestId('rec-direction-9')).toHaveTextContent('利好');
    // T229 抽查：方向徽章走方向轨单点 directionToneClass（利好红）
    expect(screen.getByTestId('rec-direction-9').className).toContain('text-red-500');
    expect(screen.getByTestId('rec-importance-9')).toHaveTextContent('高');
    expect(screen.getByTestId('rec-level-9')).toHaveTextContent('标的直接');
    // 逻辑链突出展示（可解释红线：零新增事实）
    expect(screen.getByTestId('rec-logic-9')).toHaveTextContent('直接涉及你关注的标的贵州茅台');
    // 标的区：可点跳标的详情 + 未自选展示「加自选」；未回联标的仅留名不可点
    expect(screen.getByTestId('rec-subject-SH600519')).toHaveAttribute('href', '#/subjects/SH600519');
    expect(screen.getByTestId('rec-addwatch-9-SH600519')).toBeInTheDocument();
    expect(screen.queryByTestId('rec-addwatch-9-null')).toBeNull();
    // 关键数字 chips + 原文引用 + 外链（新窗口 + rel=noreferrer）
    expect(screen.getByTestId('rec-figures-9')).toHaveTextContent('回购金额上限: 30亿元');
    expect(screen.getByTestId('rec-quote-9')).toHaveTextContent('拟回购金额不超过30亿元');
    const link = screen.getByTestId('rec-link-9');
    expect(link).toHaveAttribute('href', 'https://example.com/n/8009');
    expect(link).toHaveAttribute('rel', 'noreferrer');
    expect(screen.getByTestId('rec-time-9')).toHaveTextContent('2026-09-22');
    expect(screen.getByTestId('rec-total')).toHaveTextContent('1');
    // 互链（M21 T184）：页顶「全市场视角 → 全市场推荐」入口（纯新增零重构）
    expect(screen.getByTestId('rec-link-market-top')).toHaveAttribute('href', '#/market-top');
    // 请求线格式：无筛选参数
    const firstCall = String(fetchMock.mock.calls[0][0]);
    expect(firstCall).toContain('/recommendations');
    expect(firstCall).not.toContain('level=');
  });

  it('卡片曝光埋点：页面拉取后按卡 fire-and-forget 上报 RECOMMENDATION_VIEW（adopt-v1 曝光②）', async () => {
    const fetchMock = stubFetch([
      { path: '/api/v1/recommendations', respond: () => ok(viewOf([cardOf({ id: 9 })])) },
      { path: '/api/v1/reading-events', respond: () => ok(null) },
    ]);

    const { Recommendations } = await import('@/pages/Recommendations');
    render(<Recommendations />);
    await screen.findByTestId('rec-card-9');

    await waitFor(() => {
      const viewCalls = fetchMock.mock.calls.filter(
        (call) => String(call[0]).includes('/reading-events'),
      );
      expect(viewCalls).toHaveLength(1);
      expect(String(viewCalls[0][1]?.body)).toContain('"contentType":"RECOMMENDATION_VIEW"');
      expect(String(viewCalls[0][1]?.body)).toContain('"contentRef":"9"');
    });
  });

  it('逻辑链可回溯三环节：行业跳行业热度下钻、标的跳标的详情、摘要/引用取自原文', async () => {
    stubFetch([
      { path: '/api/v1/recommendations', respond: () => ok(viewOf([cardOf()])) },
    ]);

    const { Recommendations } = await import('@/pages/Recommendations');
    render(<Recommendations />);

    const industries = await screen.findByTestId('rec-industries-9');
    const industryChip = within(industries).getByText('食品饮料');
    expect(industryChip.closest('a')).toHaveAttribute(
      'href',
      `#/industry-heat?industry=${encodeURIComponent('食品饮料')}`,
    );
    expect(screen.getByTestId('rec-subject-SH600519')).toHaveAttribute(
      'href',
      '#/subjects/SH600519',
    );
  });

  it('已自选标的不展示加自选按钮（inWatchlist=true 徽章标识）', async () => {
    stubFetch([
      {
        path: '/api/v1/recommendations',
        respond: () =>
          ok(
            viewOf([
              cardOf({
                subjects: [
                  { code: 'SH600519', name: '贵州茅台', industry: '食品饮料', inWatchlist: true },
                ],
              }),
            ]),
          ),
      },
    ]);

    const { Recommendations } = await import('@/pages/Recommendations');
    render(<Recommendations />);

    expect(await screen.findByTestId('rec-card-9')).toBeInTheDocument();
    expect(screen.queryByTestId('rec-addwatch-9-SH600519')).toBeNull();
    expect(screen.getByTestId('rec-inwatch-SH600519')).toBeInTheDocument();
  });

  it('加自选：POST feedback（action=ADD_WATCHLIST + subjectCode）→ 即时态更新（按钮消失/已自选徽章出现）', async () => {
    const user = userEvent.setup();
    const fetchMock = stubFetch([
      { path: '/api/v1/recommendations', respond: () => ok(viewOf([cardOf()])) },
      {
        path: '/api/v1/recommendations/9/feedback',
        respond: () => ok({ muteUntil: null, escalated: null }),
      },
    ]);

    const { Recommendations } = await import('@/pages/Recommendations');
    render(<Recommendations />);
    await user.click(await screen.findByTestId('rec-addwatch-9-SH600519'));

    const feedbackCalls = fetchMock.mock.calls.filter(
      (call) => String(call[0]).includes('/recommendations/9/feedback'),
    );
    expect(feedbackCalls).toHaveLength(1);
    expect(String(feedbackCalls[0][1]?.body)).toContain('"action":"ADD_WATCHLIST"');
    expect(String(feedbackCalls[0][1]?.body)).toContain('"subjectCode":"SH600519"');
    await waitFor(() =>
      expect(screen.queryByTestId('rec-addwatch-9-SH600519')).toBeNull(),
    );
    expect(screen.getByTestId('rec-inwatch-SH600519')).toBeInTheDocument();
  });

  it('有用反馈：POST feedback（action=USEFUL）→ 操作条已点态（按钮禁用 + 已点文案）', async () => {
    const user = userEvent.setup();
    const fetchMock = stubFetch([
      { path: '/api/v1/recommendations', respond: () => ok(viewOf([cardOf()])) },
      {
        path: '/api/v1/recommendations/9/feedback',
        respond: () => ok({ muteUntil: null, escalated: null }),
      },
    ]);

    const { Recommendations } = await import('@/pages/Recommendations');
    render(<Recommendations />);
    await user.click(await screen.findByTestId('rec-useful-9'));

    const feedbackCalls = fetchMock.mock.calls.filter(
      (call) => String(call[0]).includes('/recommendations/9/feedback'),
    );
    expect(String(feedbackCalls[0][1]?.body)).toContain('"action":"USEFUL"');
    await waitFor(() => expect(screen.getByTestId('rec-useful-9')).toBeDisabled());
  });

  it('不感兴趣：POST feedback（action=DISLIKE）→ 卡片降频标记出现 + 撤销降频入口', async () => {
    const user = userEvent.setup();
    const fetchMock = stubFetch([
      { path: '/api/v1/recommendations', respond: () => ok(viewOf([cardOf()])) },
      {
        path: '/api/v1/recommendations/9/feedback',
        respond: () => ok({ muteUntil: '2026-09-29T08:00:00Z', escalated: false }),
      },
    ]);

    const { Recommendations } = await import('@/pages/Recommendations');
    render(<Recommendations />);
    await user.click(await screen.findByTestId('rec-dislike-9'));

    const feedbackCalls = fetchMock.mock.calls.filter(
      (call) => String(call[0]).includes('/recommendations/9/feedback'),
    );
    expect(String(feedbackCalls[0][1]?.body)).toContain('"action":"DISLIKE"');
    expect(await screen.findByTestId('rec-muted-9')).toBeInTheDocument();
    expect(screen.getByTestId('rec-undomute-9')).toBeInTheDocument();
  });

  it('撤销降频：POST feedback（action=UNDO_MUTE）→ 降频标记消失', async () => {
    const user = userEvent.setup();
    const fetchMock = stubFetch([
      {
        path: '/api/v1/recommendations',
        respond: () => ok(viewOf([cardOf({ muted: true, feedbackAction: 'DISLIKE' })])),
      },
      {
        path: '/api/v1/recommendations/9/feedback',
        respond: () => ok({ muteUntil: null, escalated: null }),
      },
    ]);

    const { Recommendations } = await import('@/pages/Recommendations');
    render(<Recommendations />);
    expect(await screen.findByTestId('rec-muted-9')).toBeInTheDocument();

    await user.click(screen.getByTestId('rec-undomute-9'));

    const feedbackCalls = fetchMock.mock.calls.filter(
      (call) => String(call[0]).includes('/recommendations/9/feedback'),
    );
    expect(String(feedbackCalls[0][1]?.body)).toContain('"action":"UNDO_MUTE"');
    await waitFor(() => expect(screen.queryByTestId('rec-muted-9')).toBeNull());
  });

  it('点击原文外链前 fire-and-forget 已读：POST /read + 未读点消失（展开即采纳）', async () => {
    const user = userEvent.setup();
    const fetchMock = stubFetch([
      { path: '/api/v1/recommendations', respond: () => ok(viewOf([cardOf()])) },
      { path: '/api/v1/recommendations/9/read', respond: () => ok(null) },
    ]);

    const { Recommendations } = await import('@/pages/Recommendations');
    render(<Recommendations />);
    expect(await screen.findByTestId('rec-unread-9')).toBeInTheDocument();

    await user.click(screen.getByTestId('rec-link-9'));

    await waitFor(() => {
      expect(
        fetchMock.mock.calls.some((call) => String(call[0]).includes('/recommendations/9/read')),
      ).toBe(true);
    });
    await waitFor(() => expect(screen.queryByTestId('rec-unread-9')).toBeNull());
  });

  it('筛选行（级别/类型/方向）：变更回第 1 页带参重拉', async () => {
    const fetchMock = stubFetch([
      { path: '/api/v1/recommendations', respond: () => ok(viewOf([])) },
    ]);

    const { Recommendations } = await import('@/pages/Recommendations');
    render(<Recommendations />);
    await screen.findByTestId('rec-empty');

    const user = userEvent.setup();
    await user.selectOptions(screen.getByTestId('rec-filter-level'), 'P1');
    await user.selectOptions(screen.getByTestId('rec-filter-type'), 'BUYBACK_CHANGE');
    await user.selectOptions(screen.getByTestId('rec-filter-direction'), 'BULLISH');

    await waitFor(() => {
      const last = String(fetchMock.mock.calls.at(-1)?.[0]);
      expect(last).toContain('level=P1');
      expect(last).toContain('eventType=BUYBACK_CHANGE');
      expect(last).toContain('direction=BULLISH');
    });
  });

  it('游标分页退役 → M9 页码分页：首查 page=1&size=20，「共 N 条」与分页 total 一致，无「加载更多」', async () => {
    const fetchMock = stubFetch([
      { path: '/api/v1/recommendations', respond: () => ok(viewOf([cardOf()], 30)) },
    ]);

    const { Recommendations } = await import('@/pages/Recommendations');
    render(<Recommendations />);

    expect(await screen.findByTestId('rec-card-9')).toBeInTheDocument();
    expect(screen.getByTestId('rec-total')).toHaveTextContent('30');
    expect(screen.getByTestId('rec-pagination-total')).toHaveTextContent('30');
    expect(screen.getByTestId('rec-pagination-page-indicator')).toHaveTextContent('1 / 2');
    // 请求线：页码模式（page/size），游标退役
    const firstCall = String(fetchMock.mock.calls[0][0]);
    expect(firstCall).toContain('/recommendations');
    expect(paramOf(firstCall, 'page')).toBe('1');
    expect(paramOf(firstCall, 'size')).toBe('20');
    expect(firstCall).not.toContain('beforeId=');
    expect(screen.queryByTestId('rec-load-more')).toBeNull();
    expect(firstCall).not.toContain('level=');
  });

  it('翻页：点击第 2 页 → page=2 请求，卡片数据替换（非追加），页码指示联动', async () => {
    const fetchMock = stubFetch([
      {
        path: '/api/v1/recommendations',
        respond: (url) => {
          const page = paramOf(url, 'page') ?? '1';
          return page === '2'
            ? ok(viewOf([cardOf({ id: 8, summary: '第二页推荐卡' })], 30, 2))
            : ok(viewOf([cardOf()], 30, 1));
        },
      },
    ]);

    const { Recommendations } = await import('@/pages/Recommendations');
    render(<Recommendations />);

    await screen.findByTestId('rec-card-9');
    const user = userEvent.setup();
    await user.click(screen.getByTestId('rec-pagination-page-2'));

    expect(await screen.findByTestId('rec-card-8')).toBeInTheDocument();
    // 数据替换：第 1 页卡片不再渲染（非游标追加）
    expect(screen.queryByTestId('rec-card-9')).toBeNull();
    expect(screen.getByTestId('rec-pagination-page-indicator')).toHaveTextContent('2 / 2');
    const lastCall = listCallsOf(fetchMock).at(-1) ?? '';
    expect(paramOf(lastCall, 'page')).toBe('2');
    expect(lastCall).not.toContain('beforeId=');
  });

  it('翻页失败：列表保留 + 错误可见可重试，重试成功落到目标页（在途不吞错）', async () => {
    let page2Fails = true;
    stubFetch([
      {
        path: '/api/v1/recommendations',
        respond: (url) => {
          if ((paramOf(url, 'page') ?? '1') === '2') {
            if (page2Fails) return fail(500, 50000, '翻页服务异常');
            return ok(viewOf([cardOf({ id: 8 })], 30, 2));
          }
          return ok(viewOf([cardOf()], 30, 1));
        },
      },
    ]);

    const { Recommendations } = await import('@/pages/Recommendations');
    render(<Recommendations />);

    await screen.findByTestId('rec-card-9');
    const user = userEvent.setup();
    await user.click(screen.getByTestId('rec-pagination-page-2'));

    expect(await screen.findByTestId('rec-pagination-error')).toHaveTextContent('加载第 2 页失败');
    // 在途失败不清列表（第 1 页卡片保留）
    expect(screen.getByTestId('rec-card-9')).toBeInTheDocument();

    page2Fails = false;
    await user.click(screen.getByTestId('rec-pagination-retry'));
    expect(await screen.findByTestId('rec-card-8')).toBeInTheDocument();
  });

  it('空页防御回退：末页收缩（items 空 + total>0）→ 静默重发末页落地（M9 §5.3）', async () => {
    let firstPageLoads = 0;
    const fetchMock = stubFetch([
      {
        path: '/api/v1/recommendations',
        respond: (url) => {
          const page = paramOf(url, 'page') ?? '1';
          if (page === '2') {
            // 请求第 2 页时数据已收缩：空列表 + total=15（只剩 1 页）→ 触发回退
            return ok(viewOf([], 15, 2));
          }
          firstPageLoads += 1;
          return ok(viewOf([cardOf()], firstPageLoads === 1 ? 30 : 15, 1));
        },
      },
    ]);

    const { Recommendations } = await import('@/pages/Recommendations');
    render(<Recommendations />);

    await screen.findByTestId('rec-card-9');
    const user = userEvent.setup();
    await user.click(screen.getByTestId('rec-pagination-page-2'));

    expect(await screen.findByTestId('rec-card-9')).toBeInTheDocument();
    await waitFor(() => expect(paramOf(listCallsOf(fetchMock).at(-1) ?? '', 'page')).toBe('1'));
    expect(screen.getByTestId('rec-pagination-page-indicator')).toHaveTextContent('1 / 1');
    expect(screen.queryByTestId('rec-pagination-error')).toBeNull();
    expect(screen.queryByTestId('rec-empty')).toBeNull();
  });

  it('条数切换：size 切 50 → 回第 1 页带新 size 重查', async () => {
    const fetchMock = stubFetch([
      { path: '/api/v1/recommendations', respond: () => ok(viewOf([cardOf()], 120)) },
    ]);

    const { Recommendations } = await import('@/pages/Recommendations');
    render(<Recommendations />);

    await screen.findByTestId('rec-card-9');
    const user = userEvent.setup();
    await user.click(screen.getByTestId('rec-pagination-page-3'));
    await waitFor(() => expect(paramOf(listCallsOf(fetchMock).at(-1) ?? '', 'page')).toBe('3'));

    await user.selectOptions(screen.getByTestId('rec-pagination-size'), '50');
    await waitFor(() => {
      const last = listCallsOf(fetchMock).at(-1) ?? '';
      expect(paramOf(last, 'page')).toBe('1'); // 条数切换回第 1 页
      expect(paramOf(last, 'size')).toBe('50');
    });
  });

  it('focus 参数定位：SSE 跳转落地 #/recommendations?focus=9 → 高亮目标卡 + 自动已读', async () => {
    window.location.hash = '#/recommendations?focus=9';
    const fetchMock = stubFetch([
      { path: '/api/v1/recommendations', respond: () => ok(viewOf([cardOf()])) },
      { path: '/api/v1/recommendations/9/read', respond: () => ok(null) },
    ]);

    const { Recommendations } = await import('@/pages/Recommendations');
    render(<Recommendations />);

    expect(await screen.findByTestId('rec-focus-9')).toBeInTheDocument();
    await waitFor(() => {
      expect(
        fetchMock.mock.calls.some((call) => String(call[0]).includes('/recommendations/9/read')),
      ).toBe(true);
    });
  });

  it('三态齐备：首屏骨架 / 错误重试恢复 / 空态引导文案', async () => {
    let failing = true;
    stubFetch([
      {
        path: '/api/v1/recommendations',
        respond: () => (failing ? fail(500, 50000, '服务异常') : ok(viewOf([cardOf()]))),
      },
    ]);

    const { Recommendations } = await import('@/pages/Recommendations');
    render(<Recommendations />);

    expect(screen.getByTestId('rec-loading')).toBeInTheDocument();
    expect(await screen.findByTestId('rec-error')).toHaveTextContent('服务异常');

    failing = false;
    const user = userEvent.setup();
    await user.click(screen.getByTestId('rec-retry'));
    expect(await screen.findByTestId('rec-card-9')).toBeInTheDocument();
  });

  it('空态：暂无动态推荐（触发门槛与关联命中说明）', async () => {
    stubFetch([{ path: '/api/v1/recommendations', respond: () => ok(viewOf([])) }]);

    const { Recommendations } = await import('@/pages/Recommendations');
    render(<Recommendations />);

    expect(await screen.findByTestId('rec-empty')).toHaveTextContent('暂无动态推荐');
    // T228 抽查：统一空态组件结构（标题 + 引导描述）
    expect(screen.getByTestId('empty-state-title')).toHaveTextContent('暂无动态推荐');
    expect(screen.getByTestId('empty-state-description')).toHaveTextContent('可稍后刷新或调整关注配置');
  });

  it('反馈失败（30081）：错误就地提示，不打断卡片流', async () => {
    const user = userEvent.setup();
    stubFetch([
      { path: '/api/v1/recommendations', respond: () => ok(viewOf([cardOf()])) },
      {
        path: '/api/v1/recommendations/9/feedback',
        respond: () => fail(400, 30081, 'action: 须为 USEFUL / DISLIKE / ADD_WATCHLIST / UNDO_MUTE'),
      },
    ]);

    const { Recommendations } = await import('@/pages/Recommendations');
    render(<Recommendations />);
    await user.click(await screen.findByTestId('rec-useful-9'));

    expect(await screen.findByTestId('rec-feedback-error-9')).toBeInTheDocument();
    expect(screen.getByTestId('rec-useful-9')).not.toBeDisabled();
  });
});
