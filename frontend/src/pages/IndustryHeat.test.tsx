import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { IndustryHeat } from '@/pages/IndustryHeat';
import { formatDateTime } from '@/lib/format';
import type {
  IndustryHeatBoardView,
  IndustryHeatItemsView,
  IndustryReportDetailView,
  IndustryReportListView,
} from '@/types/industryHeat';

// —— fetch mock：对齐后端 IndustryHeatController/IndustryReportController 契约（方案 §4.8） ——

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

function boardOf(overrides: Partial<IndustryHeatBoardView> = {}): IndustryHeatBoardView {
  return {
    window: 'H24',
    industries: [
      { industry: '电子', heatScore: 40, prevScore: 32, deltaPct: 25, newsCount: 42, eventCount: 3 },
      { industry: '食品饮料', heatScore: 20, prevScore: 25, deltaPct: -20, newsCount: 18, eventCount: 1 },
      { industry: '银行', heatScore: 10, prevScore: 10, deltaPct: 0, newsCount: 8, eventCount: 0 },
      { industry: '美容护理', heatScore: 0, prevScore: 0, deltaPct: 0, newsCount: 0, eventCount: 0 },
    ],
    basis: 'heat-v1:k1=10;hl=12h',
    snapshotAt: '2026-09-22T08:00:00Z',
    pipeline: { level: 'NORMAL' },
    ...overrides,
  };
}

function newsItemsOf(total = 2, nextBeforeId: number | null = null): IndustryHeatItemsView {
  return {
    industry: '电子',
    window: 'H24',
    type: 'news',
    total,
    items: [
      {
        newsId: 101,
        eventId: null,
        title: '半导体设备出口管制收紧',
        sourceName: '财联社',
        publishedAt: '2026-09-22T07:30:00Z',
        hasEvent: true,
        eventType: null,
        summary: null,
        direction: null,
        importance: null,
        eventTime: null,
      },
      {
        newsId: 100,
        eventId: null,
        title: '消费电子出货量回升',
        sourceName: '金十数据',
        publishedAt: '2026-09-22T06:10:00Z',
        hasEvent: false,
        eventType: null,
        summary: null,
        direction: null,
        importance: null,
        eventTime: null,
      },
    ],
    nextBeforeId,
  };
}

function eventItemsOf(total = 2, nextBeforeId: number | null = null): IndustryHeatItemsView {
  return {
    industry: '电子',
    window: 'H24',
    type: 'events',
    total,
    items: [
      {
        newsId: 101,
        eventId: 9,
        title: '半导体设备出口管制收紧',
        sourceName: null,
        publishedAt: null,
        hasEvent: null,
        eventType: 'POLICY_RELEASE',
        summary: '出口管制升级，设备材料链承压',
        direction: 'BEARISH',
        importance: 'HIGH',
        eventTime: '2026-09-22T07:30:00Z',
      },
      {
        newsId: 95,
        eventId: 8,
        title: '某公司业绩预告净利增 80%',
        sourceName: null,
        publishedAt: null,
        hasEvent: null,
        eventType: 'EARNINGS_FORECAST',
        summary: '净利润同比增长 80%',
        direction: 'BULLISH',
        importance: 'MEDIUM',
        eventTime: '2026-09-21T15:00:00Z',
      },
    ],
    nextBeforeId,
  };
}

function reportList(): IndustryReportListView {
  return {
    reports: [
      {
        id: 2,
        reportDate: '2026-09-21',
        status: 'SUCCESS',
        summary: '昨日电子行业主线明确，政策与业绩双驱动。',
        totalNews: 210,
        totalEvents: 9,
        narrativeDegraded: true,
        createdAt: '2026-09-22T08:00:30Z',
        updatedAt: '2026-09-22T08:00:30Z',
      },
      {
        id: 1,
        reportDate: '2026-09-20',
        status: 'FAILED',
        summary: '生成失败：LLM 网关超时',
        totalNews: 180,
        totalEvents: 6,
        narrativeDegraded: true,
        createdAt: '2026-09-21T08:00:30Z',
        updatedAt: '2026-09-21T08:01:10Z',
      },
    ],
    nextBeforeId: null,
  };
}

function reportDetail(): IndustryReportDetailView {
  return {
    id: 2,
    reportDate: '2026-09-21',
    status: 'SUCCESS',
    content: {
      summary: '昨日行业主线：电子领涨，政策与业绩双驱动。',
      narrativeDegraded: true,
      watchPoints: ['关注半导体设备链订单落地', '留意消费电子旺季数据'],
      industryCounts: { 电子: 42, 食品饮料: 18 },
      containerCounts: { 宏观: 12 },
      totalNews: 210,
      totalEvents: 9,
      topIndustries: [
        {
          industry: '电子',
          newsCount: 42,
          eventCount: 3,
          heatScore: 40,
          deltaPct: 25,
          commentary: '政策与业绩共振，热度环比抬升。',
          refEventIds: [9],
        },
      ],
      events: [
        {
          eventId: 9,
          newsId: 101,
          eventType: 'POLICY_RELEASE',
          summary: '出口管制升级，设备材料链承压',
          industries: ['电子'],
          direction: 'BEARISH',
          importance: 'HIGH',
          quote: '半导体设备出口管制收紧',
          figures: [{ label: '涉及品类', value: '3 类', unit: '' }],
          eventTime: '2026-09-22T07:30:00Z',
        },
      ],
      disclaimer: 'AI 分析仅供参考',
    },
    heatTop: null,
    errorMessage: null,
    promptVersion: 'v1.0',
    basis: 'heat+cost-v2',
    createdAt: '2026-09-22T08:00:30Z',
    updatedAt: '2026-09-22T08:00:30Z',
  };
}

/** 按路径前缀分发的 fetch mock（POST retry 由专用用例自建 mock 覆盖）。 */
function stubFetch(routes: Array<{ path: string; respond: () => ReturnType<typeof ok> | ReturnType<typeof fail> }>) {
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

describe('IndustryHeat 行业热度与日报页（T126）· 热度榜 Tab', () => {
  it('默认拉取 H24 榜单：排名/行业名/热度条/涨跌徽章/资讯数与事件数齐备', async () => {
    const fetchMock = stubFetch([{ path: '/api/v1/industry-heat', respond: () => ok(boardOf()) }]);

    render(<IndustryHeat />);

    expect(await screen.findByTestId('heat-row-电子')).toBeInTheDocument();
    const first = screen.getByTestId('heat-row-电子');
    expect(within(first).getByTestId('heat-rank')).toHaveTextContent('1');
    expect(within(first).getByText('42')).toBeInTheDocument(); // 资讯数（对账口径）
    expect(within(first).getByText('3')).toBeInTheDocument(); // 事件数
    expect(String(fetchMock.mock.calls[0][0])).toContain('/industry-heat?window=H24');
  });

  it('热度条按 Top1 归一化宽度（第二名 = Top 的一半），0 分沉底行零宽', async () => {
    stubFetch([{ path: '/api/v1/industry-heat', respond: () => ok(boardOf()) }]);

    render(<IndustryHeat />);

    await screen.findByTestId('heat-row-电子');
    expect(screen.getByTestId('heat-bar-电子')).toHaveStyle({ width: '100%' });
    expect(screen.getByTestId('heat-bar-食品饮料')).toHaveStyle({ width: '50%' });
    expect(screen.getByTestId('heat-bar-美容护理')).toHaveStyle({ width: '0%' });
  });

  it('环比徽章沿 A 股惯例配色：涨红跌绿持平灰', async () => {
    stubFetch([{ path: '/api/v1/industry-heat', respond: () => ok(boardOf()) }]);

    render(<IndustryHeat />);

    await screen.findByTestId('heat-row-电子');
    expect(screen.getByTestId('heat-delta-电子')).toHaveTextContent('+25.00%');
    expect(screen.getByTestId('heat-delta-电子').className).toContain('text-red-500');
    expect(screen.getByTestId('heat-delta-食品饮料')).toHaveTextContent('-20.00%');
    expect(screen.getByTestId('heat-delta-食品饮料').className).toContain('text-green-500');
    expect(screen.getByTestId('heat-delta-银行').className).toContain('text-muted-foreground');
  });

  it('脚注展示热度口径版本串与快照时间（格式化口径与 lib/format 一致）', async () => {
    stubFetch([{ path: '/api/v1/industry-heat', respond: () => ok(boardOf()) }]);

    render(<IndustryHeat />);

    const footnote = await screen.findByTestId('heat-footnote');
    expect(footnote).toHaveTextContent('heat-v1:k1=10;hl=12h');
    expect(footnote).toHaveTextContent(formatDateTime('2026-09-22T08:00:00Z'));
  });

  it('窗口切换 24 小时 / 7 天：按新窗口参数重新请求', async () => {
    const fetchMock = stubFetch([{ path: '/api/v1/industry-heat', respond: () => ok(boardOf()) }]);

    render(<IndustryHeat />);
    await screen.findByTestId('heat-row-电子');

    await userEvent.click(screen.getByTestId('heat-window-d7'));
    await waitFor(() => {
      expect(fetchMock.mock.calls.some((call) => String(call[0]).includes('window=D7'))).toBe(true);
    });
  });

  it('护栏横幅三态：DEGRADED 黄「AI 管道降级中」/ FUSED 红「AI 管道已熔断」/ NORMAL 不渲染', async () => {
    stubFetch([
      { path: '/api/v1/industry-heat', respond: () => ok(boardOf({ pipeline: { level: 'NORMAL' } })) },
    ]);
    const { unmount } = render(<IndustryHeat />);
    await screen.findByTestId('heat-row-电子');
    expect(screen.queryByTestId('heat-banner')).toBeNull();
    unmount();

    stubFetch([
      { path: '/api/v1/industry-heat', respond: () => ok(boardOf({ pipeline: { level: 'DEGRADED' } })) },
    ]);
    const degraded = render(<IndustryHeat />);
    expect(await degraded.findByTestId('heat-banner')).toHaveTextContent('AI 管道降级中');
    expect(degraded.getByTestId('heat-banner').className).toContain('text-amber-400');
    degraded.unmount();

    stubFetch([
      { path: '/api/v1/industry-heat', respond: () => ok(boardOf({ pipeline: { level: 'FUSED' } })) },
    ]);
    const fused = render(<IndustryHeat />);
    expect(await fused.findByTestId('heat-banner')).toHaveTextContent('AI 管道已熔断');
    expect(fused.getByTestId('heat-banner').className).toContain('text-rose-400');
    fused.unmount();
  });

  it('行点击页内展开下钻：默认 news 清单，total 与榜单计数对账展示', async () => {
    const fetchMock = stubFetch([
      { path: '/api/v1/industry-heat?window=H24', respond: () => ok(boardOf()) },
      { path: '/api/v1/industry-heat/%E7%94%B5%E5%AD%90/items', respond: () => ok(newsItemsOf(42)) },
    ]);

    render(<IndustryHeat />);
    await screen.findByTestId('heat-row-电子');

    await userEvent.click(screen.getByTestId('heat-row-电子'));

    const drill = await screen.findByTestId('heat-drilldown-电子');
    expect(within(drill).getByTestId('drill-total-电子')).toHaveTextContent('42');
    expect(within(drill).getByText('半导体设备出口管制收紧')).toBeInTheDocument();
    expect(within(drill).getByTestId('drill-has-event-101')).toBeInTheDocument();
    const drillUrl = String(
      fetchMock.mock.calls.find((call) => String(call[0]).includes('/items'))?.[0] ?? '',
    );
    expect(drillUrl).toContain('type=news');
    expect(drillUrl).toContain('window=H24');
  });

  it('下钻 events 切换：事件行展示类型徽章/方向/重要度（与事件流同口径）', async () => {
    stubFetch([
      { path: '/api/v1/industry-heat?window=H24', respond: () => ok(boardOf()) },
      { path: '/api/v1/industry-heat/%E7%94%B5%E5%AD%90/items', respond: () => ok(newsItemsOf(42)) },
    ]);

    render(<IndustryHeat />);
    await screen.findByTestId('heat-row-电子');
    await userEvent.click(screen.getByTestId('heat-row-电子'));
    await screen.findByTestId('heat-drilldown-电子');

    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.includes('type=events')) return ok(eventItemsOf(3));
        return fail(404, 50000, `unexpected: ${url}`);
      }),
    );
    await userEvent.click(screen.getByTestId('drill-type-events-电子'));

    const drill = await screen.findByTestId('heat-drilldown-电子');
    expect(within(drill).getByTestId('drill-total-电子')).toHaveTextContent('3');
    expect(within(drill).getByText('政策发布')).toBeInTheDocument();
    expect(within(drill).getByText('利空')).toBeInTheDocument();
    expect(within(drill).getByText(/重要度 高/)).toBeInTheDocument();
  });

  it('下钻游标加载更多：nextBeforeId 非空展示按钮，点击带 beforeId 追加，尾页隐藏', async () => {
    const eventsFetch = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/items')) {
        // 首屏 news 清单（默认 type）
        if (url.includes('type=news')) return ok(newsItemsOf(42));
        return url.includes('beforeId=8')
          ? ok({ ...eventItemsOf(3, null), items: [{ ...eventItemsOf().items[0], eventId: 7 }] })
          : ok(eventItemsOf(3, 8));
      }
      if (url.startsWith('/api/v1/industry-heat')) return ok(boardOf());
      return fail(404, 50000, `unexpected: ${url}`);
    });
    vi.stubGlobal('fetch', eventsFetch);

    render(<IndustryHeat />);
    await screen.findByTestId('heat-row-电子');
    await userEvent.click(screen.getByTestId('heat-row-电子'));
    await screen.findByTestId('heat-drilldown-电子');
    await userEvent.click(screen.getByTestId('drill-type-events-电子'));

    await userEvent.click(await screen.findByTestId('drill-load-more-电子'));

    await waitFor(() => {
      expect(eventsFetch.mock.calls.some((call) => String(call[0]).includes('beforeId=8'))).toBe(
        true,
      );
    });
    await waitFor(() => {
      expect(screen.queryByTestId('drill-load-more-电子')).toBeNull();
    });
  });

  it('首屏加载骨架（不裸转圈）与错误态：错误信息展示 + 重试入口', async () => {
    // 阶段一：永不 resolve → 骨架可见
    vi.stubGlobal(
      'fetch',
      vi.fn(() => new Promise<Response>(() => undefined)),
    );
    const pending = render(<IndustryHeat />);
    expect(pending.getByTestId('heat-loading')).toBeInTheDocument();
    pending.unmount();

    // 阶段二：400/30076 → 错误态 + 重试（重试仍失败不白屏）
    stubFetch([{ path: '/api/v1/industry-heat', respond: () => fail(400, 30076, '管道查询参数非法') }]);
    render(<IndustryHeat />);

    const error = await screen.findByTestId('heat-error');
    expect(error).toHaveTextContent('管道查询参数非法');
    await userEvent.click(screen.getByTestId('heat-retry'));
    expect(await screen.findByTestId('heat-error')).toBeInTheDocument();
  });

  it('全库无数据空态：榜单空数组给出引导文案不报错', async () => {
    stubFetch([{ path: '/api/v1/industry-heat', respond: () => ok(boardOf({ industries: [] })) }]);

    render(<IndustryHeat />);

    expect(await screen.findByTestId('heat-empty')).toHaveTextContent('暂无行业热度数据');
  });
});

describe('IndustryHeat 行业热度与日报页（T126）· 日报 Tab', () => {
  async function openReportTab() {
    await userEvent.click(screen.getByTestId('heat-tab-report'));
    await screen.findByTestId('report-list-card-2026-09-21');
  }

  it('切换日报 Tab 拉取列表：日期/状态徽章/摘要/总条数/纯统计版标识齐备', async () => {
    const fetchMock = stubFetch([
      { path: '/api/v1/industry-heat', respond: () => ok(boardOf()) },
      { path: '/api/v1/industry-reports', respond: () => ok(reportList()) },
    ]);

    render(<IndustryHeat />);
    await openReportTab();

    const card = screen.getByTestId('report-list-card-2026-09-21');
    expect(within(card).getByText('2026-09-21')).toBeInTheDocument();
    expect(within(card).getByTestId('report-status-2026-09-21')).toHaveTextContent('成功');
    expect(within(card).getByText('210')).toBeInTheDocument();
    expect(within(card).getByTestId('report-degraded-2026-09-21')).toHaveTextContent('纯统计版');
    expect(
      fetchMock.mock.calls.some((call) => String(call[0]).startsWith('/api/v1/industry-reports')),
    ).toBe(true);
  });

  it('首日无日报空态：引导文案（每日 08:00 生成）', async () => {
    stubFetch([
      { path: '/api/v1/industry-heat', respond: () => ok(boardOf()) },
      { path: '/api/v1/industry-reports', respond: () => ok({ reports: [], nextBeforeId: null }) },
    ]);

    render(<IndustryHeat />);
    await userEvent.click(screen.getByTestId('heat-tab-report'));

    expect(await screen.findByTestId('report-empty')).toHaveTextContent('08:00');
  });

  it('点击日报卡片进详情：叙述 + 行业动态表 + 事件精选（quote/关键数字）+ watchPoints + 免责声明', async () => {
    const fetchMock = stubFetch([
      { path: '/api/v1/industry-heat', respond: () => ok(boardOf()) },
      { path: '/api/v1/industry-reports/2026-09-21', respond: () => ok(reportDetail()) },
      { path: '/api/v1/industry-reports', respond: () => ok(reportList()) },
    ]);

    render(<IndustryHeat />);
    await openReportTab();
    await userEvent.click(screen.getByTestId('report-list-card-2026-09-21'));

    const detail = await screen.findByTestId('report-detail-page');
    expect(within(detail).getByTestId('report-detail-summary')).toHaveTextContent('电子领涨');
    const topRow = within(detail).getByTestId('report-detail-top-电子');
    expect(within(topRow).getByText('42')).toBeInTheDocument();
    expect(within(topRow).getByText(/政策与业绩共振/)).toBeInTheDocument();
    const eventRow = within(detail).getByTestId('report-detail-event-9');
    expect(within(eventRow).getByText('政策发布')).toBeInTheDocument();
    expect(within(eventRow).getByText('出口管制升级，设备材料链承压')).toBeInTheDocument();
    expect(within(eventRow).getByText(/「半导体设备出口管制收紧」/)).toBeInTheDocument(); // quote 原文引用
    expect(within(eventRow).getByText(/涉及品类/)).toBeInTheDocument(); // 关键数字（原文数字）
    expect(within(detail).getByTestId('report-detail-watchpoints')).toHaveTextContent(
      '关注半导体设备链订单落地',
    );
    expect(within(detail).getByTestId('report-detail-disclaimer')).toHaveTextContent(
      'AI 分析仅供参考',
    );
    expect(
      fetchMock.mock.calls.some((call) =>
        String(call[0]).startsWith('/api/v1/industry-reports/2026-09-21'),
      ),
    ).toBe(true);
  });

  it('详情返回列表：回退不重复请求列表', async () => {
    const fetchMock = stubFetch([
      { path: '/api/v1/industry-heat', respond: () => ok(boardOf()) },
      { path: '/api/v1/industry-reports/2026-09-21', respond: () => ok(reportDetail()) },
      { path: '/api/v1/industry-reports', respond: () => ok(reportList()) },
    ]);

    render(<IndustryHeat />);
    await openReportTab();
    await userEvent.click(screen.getByTestId('report-list-card-2026-09-21'));
    await screen.findByTestId('report-detail-page');

    await userEvent.click(screen.getByTestId('report-detail-back'));
    expect(await screen.findByTestId('report-list-card-2026-09-21')).toBeInTheDocument();

    const listCalls = fetchMock.mock.calls.filter(
      (call) => String(call[0]) === '/api/v1/industry-reports',
    );
    expect(listCalls).toHaveLength(1);
  });

  it('FAILED 日报重试：POST 202 受理 → 轻轮询至状态翻成功，受理中提示且不重复点击', async () => {
    let listCalls = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.startsWith('/api/v1/industry-heat')) return ok(boardOf());
      if (url.includes('/retry')) return ok({ executionId: 501 });
      if (url.startsWith('/api/v1/industry-reports')) {
        listCalls += 1;
        if (listCalls === 1) return ok(reportList());
        const regenerated = reportList();
        regenerated.reports[1] = {
          ...regenerated.reports[1],
          status: 'SUCCESS',
          summary: '补生成成功',
          narrativeDegraded: false,
        };
        return ok(regenerated);
      }
      return fail(404, 50000, `unexpected: ${url}`);
    });
    vi.stubGlobal('fetch', fetchMock);

    render(<IndustryHeat retryPollMs={10} />);
    await userEvent.click(screen.getByTestId('heat-tab-report'));
    await screen.findByTestId('report-list-card-2026-09-20');

    await userEvent.click(screen.getByTestId('report-retry-2026-09-20'));

    expect(await screen.findByTestId('report-retrying-2026-09-20')).toHaveTextContent('重试已受理');
    await waitFor(
      () => {
        expect(screen.getByTestId('report-status-2026-09-20')).toHaveTextContent('成功');
      },
      { timeout: 3000 },
    );
    await waitFor(() => {
      expect(screen.queryByTestId('report-retrying-2026-09-20')).toBeNull();
    });
    const retryCalls = fetchMock.mock.calls.filter((call) =>
      String(call[0]).includes('/industry-reports/2026-09-20/retry'),
    );
    expect(retryCalls).toHaveLength(1);
  });

  it('重试冲突（已 SUCCESS 30077/409）：展示后端错误文案，不进入轮询', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.startsWith('/api/v1/industry-heat')) return ok(boardOf());
      if (url.includes('/retry')) return fail(409, 30077, '该日日报已成功生成，无需重试');
      if (url.startsWith('/api/v1/industry-reports')) return ok(reportList());
      return fail(404, 50000, `unexpected: ${url}`);
    });
    vi.stubGlobal('fetch', fetchMock);

    render(<IndustryHeat retryPollMs={10} />);
    await userEvent.click(screen.getByTestId('heat-tab-report'));
    await screen.findByTestId('report-list-card-2026-09-20');

    await userEvent.click(screen.getByTestId('report-retry-2026-09-20'));

    expect(await screen.findByTestId('report-retry-error')).toHaveTextContent('已成功生成');
    expect(screen.queryByTestId('report-retrying-2026-09-20')).toBeNull();
  });

  it('日报列表加载失败：错误态 + 重试入口恢复', async () => {
    let failed = true;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.startsWith('/api/v1/industry-heat')) return ok(boardOf());
        if (url.startsWith('/api/v1/industry-reports')) {
          if (failed) {
            failed = false;
            return fail(500, 50000, '服务异常');
          }
          return ok(reportList());
        }
        return fail(404, 50000, `unexpected: ${url}`);
      }),
    );

    render(<IndustryHeat />);
    await userEvent.click(screen.getByTestId('heat-tab-report'));

    const error = await screen.findByTestId('report-error');
    expect(error).toHaveTextContent('服务异常');
    await userEvent.click(screen.getByTestId('report-retry'));
    expect(await screen.findByTestId('report-list-card-2026-09-21')).toBeInTheDocument();
  });

  it('热度榜与日报双 Tab 切换互不干扰（回热度榜榜单仍在）', async () => {
    stubFetch([
      { path: '/api/v1/industry-heat', respond: () => ok(boardOf()) },
      { path: '/api/v1/industry-reports', respond: () => ok(reportList()) },
    ]);

    render(<IndustryHeat />);
    await screen.findByTestId('heat-row-电子');
    await openReportTab();
    await userEvent.click(screen.getByTestId('heat-tab-heat'));

    expect(await screen.findByTestId('heat-row-电子')).toBeInTheDocument();
    expect(screen.queryByTestId('report-list-card-2026-09-21')).toBeNull();
  });
});
