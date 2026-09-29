import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NewsLibrary } from '@/pages/NewsLibrary';
import type { InfoSourcesView } from '@/types/infoSource';
import type { SubjectSummary } from '@/api/subject';
import type { NewsLibraryItem, NewsLibraryPagedView } from '@/types/newsItem';

// —— fetch mock：news-items 页码视图（URL 断言驱动）+ info-sources 分组视图（源下拉） ——

const ok = (data: unknown) => ({
  ok: true,
  status: 200,
  json: async () => ({ code: 0, msg: 'ok', data, traceId: 't' }),
});
const serverError = () => ({
  ok: false,
  status: 500,
  json: async () => ({ code: 500, msg: '内部错误', data: null, traceId: 't' }),
});

function itemOf(overrides: Partial<NewsLibraryItem> = {}): NewsLibraryItem {
  return {
    id: 1,
    sourceId: 7,
    sourceCode: 'jin10_flash',
    sourceName: '金十数据·快讯',
    title: '央行宣布降息50个基点',
    summary: '货币政策宽松信号，市场流动性预期改善',
    url: 'https://example.com/n1',
    author: null,
    publishedAt: '2026-09-22T01:31:00Z',
    fetchedAt: '2026-09-22T01:31:30Z',
    l0Result: 'PASS',
    l0Detail: null,
    l1Main: '银行',
    l1Confidence: 0.92,
    lowConfidence: false,
    nearDupMasterId: null,
    nearDupMasterUrl: null,
    matchedSubjects: [],
    ...overrides,
  };
}

function paged(items: NewsLibraryItem[], total = items.length): NewsLibraryPagedView {
  return { items, total, page: 1, size: 20 };
}

function sourcesView(): InfoSourcesView {
  return {
    groups: [
      {
        category: '快讯',
        sources: [
          {
            id: 7,
            sourceCode: 'jin10_flash',
            name: '金十数据·快讯',
            category: '快讯',
            adapterType: 'json_api',
            adapterRef: null,
            endpoint: 'https://example.com/j',
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
            intervalMinutes: 5,
            enabled: true,
            preset: true,
            deleted: false,
            today: {
              pollCount: 1,
              failCount: 0,
              newCount: 1,
              dupCount: 0,
            },
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
            createdAt: null,
            updatedAt: null,
          },
        ],
      },
    ],
    archived: [],
  };
}

/** 组装 fetch mock：news-items 返回态与 info-sources 返回态可注入。 */
function makeFetch(
  newsItems: () => unknown = () => ok(paged([itemOf()])),
  infoSources: () => unknown = () => ok(sourcesView()),
  subjectSearch: () => unknown = () => ok([]),
) {
  return vi.fn(async (url: string) => {
    const path = String(url);
    if (path.includes('/news-items')) return newsItems();
    if (path.includes('/info-sources')) return infoSources();
    if (path.includes('/subjects/search')) return subjectSearch();
    return ok(null);
  });
}

/** 标的搜索联想结果（SubjectPicker 数据源 mock；V3.1 标的筛选）。 */
function subjectOptions(): SubjectSummary[] {
  return [
    { id: 7, subjectCode: 'SH600519', name: '贵州茅台', market: 'A_SHARE', type: 1, industry: '白酒' },
    { id: 8, subjectCode: 'SZ300024', name: '机器人', market: 'A_SHARE', type: 1, industry: null },
  ];
}

function newsLibraryCalls(fetchMock: ReturnType<typeof makeFetch>): string[] {
  return fetchMock.mock.calls.map(String).filter((u) => u.includes('/news-items'));
}

afterEach(() => {
  vi.unstubAllGlobals();
  cleanup();
});

describe('资讯库页（M19 T161，REQ-20260926-16 拍板一）', () => {
  it('默认加载：请求 l0=PASS&page=1&size=20，行字段齐全（标题新窗口外链/源徽章/双时间/徽章/摘要截断）', async () => {
    const fetchMock = makeFetch();
    vi.stubGlobal('fetch', fetchMock);
    render(<NewsLibrary />);

    await screen.findByTestId('news-library-item-1');
    const calls = newsLibraryCalls(fetchMock);
    expect(calls[0]).toContain('page=1');
    expect(calls[0]).toContain('size=20');
    expect(calls[0]).toContain('l0=PASS');

    const title = screen.getByTestId('news-library-title-1');
    expect(title).toHaveAttribute('href', 'https://example.com/n1');
    expect(title).toHaveAttribute('target', '_blank');
    expect(screen.getByTestId('news-library-source-1')).toHaveTextContent('金十数据·快讯');
    expect(screen.getByTestId('news-library-published-1')).toBeInTheDocument();
    expect(screen.getByTestId('news-library-fetched-1')).toBeInTheDocument();
    const summary = screen.getByTestId('news-library-summary-1');
    expect(summary.className).toContain('line-clamp-2');
    expect(summary).toHaveAttribute('title', '货币政策宽松信号，市场流动性预期改善');
  });

  it('L0 徽章矩阵：PASS 绿「通过」/ NOISE 灰「噪音」带规则名 title / NEAR_DUP 琥珀「近重复」', async () => {
    vi.stubGlobal(
      'fetch',
      makeFetch(() =>
        ok(
          paged([
            itemOf({ id: 1, l0Result: 'PASS' }),
            itemOf({ id: 2, l0Result: 'NOISE', l0Detail: '推广', l1Main: null }),
            itemOf({ id: 3, l0Result: 'NEAR_DUP', l0Detail: 'hamming=3;edit=0.21' }),
          ]),
        ),
      ),
    );
    render(<NewsLibrary />);

    await screen.findByTestId('news-library-item-3');
    const pass = screen.getByTestId('news-library-l0-1');
    expect(pass).toHaveTextContent('通过');
    expect(pass.className).toContain('emerald');
    const noise = screen.getByTestId('news-library-l0-2');
    expect(noise).toHaveTextContent('噪音');
    expect(noise).toHaveAttribute('title', '推广');
    expect(noise.className).toContain('muted');
    const dup = screen.getByTestId('news-library-l0-3');
    expect(dup).toHaveTextContent('近重复');
    expect(dup).toHaveAttribute('title', 'hamming=3;edit=0.21');
    expect(dup.className).toContain('amber');
  });

  it('near_dup 主条外链：链接直达主条 url（新窗口）；主条缺失时链接降级隐藏、行不报错', async () => {
    vi.stubGlobal(
      'fetch',
      makeFetch(() =>
        ok(
          paged([
            itemOf({
              id: 5,
              l0Result: 'NEAR_DUP',
              nearDupMasterId: 4,
              nearDupMasterUrl: 'https://example.com/master',
            }),
            itemOf({
              id: 6,
              l0Result: 'NEAR_DUP',
              nearDupMasterId: 99,
              nearDupMasterUrl: null,
            }),
          ]),
        ),
      ),
    );
    render(<NewsLibrary />);

    await screen.findByTestId('news-library-item-6');
    const master = screen.getByTestId('news-library-master-5');
    expect(master).toHaveAttribute('href', 'https://example.com/master');
    expect(master).toHaveAttribute('target', '_blank');
    // 主条被清理：徽章仍在、链接不渲染（REQ 故事 3 场景 2）
    expect(screen.getByTestId('news-library-l0-6')).toHaveTextContent('近重复');
    expect(screen.queryByTestId('news-library-master-6')).toBeNull();
  });

  it('L1 徽章：35 枚举直读；lowConfidence 加「低置信」角标；null 呈「未分类」灰态', async () => {
    vi.stubGlobal(
      'fetch',
      makeFetch(() =>
        ok(
          paged([
            itemOf({ id: 1, l1Main: '电子', l1Confidence: 0.92 }),
            itemOf({ id: 2, l1Main: '市场·其他', l1Confidence: 0.3, lowConfidence: true }),
            itemOf({ id: 3, l1Main: null, l1Confidence: null }),
          ]),
        ),
      ),
    );
    render(<NewsLibrary />);

    await screen.findByTestId('news-library-item-3');
    expect(screen.getByTestId('news-library-l1-1')).toHaveTextContent('电子');
    expect(screen.queryByTestId('news-library-lowconf-1')).toBeNull();
    expect(screen.getByTestId('news-library-l1-2')).toHaveTextContent('市场·其他');
    expect(screen.getByTestId('news-library-lowconf-2')).toHaveTextContent('低置信');
    const unclassified = screen.getByTestId('news-library-l1-3');
    expect(unclassified).toHaveTextContent('未分类');
    expect(unclassified.className).toContain('muted');
  });

  it('L1 下拉为 35 枚举（31 申万 + 4 容器）+ 全部分类缺省项', async () => {
    vi.stubGlobal('fetch', makeFetch());
    render(<NewsLibrary />);

    await screen.findByTestId('news-library-item-1');
    const select = screen.getByTestId('news-library-l1-filter') as HTMLSelectElement;
    const options = Array.from(select.options).map((o) => o.value);
    expect(options).toContain('');
    expect(options).toContain('农林牧渔');
    expect(options).toContain('汽车');
    expect(options).toContain('宏观');
    expect(options).toContain('监管·政策');
    expect(options).toContain('国际');
    expect(options).toContain('市场·其他');
    expect(options.filter(Boolean)).toHaveLength(35);
  });

  it('L0 筛选切换：默认「有效」，切「噪音」回第 1 页并请求 l0=NOISE；切「全部」请求 l0=ALL', async () => {
    const fetchMock = makeFetch();
    vi.stubGlobal('fetch', fetchMock);
    render(<NewsLibrary />);
    await screen.findByTestId('news-library-item-1');

    await userEvent.click(screen.getByTestId('news-library-l0-noise'));
    await waitFor(() => {
      const last = newsLibraryCalls(fetchMock).at(-1) ?? '';
      expect(last).toContain('l0=NOISE');
      expect(last).toContain('page=1');
    });

    await userEvent.click(screen.getByTestId('news-library-l0-all'));
    await waitFor(() => {
      expect(newsLibraryCalls(fetchMock).at(-1) ?? '').toContain('l0=ALL');
    });
  });

  it('源筛选：下拉为活跃源清单（info-sources 复用），选择后请求 sourceId 并回第 1 页', async () => {
    const fetchMock = makeFetch();
    vi.stubGlobal('fetch', fetchMock);
    render(<NewsLibrary />);
    await screen.findByTestId('news-library-item-1');

    await userEvent.selectOptions(screen.getByTestId('news-library-source-filter'), '7');
    await waitFor(() => {
      const last = newsLibraryCalls(fetchMock).at(-1) ?? '';
      expect(last).toContain('sourceId=7');
      expect(last).toContain('page=1');
    });
    expect(
      screen.getByTestId('news-library-source-filter') as HTMLSelectElement,
    ).toHaveTextContent('金十数据·快讯');
  });

  it('源清单接口失败时降级：下拉仅「全部源」，列表不受阻', async () => {
    vi.stubGlobal('fetch', makeFetch(() => ok(paged([itemOf()])), () => serverError()));
    render(<NewsLibrary />);

    await screen.findByTestId('news-library-item-1');
    const select = screen.getByTestId('news-library-source-filter') as HTMLSelectElement;
    expect(Array.from(select.options).map((o) => o.value)).toEqual(['']);
  });

  it('关键词搜索：提交后请求 q 并回第 1 页；清空提交恢复无 q', async () => {
    const fetchMock = makeFetch();
    vi.stubGlobal('fetch', fetchMock);
    render(<NewsLibrary />);
    await screen.findByTestId('news-library-item-1');

    await userEvent.type(screen.getByTestId('news-library-keyword-input'), '降息');
    await userEvent.click(screen.getByTestId('news-library-keyword-search'));
    await waitFor(() => {
      const last = newsLibraryCalls(fetchMock).at(-1) ?? '';
      expect(last).toContain('q=');
      expect(last).toContain('page=1');
    });

    await userEvent.clear(screen.getByTestId('news-library-keyword-input'));
    await userEvent.click(screen.getByTestId('news-library-keyword-search'));
    await waitFor(() => {
      expect(newsLibraryCalls(fetchMock).at(-1) ?? '').not.toContain('q=');
    });
  });

  it('L1 筛选：选择「电子」后请求 l1=电子', async () => {
    const fetchMock = makeFetch();
    vi.stubGlobal('fetch', fetchMock);
    render(<NewsLibrary />);
    await screen.findByTestId('news-library-item-1');

    await userEvent.selectOptions(screen.getByTestId('news-library-l1-filter'), '电子');
    await waitFor(() => {
      expect(newsLibraryCalls(fetchMock).at(-1) ?? '').toContain('l1=');
    });
  });

  it('组合筛选：源 + 状态 + 分类 + 关键词同时生效（AND 组合透传）', async () => {
    const fetchMock = makeFetch();
    vi.stubGlobal('fetch', fetchMock);
    render(<NewsLibrary />);
    await screen.findByTestId('news-library-item-1');

    await userEvent.selectOptions(screen.getByTestId('news-library-source-filter'), '7');
    await waitFor(() => expect(newsLibraryCalls(fetchMock).length).toBeGreaterThanOrEqual(2));
    await userEvent.click(screen.getByTestId('news-library-l0-near-dup'));
    await waitFor(() => expect(newsLibraryCalls(fetchMock).length).toBeGreaterThanOrEqual(3));
    await userEvent.selectOptions(screen.getByTestId('news-library-l1-filter'), '银行');
    await waitFor(() => expect(newsLibraryCalls(fetchMock).length).toBeGreaterThanOrEqual(4));
    await userEvent.type(screen.getByTestId('news-library-keyword-input'), '降息');
    await userEvent.click(screen.getByTestId('news-library-keyword-search'));

    await waitFor(() => {
      const last = newsLibraryCalls(fetchMock).at(-1) ?? '';
      expect(last).toContain('sourceId=7');
      expect(last).toContain('l0=NEAR_DUP');
      expect(last).toContain('l1=');
      expect(last).toContain('q=');
      expect(last).toContain('page=1');
    });
  });

  it('分页：total=45 → 「共 45 条」，点第 2 页请求 page=2；条数切换请求 size=10&page=1', async () => {
    const fetchMock = makeFetch(() => ok(paged([itemOf()], 45)));
    vi.stubGlobal('fetch', fetchMock);
    render(<NewsLibrary />);

    await screen.findByTestId('news-library-pagination-total');
    expect(screen.getByTestId('news-library-pagination-total')).toHaveTextContent('共 45 条');

    await userEvent.click(screen.getByTestId('news-library-pagination-page-2'));
    await waitFor(() => {
      expect(newsLibraryCalls(fetchMock).at(-1) ?? '').toContain('page=2');
    });

    await userEvent.selectOptions(screen.getByTestId('news-library-pagination-size'), '10');
    await waitFor(() => {
      const last = newsLibraryCalls(fetchMock).at(-1) ?? '';
      expect(last).toContain('size=10');
      expect(last).toContain('page=1');
    });
  });

  it('空态区分：无附加筛选（默认 PASS）→「资讯库暂无条目」；有筛选→筛选过窄文案 + 清除筛选 CTA 恢复默认', async () => {
    const fetchMock = makeFetch(() => ok(paged([], 0)));
    vi.stubGlobal('fetch', fetchMock);
    render(<NewsLibrary />);

    await screen.findByTestId('news-library-empty');
    // T228 抽查：统一空态组件结构（标题槽）
    expect(screen.getByTestId('empty-state-title')).toHaveTextContent('资讯库暂无条目');
    expect(screen.getByTestId('news-library-empty')).toHaveTextContent('资讯库暂无条目');
    expect(screen.queryByTestId('news-library-clear-filters')).toBeNull();

    await userEvent.click(screen.getByTestId('news-library-l0-noise'));
    await screen.findByTestId('news-library-empty');
    // T228 抽查：筛选过窄分支（标题 + 描述 + 清除筛选 CTA 动作槽）
    expect(screen.getByTestId('empty-state-title')).toHaveTextContent('未找到匹配条目');
    expect(screen.getByTestId('empty-state-description')).toHaveTextContent('可调整源、状态、分类或关键词后重试');
    expect(screen.getByTestId('news-library-empty')).toHaveTextContent('未找到匹配条目');

    await userEvent.click(screen.getByTestId('news-library-clear-filters'));
    await waitFor(() => {
      const last = newsLibraryCalls(fetchMock).at(-1) ?? '';
      expect(last).toContain('l0=PASS');
      expect(last).not.toContain('l0=NOISE');
    });
  });

  it('错误态：列表接口失败 → 错误提示 + 重试恢复', async () => {
    let fail = true;
    const fetchMock = makeFetch(() => (fail ? serverError() : ok(paged([itemOf()]))));
    vi.stubGlobal('fetch', fetchMock);
    render(<NewsLibrary />);

    await screen.findByTestId('news-library-error');
    // ApiError 优先透出后端 msg（与 Policy 页同口径）
    expect(screen.getByRole('alert')).toHaveTextContent('内部错误');

    fail = false;
    await userEvent.click(screen.getByTestId('news-library-retry'));
    await screen.findByTestId('news-library-item-1');
  });

  it('加载骨架：首查在途时渲染骨架态', async () => {
    let release: ((value: unknown) => void) | undefined;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    const fetchMock = makeFetch(() => gate.then(() => ok(paged([itemOf()]))));
    vi.stubGlobal('fetch', fetchMock);
    render(<NewsLibrary />);

    expect(screen.getByTestId('news-library-loading')).toBeInTheDocument();
    release?.(null);
    await screen.findByTestId('news-library-item-1');
  });
});

describe('资讯库 URL 预填（V2.4 T213，REQ-20260928-20 拍板二：政策页重定向落点）', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    cleanup();
    window.location.hash = '';
  });

  it('l1 预填：#/news-library?l1=监管·政策 挂载即按预填筛选出数（控件初始态一致）', async () => {
    window.location.hash = '#/news-library?l1=监管·政策';
    const fetchMock = makeFetch();
    vi.stubGlobal('fetch', fetchMock);
    render(<NewsLibrary />);

    await screen.findByTestId('news-library-item-1');
    const calls = newsLibraryCalls(fetchMock);
    expect(calls[0]).toContain('l1=');
    expect(decodeURIComponent(calls[0])).toContain('l1=监管·政策');
    // 控件初始态与参数一致（select 值即 l1）
    expect(screen.getByTestId('news-library-l1-filter')).toHaveValue('监管·政策');
  });

  it('sourceId/l0 预填：#/news-library?sourceId=7&l0=ALL 首查带源与全量口径', async () => {
    window.location.hash = '#/news-library?sourceId=7&l0=ALL';
    const fetchMock = makeFetch();
    vi.stubGlobal('fetch', fetchMock);
    render(<NewsLibrary />);

    await screen.findByTestId('news-library-item-1');
    const calls = newsLibraryCalls(fetchMock);
    expect(calls[0]).toContain('sourceId=7');
    expect(calls[0]).toContain('l0=ALL');
    expect(screen.getByTestId('news-library-source-filter')).toHaveValue('7');
  });

  it('非法参数忽略回默认态：l1 非 35 枚举 / sourceId 非数字 / l0 非法枚举不进筛选', async () => {
    window.location.hash = '#/news-library?l1=不存在的分类&sourceId=abc&l0=FOO';
    const fetchMock = makeFetch();
    vi.stubGlobal('fetch', fetchMock);
    render(<NewsLibrary />);

    await screen.findByTestId('news-library-item-1');
    const calls = newsLibraryCalls(fetchMock);
    expect(calls[0]).toContain('l0=PASS');
    expect(calls[0]).not.toContain('l1=');
    expect(calls[0]).not.toContain('sourceId=');
    expect(screen.getByTestId('news-library-l1-filter')).toHaveValue('');
  });

  it('预填仅初始化一次不锁态：挂载后改筛自由（清空 l1 走全量口径）', async () => {
    window.location.hash = '#/news-library?l1=监管·政策';
    const fetchMock = makeFetch();
    vi.stubGlobal('fetch', fetchMock);
    render(<NewsLibrary />);

    await screen.findByTestId('news-library-item-1');
    await userEvent.selectOptions(screen.getByTestId('news-library-l1-filter'), '');
    await waitFor(() => {
      const last = newsLibraryCalls(fetchMock).at(-1) ?? '';
      expect(decodeURIComponent(last)).not.toContain('l1=');
    });
  });
});

describe('发布时间窗序列化（BUG-M23-01 遗留：publishedFrom/To 未进请求参数）', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    cleanup();
    window.location.hash = '';
  });

  it('日期区间变更 → 请求携带 publishedFrom/publishedTo（yyyy-MM-dd 直传）', async () => {
    const fetchMock = makeFetch();
    vi.stubGlobal('fetch', fetchMock);
    render(<NewsLibrary />);
    await screen.findByTestId('news-library-item-1');

    await userEvent.type(screen.getByTestId('news-library-from-date'), '2026-09-21');
    await userEvent.type(screen.getByTestId('news-library-to-date'), '2026-09-22');

    await waitFor(() => {
      const last = newsLibraryCalls(fetchMock).at(-1) ?? '';
      expect(last).toContain('publishedFrom=2026-09-21');
      expect(last).toContain('publishedTo=2026-09-22');
    });
  });

  it('日期变更回第 1 页（新结果集查询，M9 §5 语义）', async () => {
    const fetchMock = makeFetch();
    vi.stubGlobal('fetch', fetchMock);
    render(<NewsLibrary />);
    await screen.findByTestId('news-library-item-1');

    await userEvent.type(screen.getByTestId('news-library-from-date'), '2026-09-21');

    await waitFor(() => {
      const last = newsLibraryCalls(fetchMock).at(-1) ?? '';
      expect(last).toContain('page=1');
      expect(last).toContain('publishedFrom=2026-09-21');
    });
  });
});

describe('资讯库标的增强（V3.1：hover 详情卡 + 关联标的 + 按标的筛选聚合）', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    cleanup();
  });

  const withSubjects = (id: number) =>
    itemOf({
      id,
      matchedSubjects: [
        { code: 'SH600519', name: '贵州茅台', industry: '白酒' },
        { code: 'SZ300024', name: '机器人', industry: null },
      ],
    });

  it('V3.2 移除悬浮卡：行内不渲染 hover 层，标题/摘要/源/L1/低置信仍行内呈现', async () => {
    vi.stubGlobal(
      'fetch',
      makeFetch(() =>
        ok(
          paged([
            itemOf({
              id: 1,
              title: '贵州茅台发布2026年中期业绩公告：营收与净利润均创新高',
              summary: '行内摘要两行截断呈现',
              author: '证券时报',
              nearDupMasterId: 4,
              nearDupMasterUrl: 'https://example.com/master',
            }),
          ]),
        ),
      ),
    );
    render(<NewsLibrary />);

    await screen.findByTestId('news-library-item-1');
    // 悬浮层不挂载（V3.1 → V3.2 移除，行内信息即全量）
    expect(screen.queryByTestId('news-library-hover-1')).toBeNull();
    const row = screen.getByTestId('news-library-item-1');
    expect(row.className).not.toContain('group-hover');
    // 行内既有信息不受影响
    expect(screen.getByTestId('news-library-title-1')).toHaveTextContent('贵州茅台发布2026年中期业绩公告');
    expect(screen.getByTestId('news-library-summary-1')).toHaveTextContent('行内摘要两行截断呈现');
    expect(screen.getByTestId('news-library-source-1')).toHaveTextContent('金十数据·快讯');
    expect(screen.getByTestId('news-library-l1-1')).toHaveTextContent('银行');
    expect(screen.getByTestId('news-library-master-1')).toHaveAttribute(
      'href',
      'https://example.com/master',
    );
  });

  it('标的 chips：matchedSubjects 行渲染名称 chips（title 带代码）；无关联标的行不渲染 chips', async () => {
    vi.stubGlobal(
      'fetch',
      makeFetch(() => ok(paged([withSubjects(1), itemOf({ id: 2 })]))),
    );
    render(<NewsLibrary />);

    await screen.findByTestId('news-library-item-2');
    const chip1 = screen.getByTestId('news-library-subject-chip-1-SH600519');
    expect(chip1).toHaveTextContent('贵州茅台');
    expect(chip1).toHaveAttribute('title', 'SH600519');
    expect(screen.getByTestId('news-library-subject-chip-1-SZ300024')).toHaveTextContent('机器人');
    expect(screen.queryByTestId(/news-library-subject-chip-2-/)).toBeNull();
  });

  it('chips 双向联动：点击 chip → 请求带 subjectCode=SH600519 回第 1 页，页头聚合「标的：SH600519 贵州茅台 · 共 N 条」', async () => {
    const fetchMock = makeFetch(() => ok(paged([withSubjects(1)], 12)));
    vi.stubGlobal('fetch', fetchMock);
    render(<NewsLibrary />);

    await screen.findByTestId('news-library-item-1');
    await userEvent.click(screen.getByTestId('news-library-subject-chip-1-SH600519'));
    await waitFor(() => {
      const last = newsLibraryCalls(fetchMock).at(-1) ?? '';
      expect(last).toContain('subjectCode=SH600519');
      expect(last).toContain('page=1');
    });
    const aggregate = screen.getByTestId('news-library-subject-aggregate');
    expect(aggregate).toHaveTextContent('SH600519');
    expect(aggregate).toHaveTextContent('贵州茅台');
    expect(aggregate).toHaveTextContent('共 12 条');
    // 双向联动：筛选器同步呈现选中态（SubjectPicker 已选视图）
    expect(screen.getByTestId('news-library-subject-picker-selected')).toHaveTextContent(
      'SH600519',
    );
  });

  it('chips 触发筛选后翻页：分页通道保留 subjectCode 条件', async () => {
    const fetchMock = makeFetch(() => ok(paged([withSubjects(1)], 45)));
    vi.stubGlobal('fetch', fetchMock);
    render(<NewsLibrary />);

    await screen.findByTestId('news-library-item-1');
    await userEvent.click(screen.getByTestId('news-library-subject-chip-1-SH600519'));
    await waitFor(() =>
      expect(newsLibraryCalls(fetchMock).at(-1) ?? '').toContain('subjectCode=SH600519'),
    );
    await userEvent.click(screen.getByTestId('news-library-pagination-page-2'));
    await waitFor(() => {
      const last = newsLibraryCalls(fetchMock).at(-1) ?? '';
      expect(last).toContain('subjectCode=SH600519');
      expect(last).toContain('page=2');
    });
  });

  it('SubjectPicker 联动：输入联想 → 选中 → 请求带 subjectCode 且聚合页头按该标的出数', async () => {
    const fetchMock = makeFetch(
      () => ok(paged([withSubjects(1)], 7)),
      () => ok(sourcesView()),
      () => ok(subjectOptions()),
    );
    vi.stubGlobal('fetch', fetchMock);
    render(<NewsLibrary />);

    await screen.findByTestId('news-library-item-1');
    await userEvent.type(screen.getByTestId('news-library-subject-picker-input'), '茅台');
    await userEvent.click(await screen.findByTestId('news-library-subject-picker-option-7'));

    await waitFor(() => {
      const last = newsLibraryCalls(fetchMock).at(-1) ?? '';
      expect(last).toContain('subjectCode=SH600519');
      expect(last).toContain('page=1');
    });
    expect(screen.getByTestId('news-library-subject-aggregate')).toHaveTextContent('共 7 条');
    expect(screen.getByTestId('news-library-subject-aggregate')).toHaveTextContent('贵州茅台');
  });

  it('清除标的：picker × 一键清除 → 请求恢复无 subjectCode，聚合行消失', async () => {
    const fetchMock = makeFetch(() => ok(paged([withSubjects(1)], 7)));
    vi.stubGlobal('fetch', fetchMock);
    render(<NewsLibrary />);

    await screen.findByTestId('news-library-item-1');
    await userEvent.click(screen.getByTestId('news-library-subject-chip-1-SH600519'));
    await waitFor(() =>
      expect(newsLibraryCalls(fetchMock).at(-1) ?? '').toContain('subjectCode=SH600519'),
    );
    expect(screen.getByTestId('news-library-subject-aggregate')).toBeInTheDocument();

    await userEvent.click(screen.getByTestId('news-library-subject-picker-clear'));
    await waitFor(() => {
      expect(newsLibraryCalls(fetchMock).at(-1) ?? '').not.toContain('subjectCode=');
    });
    expect(screen.queryByTestId('news-library-subject-aggregate')).toBeNull();
    expect(screen.getByTestId('news-library-subject-picker-input')).toBeInTheDocument();
  });

  it('组合筛选：subjectCode 与源/状态 AND 组合透传', async () => {
    const fetchMock = makeFetch(() => ok(paged([withSubjects(1)])));
    vi.stubGlobal('fetch', fetchMock);
    render(<NewsLibrary />);

    await screen.findByTestId('news-library-item-1');
    await userEvent.selectOptions(screen.getByTestId('news-library-source-filter'), '7');
    await waitFor(() => expect(newsLibraryCalls(fetchMock).length).toBeGreaterThanOrEqual(2));
    await userEvent.click(screen.getByTestId('news-library-subject-chip-1-SH600519'));

    await waitFor(() => {
      const last = newsLibraryCalls(fetchMock).at(-1) ?? '';
      expect(last).toContain('sourceId=7');
      expect(last).toContain('subjectCode=SH600519');
      expect(last).toContain('page=1');
    });
  });

  it('清除全部筛选 CTA：标的筛选一并恢复默认（无 subjectCode）', async () => {
    // 带 subjectCode 的请求回空（筛选过窄空态），不带的全量返回（chips 可点）
    const fetchMock = vi.fn(async (url: string) => {
      const path = String(url);
      if (path.includes('/news-items')) {
        return path.includes('subjectCode=') ? ok(paged([], 0)) : ok(paged([withSubjects(1)]));
      }
      if (path.includes('/info-sources')) return ok(sourcesView());
      return ok(null);
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<NewsLibrary />);

    await screen.findByTestId('news-library-item-1');
    await userEvent.click(screen.getByTestId('news-library-subject-chip-1-SH600519'));
    // 标的筛选下的空态 = 有附加筛选（过窄文案 + 清除 CTA，非「库为空」）
    await screen.findByTestId('news-library-empty');
    expect(screen.getByTestId('empty-state-title')).toHaveTextContent('未找到匹配条目');
    expect(screen.getByTestId('news-library-clear-filters')).toBeInTheDocument();

    await userEvent.click(screen.getByTestId('news-library-clear-filters'));
    await waitFor(() => {
      const last = newsLibraryCalls(fetchMock).at(-1) ?? '';
      expect(last).not.toContain('subjectCode=');
      expect(last).toContain('l0=PASS');
    });
    expect(screen.queryByTestId('news-library-subject-aggregate')).toBeNull();
  });

  it('默认加载与未选标的时：请求不带 subjectCode，聚合行不渲染', async () => {
    const fetchMock = makeFetch();
    vi.stubGlobal('fetch', fetchMock);
    render(<NewsLibrary />);

    await screen.findByTestId('news-library-item-1');
    expect(newsLibraryCalls(fetchMock)[0]).not.toContain('subjectCode=');
    expect(screen.queryByTestId('news-library-subject-aggregate')).toBeNull();
  });
});
