// M12 T94：分区分页/加载更多状态机（UI 设计 §5.1/§5.2 + 方案 §4.3）。
// - useAnnounceSectionPage：翻页替换/巨潮降级保留/越界空页回第 1 页/失败重试同目标页；
// - useEventSectionPage：total 以响应覆盖；
// - useNewsSectionPage：探页状态机——seenIds 去重/单点击 ≤2 源页/三路停止/失败重试同探页。

import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  stableNewsId,
  useAnnounceSectionPage,
  useEventSectionPage,
  useNewsSectionPage,
} from '@/hooks/useSectionPage';
import type {
  AnnouncementPageView,
  EventPageView,
  NewsItem,
  NewsPageView,
} from '@/types/subject-detail';

// —— fetch 打桩基建 —— #

/** 构造类 Response 的 mock（只暴露 request() 用到的 ok/status/json）。 */
function mockResponse(status: number, body: unknown) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as unknown as Response;
}

/** 响应队列项：视图 / 拒绝（模拟网络失败）/ 降级响应（200 + sourceStatus 非 ok）。 */
type QueueItem =
  | { kind: 'view'; data: unknown }
  | { kind: 'reject' }
  | { kind: 'degraded'; status: 'failed' | 'timeout' };

/** 按路径前缀分发的 fetch 打桩：子端点响应按队列顺序出队（缺省空 items 成功）。 */
function stubSectionFetch(queues: {
  announcements?: QueueItem[];
  events?: QueueItem[];
  news?: QueueItem[];
} = {}) {
  const fetchMock = vi.fn(async (url: string | URL) => {
    const path = String(url);
    const pick = (queue?: QueueItem[]) => {
      const item = queue?.shift() ?? { kind: 'view' as const, data: undefined };
      if (item.kind === 'reject') return Promise.reject(new TypeError('network down'));
      if (item.kind === 'degraded') {
        return mockResponse(200, {
          code: 0,
          msg: 'ok',
          data: { items: [], page: 1, size: 10, sourceStatus: item.status },
        });
      }
      return mockResponse(200, { code: 0, msg: 'ok', data: item.data });
    };
    if (path.includes('/announcements')) return pick(queues.announcements);
    if (path.includes('/events')) return pick(queues.events);
    if (path.includes('/news')) return pick(queues.news);
    return mockResponse(200, { code: 0, msg: 'ok', data: null });
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function announceView(overrides: Partial<AnnouncementPageView> = {}): AnnouncementPageView {
  return {
    items: [{ title: `公告 ${overrides.page ?? 1}`, publishedAt: '2026-08-15' }],
    page: 1,
    size: 10,
    total: 1074,
    paginationSupported: true,
    moreUrl: 'https://data.eastmoney.com/notices/stock/600519.html',
    sourceStatus: 'ok',
    ...overrides,
  };
}

function eventView(overrides: Partial<EventPageView> = {}): EventPageView {
  return {
    items: [{ anomalyType: 'PRICE_CHANGE', triggerTime: '2026-09-21T02:00:00Z' }],
    page: 1,
    size: 10,
    total: 37,
    sourceStatus: 'ok',
    ...overrides,
  };
}

function newsView(items: NewsItem[], hasMore: boolean, page = 2): NewsPageView {
  return { items, page, size: 20, hasMore, sourceStatus: 'ok' };
}

function newsItem(id: string, title?: string): NewsItem {
  return { externalId: id, title: title ?? `新闻 ${id}`, publishedAt: '2026-09-21' };
}

const INITIAL_ANNOUNCE = {
  items: [{ title: '首屏公告', publishedAt: '2026-08-15' }],
  meta: {
    total: 1074,
    paginationSupported: true,
    moreUrl: 'https://data.eastmoney.com/notices/stock/600519.html',
  },
};

afterEach(() => {
  vi.unstubAllGlobals();
});

// —— 公告分区分页 —— #

describe('useAnnounceSectionPage 公告分区分页', () => {
  it('翻页成功：列表整体替换 + total/meta 以响应覆盖 + 页码推进', async () => {
    stubSectionFetch({
      announcements: [{ kind: 'view', data: announceView({ page: 2, total: 1080 }) }],
    });
    const { result } = renderHook(() => useAnnounceSectionPage(1, INITIAL_ANNOUNCE));

    expect(result.current.page).toBe(1);
    await act(async () => {
      result.current.goToPage(2);
    });

    expect(result.current.page).toBe(2);
    expect(result.current.items).toHaveLength(1);
    expect(result.current.items[0]?.title).toBe('公告 2');
    expect(result.current.meta.total).toBe(1080);
    expect(result.current.loading).toBe(false);
    expect(result.current.error).toBeNull();
  });

  it('巨潮降级（paginationSupported=false 空页）：保留当前内容，不视为错误', async () => {
    stubSectionFetch({
      announcements: [
        {
          kind: 'view',
          data: announceView({
            page: 2,
            items: [],
            total: null,
            paginationSupported: false,
          }),
        },
      ],
    });
    const { result } = renderHook(() => useAnnounceSectionPage(1, INITIAL_ANNOUNCE));

    await act(async () => {
      result.current.goToPage(2);
    });

    expect(result.current.items[0]?.title).toBe('首屏公告');
    expect(result.current.meta.paginationSupported).toBe(false);
    expect(result.current.meta.total).toBeNull();
    expect(result.current.error).toBeNull();
  });

  it('越界空页（page ≤5 但超出源总页数）：静默回第 1 页', async () => {
    const fetchMock = stubSectionFetch({
      announcements: [
        { kind: 'view', data: announceView({ page: 2, items: [] }) },
        { kind: 'view', data: announceView({ page: 1 }) },
      ],
    });
    const { result } = renderHook(() => useAnnounceSectionPage(1, INITIAL_ANNOUNCE));

    await act(async () => {
      result.current.goToPage(2);
    });

    expect(result.current.page).toBe(1);
    expect(result.current.items[0]?.title).toBe('公告 1');
    const urls = fetchMock.mock.calls.map((call) => String(call[0]));
    expect(urls.some((url) => url.includes('announcements?page=2'))).toBe(true);
    expect(urls.some((url) => url.includes('announcements?page=1'))).toBe(true);
  });

  it('翻页失败：保留当前页数据，重试重发同一目标页', async () => {
    const fetchMock = stubSectionFetch({
      announcements: [
        { kind: 'reject' },
        { kind: 'view', data: announceView({ page: 2 }) },
      ],
    });
    const { result } = renderHook(() => useAnnounceSectionPage(1, INITIAL_ANNOUNCE));

    await act(async () => {
      result.current.goToPage(2);
    });

    expect(result.current.page).toBe(1);
    expect(result.current.items[0]?.title).toBe('首屏公告');
    expect(result.current.error?.page).toBe(2);
    expect(result.current.loading).toBe(false);

    await act(async () => {
      result.current.retry();
    });

    expect(result.current.error).toBeNull();
    expect(result.current.page).toBe(2);
    expect(result.current.items[0]?.title).toBe('公告 2');
    // 重试重发的是同一目标页（page=2 两次）
    const page2Calls = fetchMock.mock.calls
      .map((call) => String(call[0]))
      .filter((url) => url.includes('announcements?page=2'));
    expect(page2Calls).toHaveLength(2);
  });

  it('200 + sourceStatus 非 ok：走错误通道（保留内容 + 可重试）', async () => {
    stubSectionFetch({ announcements: [{ kind: 'degraded', status: 'timeout' }] });
    const { result } = renderHook(() => useAnnounceSectionPage(1, INITIAL_ANNOUNCE));

    await act(async () => {
      result.current.goToPage(2);
    });

    expect(result.current.items[0]?.title).toBe('首屏公告');
    expect(result.current.error?.message).toContain('超时');
  });

  it('subjectId 未解析（null）：翻页入口静默，不发请求', async () => {
    const fetchMock = stubSectionFetch();
    const { result } = renderHook(() => useAnnounceSectionPage(null, INITIAL_ANNOUNCE));

    await act(async () => {
      result.current.goToPage(2);
    });

    expect(fetchMock).not.toHaveBeenCalled();
    expect(result.current.page).toBe(1);
  });
});

// —— 事件分区分页 —— #

describe('useEventSectionPage 事件分区分页', () => {
  it('翻页成功：列表替换 + total 以 7 天窗 count 覆盖', async () => {
    stubSectionFetch({
      events: [{ kind: 'view', data: eventView({ page: 2, total: 36 }) }],
    });
    const { result } = renderHook(() =>
      useEventSectionPage(1, {
        items: [{ anomalyType: 'VOLUME', triggerTime: '2026-09-20T02:00:00Z' }],
        total: 37,
      }),
    );

    expect(result.current.meta.total).toBe(37);
    await act(async () => {
      result.current.goToPage(2);
    });

    expect(result.current.page).toBe(2);
    expect(result.current.meta.total).toBe(36);
    expect(result.current.items[0]?.anomalyType).toBe('PRICE_CHANGE');
  });

  it('翻页失败保留当前页，错误态可重试恢复', async () => {
    stubSectionFetch({
      events: [{ kind: 'reject' }, { kind: 'view', data: eventView({ page: 3 }) }],
    });
    const { result } = renderHook(() =>
      useEventSectionPage(1, { items: [], total: 37 }),
    );

    await act(async () => {
      result.current.goToPage(3);
    });
    expect(result.current.error?.page).toBe(3);
    expect(result.current.meta.total).toBe(37);

    await act(async () => {
      result.current.retry();
    });
    expect(result.current.error).toBeNull();
    expect(result.current.page).toBe(3);
  });
});

// —— 新闻「加载更多」探页状态机 —— #

describe('useNewsSectionPage 新闻探页状态机', () => {
  it('点击追加命中条目 + scrollRequest 指向首条新增下标；acknowledge 后清空', async () => {
    stubSectionFetch({
      news: [{ kind: 'view', data: newsView([newsItem('n3'), newsItem('n4')], true) }],
    });
    const initial = [newsItem('n1'), newsItem('n2')];
    const { result } = renderHook(() => useNewsSectionPage(1, initial));

    await act(async () => {
      result.current.loadMore();
    });

    expect(result.current.items.map((item) => item.externalId)).toEqual([
      'n1',
      'n2',
      'n3',
      'n4',
    ]);
    expect(result.current.scrollRequest).toBe(2);
    expect(result.current.stopped).toBe(false);
    expect(result.current.loading).toBe(false);

    act(() => {
      result.current.acknowledgeScroll();
    });
    expect(result.current.scrollRequest).toBeNull();
  });

  it('去重：跨源页重复条目（同 externalId）不进入追加列表', async () => {
    stubSectionFetch({
      news: [
        {
          kind: 'view',
          data: newsView([newsItem('n1', '重复旧闻'), newsItem('n3')], true),
        },
      ],
    });
    const { result } = renderHook(() =>
      useNewsSectionPage(1, [newsItem('n1'), newsItem('n2')]),
    );

    await act(async () => {
      result.current.loadMore();
    });

    expect(result.current.items).toHaveLength(3);
    expect(result.current.items.some((item) => item.title === '重复旧闻')).toBe(false);
  });

  it('单点击 ≤2 源页：首个源页零新增自动续探，两页合计有新增即结束', async () => {
    const fetchMock = stubSectionFetch({
      news: [
        { kind: 'view', data: newsView([], true, 2) },
        { kind: 'view', data: newsView([newsItem('n3')], true, 3) },
      ],
    });
    const { result } = renderHook(() =>
      useNewsSectionPage(1, [newsItem('n1')]),
    );

    await act(async () => {
      result.current.loadMore();
    });

    expect(result.current.items.map((item) => item.externalId)).toEqual(['n1', 'n3']);
    expect(result.current.stopped).toBe(false);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('停止条件一：连续 2 个源页零新增 → stopped 且不再发请求', async () => {
    const fetchMock = stubSectionFetch({
      news: [
        { kind: 'view', data: newsView([], true, 2) },
        { kind: 'view', data: newsView([], true, 3) },
      ],
    });
    const { result } = renderHook(() =>
      useNewsSectionPage(1, [newsItem('n1')]),
    );

    await act(async () => {
      result.current.loadMore();
    });

    expect(result.current.stopped).toBe(true);
    expect(fetchMock).toHaveBeenCalledTimes(2);

    await act(async () => {
      result.current.loadMore();
    });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('停止条件二：hasMore=false（源页耗尽）→ 有新增也进入停止终态', async () => {
    stubSectionFetch({
      news: [{ kind: 'view', data: newsView([newsItem('n3')], false) }],
    });
    const { result } = renderHook(() =>
      useNewsSectionPage(1, [newsItem('n1')]),
    );

    await act(async () => {
      result.current.loadMore();
    });

    expect(result.current.items).toHaveLength(2);
    expect(result.current.stopped).toBe(true);
  });

  it('停止条件三：累计探页达 10（首屏源页 1 计入）→ 停止', async () => {
    // 交替模式：偶数源页空、奇数源页有新增（避免触发连续 2 空），
    // 首屏已探 1 页 → 再探 2..10 共 9 页后 explored=10 → stopped。
    const queue: QueueItem[] = [];
    for (let page = 2; page <= 10; page++) {
      const items = page % 2 === 1 ? [newsItem(`n${page}`)] : [];
      queue.push({ kind: 'view', data: newsView(items, true, page) });
    }
    const fetchMock = stubSectionFetch({ news: queue });
    const { result } = renderHook(() =>
      useNewsSectionPage(1, [newsItem('n1')]),
    );

    // 8 次点击 × ≤2 源页 = 覆盖源页 2..10（每次点击：空页续探到有新增页结束）
    for (let click = 0; click < 8; click++) {
      await act(async () => {
        result.current.loadMore();
      });
      if (result.current.stopped) break;
    }

    expect(result.current.stopped).toBe(true);
    expect(fetchMock).toHaveBeenCalledTimes(9);
  });

  it('失败：保留已加载条目 + 错误态；重试重发同一目标源页', async () => {
    const fetchMock = stubSectionFetch({
      news: [
        { kind: 'reject' },
        { kind: 'view', data: newsView([newsItem('n3')], true) },
      ],
    });
    const { result } = renderHook(() =>
      useNewsSectionPage(1, [newsItem('n1'), newsItem('n2')]),
    );

    await act(async () => {
      result.current.loadMore();
    });

    expect(result.current.items).toHaveLength(2);
    expect(result.current.error).toContain('网络');
    expect(result.current.stopped).toBe(false);

    await act(async () => {
      result.current.retry();
    });

    expect(result.current.error).toBeNull();
    expect(result.current.items).toHaveLength(3);
    // 失败页未推进游标：重试仍是 page=2
    const page2Calls = fetchMock.mock.calls
      .map((call) => String(call[0]))
      .filter((url) => url.includes('/news?page=2'));
    expect(page2Calls).toHaveLength(2);
  });

  it('subjectId 未解析（null）：loadMore 静默不发请求', async () => {
    const fetchMock = stubSectionFetch();
    const { result } = renderHook(() => useNewsSectionPage(null, [newsItem('n1')]));

    await act(async () => {
      result.current.loadMore();
    });

    expect(fetchMock).not.toHaveBeenCalled();
    expect(result.current.stopped).toBe(false);
  });
});

describe('stableNewsId 新闻稳定标识', () => {
  it('externalId 优先，id 兜底，均缺失为 null（title 不参与去重）', () => {
    expect(stableNewsId({ externalId: 'doc-1', title: 'a', publishedAt: '2026-09-21' })).toBe(
      'doc-1',
    );
    expect(stableNewsId({ id: 'fallback', title: 'a', publishedAt: '2026-09-21' })).toBe(
      'fallback',
    );
    expect(stableNewsId({ title: 'a', publishedAt: '2026-09-21' })).toBeNull();
  });
});
