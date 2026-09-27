import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { Policy } from '@/pages/Policy';
import { formatDateTime } from '@/lib/format';
import type { InfoSourceCardView, InfoSourcesView } from '@/types/infoSource';
import type {
  PolicyDetailView,
  PolicyPagedView,
  PolicyView,
} from '@/types/policy';

// —— fetch mock：GET /policies（页码分页 + 行业/源/关键词过滤）/ GET /policies/{id}（详情）/
//    GET /info-sources（源下拉选项，V2.3 T205） ——

const HTTP_BY_CODE: Record<number, number> = {
  30040: 404,
  50000: 500,
};
const MSG_BY_CODE: Record<number, string> = {
  30040: '政策条目不存在',
  50000: '服务异常',
};

const ok = (data: unknown) => ({
  ok: true,
  status: 200,
  json: async () => ({ code: 0, msg: 'ok', data, traceId: 't' }),
});
const fail = (code: number) => ({
  ok: false,
  status: HTTP_BY_CODE[code] ?? 500,
  json: async () => ({
    code,
    msg: MSG_BY_CODE[code] ?? '服务异常',
    data: null,
    traceId: 't',
  }),
});

/** 手动决斗 Promise（在途/竞态用例：挂起请求，测试择机放行）。 */
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

/** 源下拉选项最小卡片（/info-sources 响应用，仅需 name/sourceCode/category 生效字段）。 */
function sourceCardOf(
  id: number,
  sourceCode: string,
  name: string,
  category: string,
): InfoSourceCardView {
  return {
    id,
    sourceCode,
    name,
    category,
    adapterType: 'rss',
    adapterRef: null,
    endpoint: 'https://example.com/feed',
    config: {
      listPath: null,
      stripPrefix: null,
      stripSuffix: null,
      itemMapping: [],
      headers: {},
      maxItems: null,
      pageSize: null,
      cursorType: 'NONE',
      cursorField: null,
      aiExclusion: 'NONE',
      staleSince: null,
    },
    intervalMinutes: 15,
    enabled: true,
    preset: true,
    deleted: false,
    today: { pollCount: 0, failCount: 0, newCount: 0, dupCount: 0 },
    state: {
      lastAttemptAt: null,
      lastSuccessAt: null,
      nextDueAt: null,
      cursorValue: null,
      consecutiveFailures: 0,
      backoffUntil: null,
      lastDurationMillis: null,
      lastRoundDetail: null,
      lastError: null,
    },
    createdAt: '2026-09-22T00:00:00Z',
    updatedAt: '2026-09-22T00:00:00Z',
  };
}

function infoSourcesView(): InfoSourcesView {
  return {
    groups: [
      {
        category: '政策',
        sources: [
          sourceCardOf(1, 'gov_policy', '中国政府网·政策', '政策'),
          sourceCardOf(2, 'csrc_news', '中国证监会·要闻', '政策'),
        ],
      },
      { category: '宏观', sources: [sourceCardOf(3, 'stats_release', '国家统计局·最新发布', '宏观')] },
      { category: '快讯', sources: [sourceCardOf(4, 'jin10_flash', '金十数据·快讯', '快讯')] },
    ],
    archived: [],
  };
}

interface ServerOpts {
  items?: PolicyView[];
  detail?: PolicyDetailView;
  detailById?: Record<number, PolicyDetailView>;
  detailCode?: number;
  listCode?: number;
  /** /info-sources 响应（源下拉选项）；'fail' 模拟接口失败。 */
  infoSources?: InfoSourcesView | 'fail';
}

/**
 * 构造页码模式服务端 mock：按 query 参数（page/size/industry/sourceCode/keyword）过滤切片，
 * 返回 { policies, total, page, size, basis }（对齐后端契约：越界页 200 空列表 + 精确 total）。
 */
function makeServer(opts: ServerOpts = {}) {
  let items = opts.items ?? [];
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET';
    const path = String(url);
    if (method !== 'GET') return fail(50000);

    // 源下拉选项：/api/v1/info-sources
    if (/\/info-sources(\?|$)/.test(path)) {
      if (opts.infoSources === 'fail') return fail(50000);
      return ok(opts.infoSources ?? infoSourcesView());
    }

    // 详情：/api/v1/policies/{id}
    const d = path.match(/\/policies\/(\d+)$/);
    if (d) {
      if (opts.detailCode) return fail(opts.detailCode);
      const id = Number(d[1]);
      const byId = opts.detailById?.[id];
      if (byId) return ok(byId);
      return ok(opts.detail ?? null);
    }

    // 列表：/api/v1/policies?page&size&industry&sourceCode&keyword
    if (/\/policies\?/.test(path)) {
      if (opts.listCode) return fail(opts.listCode);
      const q = new URL(path, 'http://x').searchParams;
      const page = Number(q.get('page') ?? '1');
      const size = Number(q.get('size') ?? '20');
      const industry = q.get('industry') ?? '';
      const sourceCode = q.get('sourceCode') ?? '';
      const keyword = (q.get('keyword') ?? '').trim().toLowerCase();
      let filtered = items;
      if (industry) {
        // L1 口径（§4.1）：申万 main/sub 命中；监管·政策 容器仅 main
        filtered = filtered.filter((p) =>
          industry === '监管·政策'
            ? p.mainCategory === industry
            : p.mainCategory === industry || p.subIndustry === industry,
        );
      }
      if (sourceCode) {
        filtered = filtered.filter((p) => p.sourceCode === sourceCode);
      }
      if (keyword) {
        filtered = filtered.filter(
          (p) =>
            p.title.toLowerCase().includes(keyword) ||
            (p.summary ?? '').toLowerCase().includes(keyword),
        );
      }
      const view: PolicyPagedView = {
        policies: filtered.slice((page - 1) * size, page * size),
        total: filtered.length,
        page,
        size,
        basis: 'policy-scope-v1',
      };
      return ok(view);
    }
    return fail(50000);
  });
  return {
    fetch,
    /** 模拟服务端数据漂移（空页回退用例）。 */
    setItems(next: PolicyView[]) {
      items = next;
    },
  };
}

/** 取列表请求调用（排除详情/源下拉请求），便于断言 query 参数。 */
function listCallsOf(
  fetchMock: ReturnType<typeof makeServer>['fetch'],
): { url: string }[] {
  return fetchMock.mock.calls
    .map((c) => ({ url: String(c[0]) }))
    .filter((c) => /\/policies\?/.test(c.url));
}

function paramsOf(call: { url: string }): URLSearchParams {
  return new URL(call.url, 'http://x').searchParams;
}

// —— fixtures：1 条汽车（gov_policy，带回联标的）+ 44 条电子 = 45 条（3 页 @20） —— #

const POLICY_A: PolicyView = {
  id: 101,
  title: '关于促进新能源汽车产业高质量发展的若干政策',
  summary: '加大充电基础设施与锂电池研发补贴，利好产业链上下游。',
  url: 'http://gov.cn/policy-101',
  sourceCode: 'gov_policy',
  sourceName: '中国政府网·政策',
  publishedAt: '2026-09-19T00:00:00Z',
  mainCategory: '汽车',
  subIndustry: '汽车零部件',
  matchedSubjects: [
    { code: 'SZ300750', name: '宁德时代', industry: '电力设备' },
    { code: 'SH600884', name: '杉杉股份', industry: '汽车零部件' },
  ],
};
const REST: PolicyView[] = Array.from({ length: 44 }, (_, i) => ({
  id: 200 + i,
  title: `半导体产业扶持政策第 ${i + 1} 号`,
  summary: `第 ${i + 1} 条：设立专项基金支持先进制程与设备国产化。`,
  url: `http://csrc.gov.cn/policy-${200 + i}`,
  sourceCode: 'csrc_news',
  sourceName: '中国证监会·要闻',
  publishedAt: '2026-09-18T00:00:00Z',
  mainCategory: '电子',
  subIndustry: '半导体',
  matchedSubjects: [],
}));
const ALL_ITEMS: PolicyView[] = [POLICY_A, ...REST];

const DETAIL_WITH_EVENT: PolicyDetailView = {
  id: 101,
  title: POLICY_A.title,
  summary: POLICY_A.summary,
  url: 'http://gov.cn/policy-101',
  sourceCode: 'gov_policy',
  sourceName: '中国政府网·政策',
  publishedAt: '2026-09-19T00:00:00Z',
  mainCategory: '汽车',
  subIndustry: '汽车零部件',
  matchedSubjects: POLICY_A.matchedSubjects,
  relatedEvents: [
    {
      id: 55,
      eventType: 'POLICY_RELEASE',
      summary: '新能源车补贴延续，产业链受益',
      direction: 'BULLISH',
      importance: 'HIGH',
      eventDate: '2026-09-19',
    },
  ],
};

// jsdom 未实现 scrollIntoView：打桩（翻页/筛选成功后滚回列表顶）
beforeAll(() => {
  HTMLElement.prototype.scrollIntoView = vi.fn();
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.clearAllMocks();
  localStorage.clear();
  window.location.hash = '';
});

describe('Policy 政策时事页 · 首屏与分页条', () => {
  it('首屏：列表 + 分页条（共 45 条 / 第 1 / 3 页 / 三页码），首查 page=1&size=20&days=7', async () => {
    const server = makeServer({ items: ALL_ITEMS, detail: DETAIL_WITH_EVENT });
    vi.stubGlobal('fetch', server.fetch);
    render(<Policy />);

    // 第一页条目 + 详情空态（V2.3 T205 换面：源名/时间/L1 徽章/回联标的徽章）
    const itemA = await screen.findByTestId('policy-item-101');
    expect(itemA).toHaveTextContent('新能源汽车');
    expect(itemA).toHaveTextContent('来源：中国政府网·政策');
    expect(itemA).toHaveTextContent(formatDateTime(POLICY_A.publishedAt));
    expect(within(itemA).getByTestId('policy-item-101-category-汽车')).toBeInTheDocument();
    expect(within(itemA).getByTestId('policy-item-101-sub-汽车零部件')).toBeInTheDocument();
    expect(within(itemA).getByTestId('policy-item-101-subject-SZ300750')).toHaveTextContent(
      '宁德时代',
    );
    expect(screen.getByTestId('policy-detail-empty')).toBeInTheDocument();

    // 分页条
    expect(screen.getByTestId('pagination-total')).toHaveTextContent('共 45 条');
    expect(screen.getByTestId('pagination-page-indicator')).toHaveTextContent(
      '第 1 / 3 页',
    );
    expect(screen.getByTestId('pagination-page-1')).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(screen.getByTestId('pagination-page-3')).toBeInTheDocument();
    expect(screen.getByTestId('pagination-prev')).toBeDisabled();
    expect(screen.getByTestId('pagination-next')).toBeEnabled();

    // 首查参数：页码模式 + 默认时间窗 7 天
    const calls = listCallsOf(server.fetch);
    expect(calls.length).toBeGreaterThanOrEqual(1);
    expect(paramsOf(calls[0]).get('page')).toBe('1');
    expect(paramsOf(calls[0]).get('size')).toBe('20');
    expect(paramsOf(calls[0]).get('days')).toBe('7');
    expect(paramsOf(calls[0]).get('cursor')).toBeNull();

    // 「加载更多」链路已删除
    expect(screen.queryByTestId('policy-load-more')).toBeNull();
    expect(screen.queryByTestId('policy-more-error')).toBeNull();
  });

  it('跳页整体替换：点第 2 页 → 请求 page=2，条目替换、高亮切换、滚回列表顶', async () => {
    const server = makeServer({ items: ALL_ITEMS });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<Policy />);

    await screen.findByTestId('policy-item-101');
    vi.mocked(HTMLElement.prototype.scrollIntoView).mockClear();

    await user.click(screen.getByTestId('pagination-page-2'));

    // 整体替换：第 2 页首条在、第 1 页条目不在（非追加）
    expect(await screen.findByTestId('policy-item-220')).toBeInTheDocument();
    expect(screen.queryByTestId('policy-item-101')).toBeNull();
    expect(screen.getByTestId('pagination-page-indicator')).toHaveTextContent(
      '第 2 / 3 页',
    );
    expect(screen.getByTestId('pagination-page-2')).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(screen.getByTestId('pagination-prev')).toBeEnabled();

    const calls = listCallsOf(server.fetch);
    expect(paramsOf(calls[calls.length - 1]).get('page')).toBe('2');
    expect(HTMLElement.prototype.scrollIntoView).toHaveBeenCalled();
  });

  it('翻页在途：保留当前页条目 + 分页条禁用 + aria-busy + 「加载中…」状态行', async () => {
    const server = makeServer({ items: ALL_ITEMS });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<Policy />);

    await screen.findByTestId('policy-item-101');

    const d = deferred<ReturnType<typeof ok>>();
    server.fetch.mockImplementationOnce(() => d.promise);
    await user.click(screen.getByTestId('pagination-page-2'));

    // 在途：骨架不闪、条目保留、分页条禁用
    expect(await screen.findByTestId('policy-page-loading')).toHaveTextContent(
      '加载中…',
    );
    expect(screen.getByTestId('policy-item-101')).toBeInTheDocument();
    expect(screen.queryByTestId('policy-list-loading')).toBeNull();
    expect(screen.getByTestId('policy-list-section')).toHaveAttribute(
      'aria-busy',
      'true',
    );
    expect(screen.getByTestId('pagination-next')).toBeDisabled();
    expect(screen.getByTestId('pagination-page-3')).toBeDisabled();
    expect(screen.getByTestId('pagination-size')).toBeDisabled();
    expect(screen.getByTestId('policy-keyword-input')).toBeEnabled();

    // 放行：整体替换 + 状态行消失
    d.resolve(ok({ policies: [REST[19]], total: 45, page: 2, size: 20, basis: 'policy-scope-v1' }));
    expect(await screen.findByTestId('policy-item-219')).toBeInTheDocument();
    expect(screen.queryByTestId('policy-item-101')).toBeNull();
    await waitFor(() =>
      expect(screen.queryByTestId('policy-page-loading')).toBeNull(),
    );
  });

  it('翻页失败：保留当前页 + 「加载第 2 页失败」错误行 + 重试同页成功', async () => {
    const server = makeServer({ items: ALL_ITEMS });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<Policy />);

    await screen.findByTestId('policy-item-101');

    server.fetch.mockImplementationOnce(async () => fail(50000));
    await user.click(screen.getByTestId('pagination-page-2'));

    // 失败：数据保留、显示态不前跳、错误行 + 重试按钮、分页控件恢复可用
    expect(await screen.findByTestId('policy-pagination-error')).toHaveTextContent(
      '加载第 2 页失败：服务异常',
    );
    expect(screen.getByTestId('policy-item-101')).toBeInTheDocument();
    expect(screen.getByTestId('pagination-page-indicator')).toHaveTextContent(
      '第 1 / 3 页',
    );
    expect(screen.queryByTestId('policy-list-error')).toBeNull();
    expect(screen.getByTestId('pagination-next')).toBeEnabled();

    // 重试：重发同一目标页（page=2）
    await user.click(screen.getByTestId('policy-pagination-retry'));
    expect(await screen.findByTestId('policy-item-220')).toBeInTheDocument();
    expect(screen.queryByTestId('policy-item-101')).toBeNull();
    await waitFor(() =>
      expect(screen.queryByTestId('policy-pagination-error')).toBeNull(),
    );
    const calls = listCallsOf(server.fetch);
    expect(paramsOf(calls[calls.length - 1]).get('page')).toBe('2');
  });

  it('每页条数切换：回第 1 页重查；50 条时收敛单页精简态', async () => {
    const server = makeServer({ items: ALL_ITEMS });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<Policy />);

    await screen.findByTestId('policy-item-101');

    await user.selectOptions(screen.getByTestId('pagination-size'), '10');
    // 条数切换走「保留数据」通道：pageSize 态变即改指示，条目待响应整体替换——以替换完成为锚
    // （@10 第 1 页 = 101 + 200..208，边界外首条为 209）
    await waitFor(() => expect(screen.queryByTestId('policy-item-209')).toBeNull());
    expect(screen.getByTestId('policy-item-208')).toBeInTheDocument();
    expect(screen.getByTestId('pagination-page-indicator')).toHaveTextContent(
      '第 1 / 5 页',
    );
    let calls = listCallsOf(server.fetch);
    expect(paramsOf(calls[calls.length - 1]).get('page')).toBe('1');
    expect(paramsOf(calls[calls.length - 1]).get('size')).toBe('10');

    // 45 条切 50：单页精简态（无翻页按钮，条数选择保留）
    await user.selectOptions(screen.getByTestId('pagination-size'), '50');
    expect(await screen.findByTestId('policy-item-243')).toBeInTheDocument();
    expect(screen.getByTestId('pagination-page-indicator')).toHaveTextContent(
      '第 1 / 1 页',
    );
    expect(screen.queryByTestId('pagination-prev')).toBeNull();
    expect(screen.queryByTestId('pagination-next')).toBeNull();
    expect(screen.getByTestId('pagination-size')).toBeInTheDocument();
    calls = listCallsOf(server.fetch);
    expect(paramsOf(calls[calls.length - 1]).get('size')).toBe('50');
  });
});

describe('Policy 政策时事页 · 筛选联动（V2.3 T205：L1 静态下拉 + 源下拉）', () => {
  it('行业筛选（L1 口径）：汽车命中 main（第 101 条），回第 1 页 + industry 参数 + 清空已选详情', async () => {
    const server = makeServer({ items: ALL_ITEMS, detail: DETAIL_WITH_EVENT });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<Policy />);

    await screen.findByTestId('policy-item-101');
    // 先选中详情再切行业：详情清空（现状行为保留）
    await user.click(screen.getByTestId('policy-item-101'));
    expect(await screen.findByTestId('policy-detail')).toBeInTheDocument();

    await user.click(screen.getByTestId('pagination-page-2'));
    expect(await screen.findByTestId('policy-item-220')).toBeInTheDocument();

    await user.selectOptions(
      screen.getByTestId('policy-industry-filter'),
      '汽车',
    );

    expect(await screen.findByTestId('policy-item-101')).toBeInTheDocument();
    expect(screen.getByTestId('pagination-total')).toHaveTextContent('共 1 条');
    expect(screen.getByTestId('pagination-page-indicator')).toHaveTextContent(
      '第 1 / 1 页',
    );
    expect(screen.getByTestId('policy-detail-empty')).toBeInTheDocument();

    const calls = listCallsOf(server.fetch);
    const last = paramsOf(calls[calls.length - 1]);
    expect(last.get('industry')).toBe('汽车');
    expect(last.get('page')).toBe('1');
  });

  it('行业筛选：电子收缩到 44 条（subIndustry 徽章为展示面，筛选为 L1 枚举）', async () => {
    const server = makeServer({ items: ALL_ITEMS });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<Policy />);

    await screen.findByTestId('policy-item-101');

    await user.selectOptions(screen.getByTestId('policy-industry-filter'), '电子');

    expect(await screen.findByTestId('policy-item-200')).toBeInTheDocument();
    expect(screen.queryByTestId('policy-item-101')).toBeNull();
    expect(screen.getByTestId('pagination-total')).toHaveTextContent('共 44 条');
    const last = paramsOf(listCallsOf(server.fetch)[listCallsOf(server.fetch).length - 1]);
    expect(last.get('industry')).toBe('电子');
  });

  it('行业下拉静态枚举：31 申万 + 监管·政策 容器全量可选（过滤态不收缩）', async () => {
    const server = makeServer({ items: ALL_ITEMS });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<Policy />);

    await screen.findByTestId('policy-item-101');
    await user.selectOptions(screen.getByTestId('policy-industry-filter'), '电子');
    await screen.findByTestId('policy-item-200');

    const select = screen.getByTestId('policy-industry-filter');
    expect(within(select).getByRole('option', { name: '全部行业' })).toBeInTheDocument();
    // 当前页数据不含银行/监管·政策，静态枚举仍可选（旧响应聚合字典收缩问题消除）
    expect(within(select).getByRole('option', { name: '银行' })).toBeInTheDocument();
    expect(within(select).getByRole('option', { name: '监管·政策' })).toBeInTheDocument();
    expect(within(select).getAllByRole('option')).toHaveLength(33); // 全部 + 32 枚举
  });

  it('源筛选：下拉加载政策/宏观类源（快讯源不入列），选择发送 sourceCode 参数 + total 收缩', async () => {
    const server = makeServer({ items: ALL_ITEMS });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<Policy />);

    await screen.findByTestId('policy-item-101');
    const select = await screen.findByTestId('policy-source-filter');
    // 政策 scope 内源：政策 2 + 宏观 1（快讯 jin10_flash 不入列）
    expect(within(select).getAllByRole('option')).toHaveLength(4);
    expect(within(select).getByRole('option', { name: '中国政府网·政策' })).toBeInTheDocument();
    expect(within(select).getByRole('option', { name: '国家统计局·最新发布' })).toBeInTheDocument();
    expect(within(select).queryByRole('option', { name: '金十数据·快讯' })).toBeNull();

    await user.selectOptions(select, 'gov_policy');

    expect(await screen.findByTestId('policy-item-101')).toBeInTheDocument();
    expect(screen.queryByTestId('policy-item-200')).toBeNull();
    expect(screen.getByTestId('pagination-total')).toHaveTextContent('共 1 条');
    const last = paramsOf(listCallsOf(server.fetch)[listCallsOf(server.fetch).length - 1]);
    expect(last.get('sourceCode')).toBe('gov_policy');
    expect(last.get('page')).toBe('1');
  });

  it('源下拉降级：/info-sources 失败 → 仅「全部源」，列表主路径不受阻', async () => {
    const server = makeServer({ items: ALL_ITEMS, infoSources: 'fail' });
    vi.stubGlobal('fetch', server.fetch);
    render(<Policy />);

    expect(await screen.findByTestId('policy-item-101')).toBeInTheDocument();
    const select = screen.getByTestId('policy-source-filter');
    expect(within(select).getAllByRole('option')).toHaveLength(1);
    expect(within(select).getByRole('option', { name: '全部源' })).toBeInTheDocument();
  });

  it('时间窗切换：近 30 天 → days=30 + page=1 重查，分段高亮切换', async () => {
    const server = makeServer({ items: ALL_ITEMS });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<Policy />);

    await screen.findByTestId('policy-item-101');
    expect(screen.getByTestId('policy-days-7').className).toContain('bg-primary');
    expect(screen.getByTestId('policy-days-30').className).not.toContain(
      'bg-primary',
    );

    await user.click(screen.getByTestId('policy-days-30'));
    expect(await screen.findByTestId('policy-item-101')).toBeInTheDocument();
    expect(screen.getByTestId('policy-days-30').className).toContain('bg-primary');
    expect(screen.getByTestId('policy-days-7').className).not.toContain(
      'bg-primary',
    );

    const calls = listCallsOf(server.fetch);
    const last = paramsOf(calls[calls.length - 1]);
    expect(last.get('days')).toBe('30');
    expect(last.get('page')).toBe('1');
  });

  it('关键词搜索：输入「新能源汽车」点搜索 → keyword + page=1，total 收缩', async () => {
    const server = makeServer({ items: ALL_ITEMS });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<Policy />);

    await screen.findByTestId('policy-item-218');

    await user.type(screen.getByTestId('policy-keyword-input'), '新能源汽车');
    await user.click(screen.getByTestId('policy-keyword-search'));

    await waitFor(() =>
      expect(screen.getByTestId('pagination-total')).toHaveTextContent('共 1 条'),
    );
    expect(screen.getByTestId('policy-item-101')).toBeInTheDocument();
    expect(screen.queryByTestId('policy-item-200')).toBeNull();
    const calls = listCallsOf(server.fetch);
    const last = paramsOf(calls[calls.length - 1]);
    expect(last.get('keyword')).toBe('新能源汽车');
    expect(last.get('page')).toBe('1');
  });

  it('Enter 触发搜索等价于点击搜索按钮', async () => {
    const server = makeServer({ items: ALL_ITEMS });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<Policy />);

    await screen.findByTestId('policy-item-101');
    await user.type(screen.getByTestId('policy-keyword-input'), '锂电池{Enter}');

    expect(await screen.findByTestId('policy-item-101')).toBeInTheDocument();
    const calls = listCallsOf(server.fetch);
    expect(paramsOf(calls[calls.length - 1]).get('keyword')).toBe('锂电池');
  });

  it('关键词 <2 字符：搜索按钮禁用 + title 提示，不发请求（按钮/Enter 均拦截）', async () => {
    const server = makeServer({ items: ALL_ITEMS });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<Policy />);

    await screen.findByTestId('policy-item-101');
    const callsBefore = listCallsOf(server.fetch).length;

    await user.type(screen.getByTestId('policy-keyword-input'), '半');
    const search = screen.getByTestId('policy-keyword-search');
    expect(search).toBeDisabled();
    expect(search).toHaveAttribute('title', '至少输入 2 个字符');

    await user.click(search);
    await user.type(screen.getByTestId('policy-keyword-input'), '{Enter}');
    await user.type(screen.getByTestId('policy-keyword-input'), '{SelectAll}');

    expect(listCallsOf(server.fetch).length).toBe(callsBefore);
  });

  it('空输入提交 = 清除关键词回全量', async () => {
    const server = makeServer({ items: ALL_ITEMS });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<Policy />);

    await screen.findByTestId('policy-item-101');
    await user.type(screen.getByTestId('policy-keyword-input'), '新能源汽车');
    await user.click(screen.getByTestId('policy-keyword-search'));
    expect(await screen.findByTestId('pagination-total')).toHaveTextContent(
      '共 1 条',
    );

    // 清空输入再提交：keyword 缺席，回全量
    await user.clear(screen.getByTestId('policy-keyword-input'));
    await user.click(screen.getByTestId('policy-keyword-search'));
    expect(await screen.findByTestId('policy-item-218')).toBeInTheDocument();
    expect(screen.getByTestId('pagination-total')).toHaveTextContent('共 45 条');
    const calls = listCallsOf(server.fetch);
    expect(paramsOf(calls[calls.length - 1]).get('keyword')).toBeNull();
  });

  it('四条件组合：keyword × industry × sourceCode × days 一次请求', async () => {
    const server = makeServer({ items: ALL_ITEMS });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<Policy />);

    await screen.findByTestId('policy-item-101');
    await user.click(screen.getByTestId('policy-days-30'));
    await screen.findByTestId('policy-item-101');
    await user.selectOptions(screen.getByTestId('policy-industry-filter'), '汽车');
    await screen.findByTestId('policy-item-101');
    await user.selectOptions(screen.getByTestId('policy-source-filter'), 'gov_policy');
    await screen.findByTestId('policy-item-101');
    await user.type(screen.getByTestId('policy-keyword-input'), '新能源汽车');
    await user.click(screen.getByTestId('policy-keyword-search'));
    await screen.findByTestId('policy-item-101');

    const calls = listCallsOf(server.fetch);
    const last = paramsOf(calls[calls.length - 1]);
    expect(last.get('days')).toBe('30');
    expect(last.get('industry')).toBe('汽车');
    expect(last.get('sourceCode')).toBe('gov_policy');
    expect(last.get('keyword')).toBe('新能源汽车');
    expect(last.get('page')).toBe('1');
  });
});

describe('Policy 政策时事页 · 空态与防御', () => {
  it('关键词无结果：区分性空态 + 清除筛选 CTA，分页条整条隐藏', async () => {
    const server = makeServer({ items: ALL_ITEMS });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<Policy />);

    await screen.findByTestId('policy-item-101');
    await user.type(screen.getByTestId('policy-keyword-input'), '量子计算机');
    await user.click(screen.getByTestId('policy-keyword-search'));

    const empty = await screen.findByTestId('policy-list-empty');
    expect(empty).toHaveTextContent('未找到包含「量子计算机」的政策');
    expect(empty).toHaveTextContent('可调整关键词、行业、源或时间窗后重试');
    expect(screen.getByTestId('policy-clear-filters')).toBeInTheDocument();
    expect(screen.queryByTestId('pagination-root')).toBeNull();
  });

  it('清除筛选 CTA：重置 keyword/industry/sourceCode/days + 回第 1 页 + 清空详情', async () => {
    const server = makeServer({ items: ALL_ITEMS, detail: DETAIL_WITH_EVENT });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<Policy />);

    await screen.findByTestId('policy-item-101');
    await user.click(screen.getByTestId('policy-item-101'));
    await screen.findByTestId('policy-detail');

    // 叠满筛选后无结果（银行 + 未知源 + 量子计算机）
    await user.click(screen.getByTestId('policy-days-30'));
    await screen.findByTestId('policy-item-101');
    await user.selectOptions(screen.getByTestId('policy-industry-filter'), '银行');
    await screen.findByTestId('policy-list-empty');
    await user.type(screen.getByTestId('policy-keyword-input'), '量子计算机');
    await user.click(screen.getByTestId('policy-keyword-search'));
    await screen.findByTestId('policy-clear-filters');

    await user.click(screen.getByTestId('policy-clear-filters'));

    // 回全量第 1 页，筛选与详情全部复位
    expect(await screen.findByTestId('policy-item-218')).toBeInTheDocument();
    expect(screen.getByTestId('pagination-total')).toHaveTextContent('共 45 条');
    expect(screen.getByTestId('policy-keyword-input')).toHaveValue('');
    expect(screen.getByTestId('policy-industry-filter')).toHaveValue('');
    expect(screen.getByTestId('policy-source-filter')).toHaveValue('');
    expect(screen.getByTestId('policy-days-7').className).toContain('bg-primary');
    expect(screen.getByTestId('policy-detail-empty')).toBeInTheDocument();
    const calls = listCallsOf(server.fetch);
    const last = paramsOf(calls[calls.length - 1]);
    expect(last.get('days')).toBe('7');
    expect(last.get('industry')).toBeNull();
    expect(last.get('sourceCode')).toBeNull();
    expect(last.get('keyword')).toBeNull();
    expect(last.get('page')).toBe('1');
  });

  it('无筛选无结果：动态空态文案（最近 7 天），无清除筛选 CTA', async () => {
    const server = makeServer({ items: [] });
    vi.stubGlobal('fetch', server.fetch);
    render(<Policy />);

    expect(await screen.findByTestId('policy-list-empty')).toHaveTextContent(
      '最近 7 天暂无政策条目',
    );
    expect(screen.queryByTestId('policy-clear-filters')).toBeNull();
    expect(screen.queryByTestId('pagination-root')).toBeNull();
  });

  it('空页防御回退：浏览期间数据收缩 → 静默重发末页，不渲染空页', async () => {
    const server = makeServer({ items: ALL_ITEMS });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<Policy />);

    await screen.findByTestId('policy-item-101');
    await user.click(screen.getByTestId('pagination-page-2'));
    expect(await screen.findByTestId('policy-item-220')).toBeInTheDocument();

    // 服务端数据 45 → 21（第 3 页消失）：UI 仍按旧 total 渲染第 3 页入口
    server.setItems([POLICY_A, ...REST.slice(0, 20)]);
    await user.click(screen.getByTestId('pagination-page-3'));

    // 期望：page=3 返回空 + total=21 → 静默自动重发 ceil(21/20)=2 页
    expect(await screen.findByTestId('policy-item-219')).toBeInTheDocument();
    expect(screen.getByTestId('pagination-page-indicator')).toHaveTextContent(
      '第 2 / 2 页',
    );
    expect(screen.getByTestId('pagination-total')).toHaveTextContent('共 21 条');

    const calls = listCallsOf(server.fetch);
    expect(paramsOf(calls[calls.length - 2]).get('page')).toBe('3');
    expect(paramsOf(calls[calls.length - 1]).get('page')).toBe('2');
  });

  it('在途竞态：筛选变更中止翻页，旧响应不覆盖新结果', async () => {
    const server = makeServer({ items: ALL_ITEMS });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<Policy />);

    await screen.findByTestId('policy-item-101');

    // 翻第 2 页挂起
    const d = deferred<ReturnType<typeof ok>>();
    server.fetch.mockImplementationOnce(() => d.promise);
    await user.click(screen.getByTestId('pagination-page-2'));
    expect(await screen.findByTestId('policy-page-loading')).toBeInTheDocument();

    // 筛选变更中止翻页、走骨架新查询（电子 44 条）
    await user.selectOptions(screen.getByTestId('policy-industry-filter'), '电子');
    expect(await screen.findByTestId('policy-item-200')).toBeInTheDocument();
    expect(screen.getByTestId('pagination-total')).toHaveTextContent('共 44 条');

    // 旧翻页响应迟到：含独有标记条目 999，不得覆盖筛选结果
    d.resolve(
      ok({ policies: [{ ...POLICY_A, id: 999 }], total: 45, page: 2, size: 20, basis: 'policy-scope-v1' }),
    );
    await waitFor(() =>
      expect(screen.queryByTestId('policy-page-loading')).toBeNull(),
    );
    expect(screen.queryByTestId('policy-item-999')).toBeNull();
    expect(screen.getByTestId('pagination-total')).toHaveTextContent('共 44 条');
    expect(screen.getByTestId('policy-item-200')).toBeInTheDocument();
  });
});

describe('Policy 政策时事页 · 详情换面（V2.3 T205：relatedEvents 承接 aiTendency）', () => {
  it('点击条目：详情 + 回联标的 chips（跳标的详情）+ 关联事件（方向徽章利好/重要度/事件流下钻）', async () => {
    const server = makeServer({ items: ALL_ITEMS, detail: DETAIL_WITH_EVENT });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<Policy />);

    await screen.findByTestId('policy-item-101');
    await user.click(screen.getByTestId('policy-item-101'));

    const detail = await screen.findByTestId('policy-detail');
    expect(detail).toHaveTextContent('充电基础设施与锂电池研发补贴');
    expect(detail).toHaveTextContent('来源：中国政府网·政策');
    expect(screen.getByTestId('policy-detail-category')).toHaveTextContent('汽车');
    expect(screen.getByTestId('policy-source-url')).toHaveAttribute(
      'href',
      'http://gov.cn/policy-101',
    );

    // 回联标的 chips：站内跳标的详情（hash 路由锚点）
    const subjectChip = screen.getByTestId('policy-matched-SZ300750');
    expect(subjectChip).toHaveTextContent('宁德时代');
    expect(subjectChip).toHaveAttribute('href', '#/subjects/SZ300750');
    expect(screen.getByTestId('policy-matched-SH600884')).toHaveTextContent('杉杉股份');

    // 关联 L2 政策发布事件：direction 徽章承接倾向 + 事件流下钻
    const event = screen.getByTestId('policy-related-event-55');
    expect(within(event).getByTestId('policy-related-event-direction-55')).toHaveTextContent(
      '利好',
    );
    expect(event).toHaveTextContent('政策发布');
    expect(event).toHaveTextContent('重要度 高');
    expect(event).toHaveTextContent('新能源车补贴延续，产业链受益');
    expect(
      within(event).getByTestId('policy-related-event-link-55'),
    ).toHaveAttribute('href', '#/events?focus=55');
  });

  it('aiTendency 退役：倾向徽章不再渲染（由 relatedEvents direction 承接）', async () => {
    const server = makeServer({ items: ALL_ITEMS, detail: DETAIL_WITH_EVENT });
    vi.stubGlobal('fetch', server.fetch);
    render(<Policy />);

    await screen.findByTestId('policy-item-101');
    // 旧倾向徽章 testid（policy-tendency / tendency-1）全站零出现
    expect(screen.queryByTestId('policy-tendency')).toBeNull();
    expect(screen.queryByTestId('tendency-1')).toBeNull();
  });

  it('方向徽章 3 态：利好(BULLISH 红) / 利空(BEARISH 绿) / 中性(NEUTRAL 灰)', async () => {
    const cases = [
      { id: 101, direction: 'BULLISH', label: '利好' },
      { id: 102, direction: 'BEARISH', label: '利空' },
      { id: 103, direction: 'NEUTRAL', label: '中性' },
    ];
    const items: PolicyView[] = cases.map((c) => ({
      ...POLICY_A,
      id: c.id,
      title: `政策${c.id}`,
      matchedSubjects: [],
    }));
    const detailById: Record<number, PolicyDetailView> = {};
    for (const c of cases) {
      detailById[c.id] = {
        ...DETAIL_WITH_EVENT,
        id: c.id,
        matchedSubjects: [],
        relatedEvents: [
          {
            id: c.id + 500,
            eventType: 'POLICY_RELEASE',
            summary: null,
            direction: c.direction,
            importance: 'MEDIUM',
            eventDate: '2026-09-19',
          },
        ],
      };
    }
    const server = makeServer({ items, detailById });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<Policy />);

    await screen.findByTestId('policy-item-101');
    for (const c of cases) {
      await user.click(screen.getByTestId(`policy-item-${c.id}`));
      const badge = await screen.findByTestId(
        `policy-related-event-direction-${c.id + 500}`,
      );
      expect(badge).toHaveTextContent(c.label);
    }
  });

  it('relatedEvents 空数组：关联事件区不渲染（无事件不占位）', async () => {
    const server = makeServer({
      items: ALL_ITEMS,
      detail: { ...DETAIL_WITH_EVENT, relatedEvents: [] },
    });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<Policy />);

    await screen.findByTestId('policy-item-101');
    await user.click(screen.getByTestId('policy-item-101'));

    expect(await screen.findByTestId('policy-detail')).toBeInTheDocument();
    expect(screen.queryByTestId('policy-related-events')).toBeNull();
  });

  it('列表 500：整页错误 + 重试恢复（重试回第 1 页）', async () => {
    const server = makeServer({ listCode: 50000 });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<Policy />);

    expect(await screen.findByTestId('policy-list-error')).toHaveTextContent(
      '服务异常',
    );

    server.fetch.mockImplementationOnce(async (url: string) => {
      const q = new URL(String(url), 'http://x').searchParams;
      return ok({
        policies: ALL_ITEMS.slice(0, Number(q.get('size') ?? '20')),
        total: ALL_ITEMS.length,
        page: Number(q.get('page') ?? '1'),
        size: Number(q.get('size') ?? '20'),
        basis: 'policy-scope-v1',
      });
    });
    await user.click(screen.getByTestId('policy-list-retry'));

    expect(await screen.findByTestId('policy-item-101')).toBeInTheDocument();
    expect(screen.getByTestId('pagination-total')).toHaveTextContent('共 45 条');
  });

  it('详情 30040(404) 政策不存在：展示友好提示与重试', async () => {
    const server = makeServer({ items: ALL_ITEMS, detailCode: 30040 });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<Policy />);

    await screen.findByTestId('policy-item-101');
    await user.click(screen.getByTestId('policy-item-101'));

    expect(await screen.findByTestId('policy-detail-error')).toHaveTextContent(
      '政策条目不存在或已下线',
    );
    expect(screen.getByTestId('policy-detail-retry')).toBeInTheDocument();
  });
});
