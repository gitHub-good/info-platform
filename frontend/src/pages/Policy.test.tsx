import { cleanup, render, screen, within, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Policy } from '@/pages/Policy';
import type {
  AiTendencyCode,
  PolicyDetailView,
  PolicyListView,
  PolicyView,
} from '@/types/policy';

// —— fetch mock：GET /policies（游标分页 + 行业过滤）/ GET /policies/{id}（详情） —— #

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

interface StoreOpts {
  /** 分页序列：按列表请求次序依次返回（首页=pages[0]）。 */
  pages?: PolicyListView[];
  /** 详情固定视图（detailById 未命中时使用）。 */
  detail?: PolicyDetailView;
  /** 按 id 返回详情视图（用于同一测试覆盖多种 aiTendency）。 */
  detailById?: Record<number, PolicyDetailView>;
  /** 强制列表请求返回该错误码。 */
  listCode?: number;
  /** 强制详情请求返回该错误码。 */
  detailCode?: number;
}

/** 构造状态化 fetch mock：列表按次序分页，详情按 id 路由。 */
function makeStore(opts: StoreOpts = {}) {
  let listCalls = 0;
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET';
    const path = String(url);
    if (method !== 'GET') return fail(50000);

    // 详情：/api/v1/policies/{id}
    const d = path.match(/\/policies\/(\d+)$/);
    if (d) {
      if (opts.detailCode) return fail(opts.detailCode);
      const id = Number(d[1]);
      const byId = opts.detailById?.[id];
      if (byId) return ok(byId);
      return ok(opts.detail ?? null);
    }

    // 列表：/api/v1/policies?...
    if (/\/policies(\?.*)?$/.test(path) && !/\/policies\//.test(path)) {
      if (opts.listCode) return fail(opts.listCode);
      const pages = opts.pages ?? [];
      const idx = Math.min(listCalls, pages.length - 1);
      listCalls++;
      return ok(pages[idx] ?? { policies: [], nextCursor: null });
    }
    return fail(50000);
  });
  return { fetch };
}

/** 取列表请求调用（排除详情请求），便于断言 query 参数。 */
function listCallsOf(
  fetchMock: ReturnType<typeof makeStore>['fetch'],
): { url: string; init?: RequestInit }[] {
  return fetchMock.mock.calls
    .map((c) => ({ url: String(c[0]), init: c[1] }))
    .filter((c) => /\/policies(\?.*)?$/.test(c.url) && !/\/policies\//.test(c.url));
}

// —— fixtures —— #

const POLICY_A: PolicyView = {
  id: 101,
  title: '关于促进新能源汽车产业高质量发展的若干政策',
  source: '国务院',
  publishedAt: '2026-09-19',
  summary: '加大充电基础设施与锂电池研发补贴，利好产业链上下游。',
  relatedIndustries: ['新能源', '锂电池'],
};
const POLICY_B: PolicyView = {
  id: 102,
  title: '半导体国产替代专项支持计划',
  source: '工信部',
  publishedAt: '2026-09-18',
  summary: '设立专项基金支持先进制程与设备国产化。',
  relatedIndustries: ['半导体'],
};

const PAGE_FULL: PolicyListView = {
  policies: [POLICY_A, POLICY_B],
  nextCursor: null,
};

const DETAIL_BULL: PolicyDetailView = {
  id: 101,
  title: POLICY_A.title,
  source: '国务院',
  publishedAt: '2026-09-19',
  summary: '加大充电基础设施与锂电池研发补贴，利好产业链上下游。',
  relatedIndustries: ['新能源', '锂电池'],
  sourceUrl: 'http://gov.cn/policy-101',
  aiTendency: 1,
  relatedSubjects: [
    { subjectCode: 'SZ300750', subjectName: '宁德时代', industry: '新能源' },
    { subjectCode: 'SH600884', subjectName: '杉杉股份', industry: '锂电池' },
  ],
};

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  localStorage.clear();
  window.location.hash = '';
});

describe('Policy 政策时事页', () => {
  it('渲染政策列表（标题/来源/时间/摘要/关联行业标签），详情区初始为空态', async () => {
    const store = makeStore({ pages: [PAGE_FULL] });
    vi.stubGlobal('fetch', store.fetch);
    render(<Policy />);

    // 两条条目
    const itemA = await screen.findByTestId('policy-item-101');
    expect(itemA).toHaveTextContent('新能源汽车');
    expect(screen.getByTestId('policy-item-102')).toBeInTheDocument();

    // 来源 / 时间 / 摘要
    expect(itemA).toHaveTextContent('国务院');
    expect(itemA).toHaveTextContent('2026-09-19');
    expect(itemA).toHaveTextContent('充电基础设施');
    // 关联行业标签
    expect(
      within(itemA).getByTestId('policy-item-101-industry-新能源'),
    ).toHaveTextContent('新能源');
    expect(
      within(itemA).getByTestId('policy-item-101-industry-锂电池'),
    ).toBeInTheDocument();

    // 详情空态（未选择）
    expect(screen.getByTestId('policy-detail-empty')).toBeInTheDocument();
    // 无「加载更多」（nextCursor=null）
    expect(screen.queryByTestId('policy-load-more')).toBeNull();
    // 首页请求带 days=7
    const calls = listCallsOf(store.fetch);
    expect(calls.length).toBeGreaterThanOrEqual(1);
    expect(new URL(calls[0].url, 'http://x').searchParams.get('days')).toBe('7');
  });

  it('切换行业过滤：带 industry 参数重新拉首页并重置详情', async () => {
    const store = makeStore({ pages: [PAGE_FULL] });
    vi.stubGlobal('fetch', store.fetch);
    const user = userEvent.setup();
    render(<Policy />);

    await screen.findByTestId('policy-item-101');

    // 选项来自 relatedIndustries 去重（新能源/锂电池/半导体）
    await user.selectOptions(
      screen.getByTestId('policy-industry-filter'),
      '新能源',
    );

    // 重新拉首页，URL 含 industry=新能源；无 cursor
    const calls = listCallsOf(store.fetch);
    const filtered = calls.filter((c) =>
      new URL(c.url, 'http://x').searchParams.get('industry'),
    );
    expect(filtered.length).toBeGreaterThanOrEqual(1);
    expect(new URL(filtered[0].url, 'http://x').searchParams.get('industry')).toBe(
      '新能源',
    );
    expect(new URL(filtered[0].url, 'http://x').searchParams.get('cursor')).toBeNull();
  });

  it('游标分页：nextCursor 存在时显示「加载更多」，点击追加下一页并带 cursor', async () => {
    const page1: PolicyListView = {
      policies: [
        {
          id: 1,
          title: '政策A',
          source: 's',
          publishedAt: '2026-09-19',
          summary: 'a',
          relatedIndustries: ['新能源'],
        },
      ],
      nextCursor: 1,
    };
    const page2: PolicyListView = {
      policies: [
        {
          id: 2,
          title: '政策B',
          source: 's',
          publishedAt: '2026-09-18',
          summary: 'b',
          relatedIndustries: ['半导体'],
        },
      ],
      nextCursor: null,
    };
    const store = makeStore({ pages: [page1, page2] });
    vi.stubGlobal('fetch', store.fetch);
    const user = userEvent.setup();
    render(<Policy />);

    await screen.findByTestId('policy-item-1');
    expect(screen.getByTestId('policy-load-more')).toBeInTheDocument();

    await user.click(screen.getByTestId('policy-load-more'));

    // 第二条追加，第一条仍在
    expect(await screen.findByTestId('policy-item-2')).toBeInTheDocument();
    expect(screen.getByTestId('policy-item-1')).toBeInTheDocument();
    // 无下一页 → 按钮消失
    await waitFor(() =>
      expect(screen.queryByTestId('policy-load-more')).toBeNull(),
    );

    // 第二次列表请求带 cursor=1
    const calls = listCallsOf(store.fetch);
    expect(calls.length).toBeGreaterThanOrEqual(2);
    expect(new URL(calls[1].url, 'http://x').searchParams.get('cursor')).toBe('1');
  });

  it('加载更多失败：保留既有条目，按钮变重试入口，重试成功后追加（不清列表）', async () => {
    const page1: PolicyListView = {
      policies: [
        {
          id: 1,
          title: '政策A',
          source: 's',
          publishedAt: '2026-09-19',
          summary: 'a',
          relatedIndustries: ['新能源'],
        },
      ],
      nextCursor: 1,
    };
    const page2: PolicyListView = {
      policies: [
        {
          id: 2,
          title: '政策B',
          source: 's',
          publishedAt: '2026-09-18',
          summary: 'b',
          relatedIndustries: ['半导体'],
        },
      ],
      nextCursor: null,
    };
    const store = makeStore({ pages: [page1, page2] });
    vi.stubGlobal('fetch', store.fetch);
    const user = userEvent.setup();
    render(<Policy />);

    await screen.findByTestId('policy-item-1');

    // 翻页失败一次：既有条目保留 + 错误提示 + 按钮可重试（不被整页错误块替换）
    store.fetch.mockImplementationOnce(async () => fail(50000));
    await user.click(screen.getByTestId('policy-load-more'));
    expect(await screen.findByTestId('policy-more-error')).toHaveTextContent('服务异常');
    expect(screen.getByTestId('policy-item-1')).toBeInTheDocument();
    expect(screen.queryByTestId('policy-list-error')).toBeNull();
    expect(screen.getByTestId('policy-load-more')).toBeInTheDocument();

    // 重试成功：追加下一页，错误清除
    await user.click(screen.getByTestId('policy-load-more'));
    expect(await screen.findByTestId('policy-item-2')).toBeInTheDocument();
    expect(screen.getByTestId('policy-item-1')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByTestId('policy-more-error')).toBeNull());
  });

  it('行业下拉缓存全量集：选中某行业过滤后下拉仍含全部行业', async () => {
    const pageAll: PolicyListView = {
      policies: [
        {
          id: 1,
          title: '政策A',
          source: 's',
          publishedAt: '2026-09-19',
          summary: 'a',
          relatedIndustries: ['新能源', '半导体'],
        },
        {
          id: 2,
          title: '政策B',
          source: 's',
          publishedAt: '2026-09-18',
          summary: 'b',
          relatedIndustries: ['半导体'],
        },
      ],
      nextCursor: null,
    };
    const pageFiltered: PolicyListView = {
      policies: [
        {
          id: 3,
          title: '政策C',
          source: 's',
          publishedAt: '2026-09-17',
          summary: 'c',
          relatedIndustries: ['新能源'],
        },
      ],
      nextCursor: null,
    };
    const store = makeStore({ pages: [pageAll, pageFiltered] });
    vi.stubGlobal('fetch', store.fetch);
    const user = userEvent.setup();
    render(<Policy />);

    await screen.findByTestId('policy-item-1');

    // 选中「新能源」：过滤结果只剩新能源条目，但下拉选项仍含「半导体」
    await user.selectOptions(screen.getByTestId('policy-industry-filter'), '新能源');
    await screen.findByTestId('policy-item-3');
    const select = screen.getByTestId('policy-industry-filter');
    expect(within(select).getByRole('option', { name: '新能源' })).toBeInTheDocument();
    expect(within(select).getByRole('option', { name: '半导体' })).toBeInTheDocument();
  });

  it('点击条目：拉详情 + 关联自选标的表 + 倾向徽章（利好）', async () => {
    const store = makeStore({ pages: [PAGE_FULL], detail: DETAIL_BULL });
    vi.stubGlobal('fetch', store.fetch);
    const user = userEvent.setup();
    render(<Policy />);

    await screen.findByTestId('policy-item-101');
    await user.click(screen.getByTestId('policy-item-101'));

    // 详情标题 + 摘要 + 原文链接
    const detail = await screen.findByTestId('policy-detail');
    expect(detail).toHaveTextContent('充电基础设施与锂电池研发补贴');
    expect(screen.getByTestId('policy-source-url')).toHaveAttribute(
      'href',
      'http://gov.cn/policy-101',
    );

    // 倾向徽章：利好（A 股惯例红）
    expect(screen.getByTestId('policy-tendency')).toHaveTextContent('利好');

    // 关联自选标的表：代码 / 名称 / 行业
    const row = screen.getByTestId('policy-related-SZ300750');
    expect(within(row).getByText('宁德时代')).toBeInTheDocument();
    expect(within(row).getByText('新能源')).toBeInTheDocument();
    expect(screen.getByTestId('policy-related-SH600884')).toHaveTextContent('杉杉股份');
  });

  it('倾向徽章 4 态：利好(1)/利空(2)/中性(3)/未判(0)', async () => {
    const cases: { id: number; tendency: AiTendencyCode; label: string }[] = [
      { id: 101, tendency: 1, label: '利好' },
      { id: 102, tendency: 2, label: '利空' },
      { id: 103, tendency: 3, label: '中性' },
      { id: 104, tendency: 0, label: '待判' },
    ];
    const pages: PolicyView[] = cases.map((c) => ({
      id: c.id,
      title: `政策${c.id}`,
      source: 's',
      publishedAt: '2026-09-19',
      summary: `摘要${c.id}`,
      relatedIndustries: ['新能源'],
    }));
    const detailById: Record<number, PolicyDetailView> = {};
    for (const c of cases) {
      detailById[c.id] = {
        id: c.id,
        title: `政策${c.id}`,
        source: 's',
        publishedAt: '2026-09-19',
        summary: `摘要${c.id}`,
        relatedIndustries: ['新能源'],
        sourceUrl: null,
        aiTendency: c.tendency,
        relatedSubjects: [],
      };
    }
    const store = makeStore({
      pages: [{ policies: pages, nextCursor: null }],
      detailById,
    });
    vi.stubGlobal('fetch', store.fetch);
    const user = userEvent.setup();
    render(<Policy />);

    await screen.findByTestId('policy-item-101');
    for (const c of cases) {
      await user.click(screen.getByTestId(`policy-item-${c.id}`));
      expect(await screen.findByTestId('policy-tendency')).toHaveTextContent(c.label);
    }
  });

  it('列表为空：展示空态文案，无「加载更多」', async () => {
    const store = makeStore({ pages: [{ policies: [], nextCursor: null }] });
    vi.stubGlobal('fetch', store.fetch);
    render(<Policy />);

    expect(await screen.findByTestId('policy-list-empty')).toHaveTextContent(
      '暂无政策条目',
    );
    expect(screen.queryByTestId('policy-load-more')).toBeNull();
    expect(screen.queryByTestId('policy-item-101')).toBeNull();
  });

  it('列表 500：展示错误与重试，重试后恢复', async () => {
    const store = makeStore({ listCode: 50000 });
    vi.stubGlobal('fetch', store.fetch);
    const user = userEvent.setup();
    render(<Policy />);

    expect(await screen.findByTestId('policy-list-error')).toHaveTextContent(
      '服务异常',
    );

    // 重试：覆盖下一次列表请求为成功
    store.fetch.mockImplementationOnce(async () => ok(PAGE_FULL));
    await user.click(screen.getByTestId('policy-list-retry'));

    expect(await screen.findByTestId('policy-item-101')).toBeInTheDocument();
  });

  it('详情 30040(404) 政策不存在：展示友好提示与重试', async () => {
    const store = makeStore({
      pages: [PAGE_FULL],
      detailCode: 30040,
    });
    vi.stubGlobal('fetch', store.fetch);
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
