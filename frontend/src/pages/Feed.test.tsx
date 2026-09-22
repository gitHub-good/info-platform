// 个人信息流页测试（T43）：渲染/高亮/两种空态/三态/游标分页（按钮兜底 + IO 哨兵）/翻页失败不清数据。
import { cleanup, render, screen, waitFor, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Feed } from '@/pages/Feed';
import type { FeedItemView, FeedPage, SubscriptionSummary } from '@/types/feed';

// —— fetch mock：GET /feed/personal（游标分页）+ GET /subscriptions（空态判别） ——

const ok = (data: unknown) => ({
  ok: true,
  status: 200,
  json: async () => ({ code: 0, msg: 'ok', data, traceId: 't' }),
});
const fail = (code = 50000) => ({
  ok: false,
  status: 500,
  json: async () => ({ code, msg: '服务异常', data: null, traceId: 't' }),
});

const SUB_ACTIVE: SubscriptionSummary = {
  id: 1,
  subType: 1,
  subKey: '人工智能',
  channel: 1,
  status: 1,
};

function itemOf(overrides: Partial<FeedItemView> = {}): FeedItemView {
  return {
    id: 1,
    type: 'policy',
    title: '关于人工智能行动方案的通知',
    summary: '加快人工智能产业发展，推动……人工智能……应用',
    publishedAt: '2026-09-22T06:30:00Z',
    source: 'gov.cn',
    url: 'https://www.gov.cn/zhengce/content.htm',
    subjectCode: null,
    subjectName: null,
    matchReason: '主题订阅：人工智能',
    keywords: ['人工智能'],
    ...overrides,
  };
}

/** 四类型条目各一（覆盖类型徽章与「原文」有无两种形态）。 */
function fourTypeItems(): FeedItemView[] {
  return [
    itemOf({ id: 1 }),
    itemOf({
      id: 2,
      type: 'announce',
      title: '贵州茅台关于回购股份的公告',
      summary: '公司拟以集中竞价方式回购……',
      source: '公告',
      url: 'https://np-anotice.eastmoney.com/x',
      subjectCode: 'SH600519',
      subjectName: '贵州茅台',
      matchReason: '标的订阅：贵州茅台',
      keywords: ['贵州茅台'],
    }),
    itemOf({
      id: 3,
      type: 'news',
      title: 'AI 芯片板块走强',
      summary: '受政策利好影响，AI 概念股午后拉升',
      source: '新浪财经',
      url: 'https://finance.sina.com.cn/x',
      subjectCode: 'SZ000001',
      subjectName: '平安银行',
      matchReason: '事件类型订阅：AI',
      keywords: [],
    }),
    itemOf({
      id: 4,
      type: 'recommendation',
      title: '贵州茅台',
      summary: '今日推荐理由：异动检测命中放量上涨',
      publishedAt: '2026-09-22T07:00:00Z',
      source: '每日推荐',
      url: null,
      subjectCode: 'SH600519',
      subjectName: '贵州茅台',
      matchReason: '每日推荐',
      keywords: [],
    }),
  ];
}

interface StoreOpts {
  /** 信息流页序列（按请求次序返回，末页重复）。 */
  pages?: FeedPage[];
  /** 订阅列表；'FAIL' 强制订阅查询失败。 */
  subscriptions?: SubscriptionSummary[] | 'FAIL';
}

function makeStore(opts: StoreOpts = {}) {
  let feedCalls = 0;
  const fetchMock = vi.fn(async (url: string) => {
    const path = String(url);
    if (path.includes('/subscriptions')) {
      if (opts.subscriptions === 'FAIL') return fail();
      return ok({ items: opts.subscriptions ?? [], nextCursor: null });
    }
    if (/\/feed\/personal(\?.*)?$/.test(path)) {
      const pages = opts.pages ?? [{ items: [], nextCursor: null }];
      const idx = Math.min(feedCalls, pages.length - 1);
      feedCalls += 1;
      return ok(pages[idx]);
    }
    return fail();
  });
  return { fetchMock };
}

function feedCallsOf(fetchMock: ReturnType<typeof makeStore>['fetchMock']) {
  return fetchMock.mock.calls
    .map((c) => String(c[0]))
    .filter((url) => /\/feed\/personal(\?.*)?$/.test(url));
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  localStorage.clear();
  window.location.hash = '';
});

describe('Feed 个人信息流页（T43）', () => {
  it('主路径：渲染四类型条目（类型徽章配色/元信息/命中原因 chip），关键词高亮为 <mark>', async () => {
    const store = makeStore({
      pages: [{ items: fourTypeItems(), nextCursor: null }],
      subscriptions: [SUB_ACTIVE],
    });
    vi.stubGlobal('fetch', store.fetchMock);
    render(<Feed />);

    expect(await screen.findByTestId('feed-item-1')).toBeInTheDocument();
    // 类型徽章：四类型文字明示 + 配色（政策 violet / 公告 emerald / 新闻 sky / 推荐 amber）
    expect(screen.getByTestId('feed-item-type-1')).toHaveTextContent('政策');
    expect(screen.getByTestId('feed-item-type-1').className).toContain('violet');
    expect(screen.getByTestId('feed-item-type-2')).toHaveTextContent('公告');
    expect(screen.getByTestId('feed-item-type-2').className).toContain('emerald');
    expect(screen.getByTestId('feed-item-type-3')).toHaveTextContent('新闻');
    expect(screen.getByTestId('feed-item-type-3').className).toContain('sky');
    expect(screen.getByTestId('feed-item-type-4')).toHaveTextContent('推荐');
    expect(screen.getByTestId('feed-item-type-4').className).toContain('amber');
    // 命中原因 chip（后端 matchReason 原文）
    expect(screen.getByTestId('feed-item-reason-1')).toHaveTextContent('主题订阅：人工智能');
    expect(screen.getByTestId('feed-item-reason-4')).toHaveTextContent('每日推荐');
    // 元信息：来源 + 标的名
    expect(screen.getByTestId('feed-item-2')).toHaveTextContent('公告 · 标的 贵州茅台');
    // 无更多数据：已加载全部，不再出现哨兵/按钮
    expect(screen.getByTestId('feed-end')).toHaveTextContent('已加载全部');
    expect(screen.queryByTestId('feed-sentinel')).toBeNull();
    expect(screen.queryByTestId('feed-load-more')).toBeNull();
  });

  it('高亮：标题与摘要命中词渲染 amber <mark>（大小写不敏感），keywords 为空不高亮', async () => {
    const store = makeStore({
      pages: [
        {
          items: [
            itemOf({ id: 1, keywords: ['人工智能', 'ai'] }),
            itemOf({ id: 3, type: 'news', title: 'AI 芯片板块走强', keywords: [], matchReason: null }),
          ],
          nextCursor: null,
        },
      ],
      subscriptions: [SUB_ACTIVE],
    });
    vi.stubGlobal('fetch', store.fetchMock);
    render(<Feed />);

    await screen.findByTestId('feed-item-1');
    // 标题 + 摘要各有一处命中（同一关键词两处出现均高亮）
    const marks = document.querySelectorAll('mark');
    expect(marks.length).toBeGreaterThanOrEqual(3);
    for (const mark of Array.from(marks)) {
      expect(mark.className).toContain('bg-amber-500/25');
    }
    // keywords 为空的条目不产生 mark
    const item3 = screen.getByTestId('feed-item-3');
    expect(item3.querySelectorAll('mark')).toHaveLength(0);
  });

  it('原文外链：target=_blank 且 rel 含 noopener/noreferrer；无 url 条目（推荐）不渲染链接', async () => {
    const store = makeStore({
      pages: [{ items: fourTypeItems(), nextCursor: null }],
      subscriptions: [SUB_ACTIVE],
    });
    vi.stubGlobal('fetch', store.fetchMock);
    render(<Feed />);

    await screen.findByTestId('feed-item-1');
    const link = screen.getByTestId('feed-item-link-1');
    expect(link).toHaveAttribute('href', 'https://www.gov.cn/zhengce/content.htm');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link.getAttribute('rel')).toContain('noopener');
    expect(link.getAttribute('rel')).toContain('noreferrer');
    expect(screen.queryByTestId('feed-item-link-4')).toBeNull();
  });

  it('空态·无订阅：引导空态 + CTA 去自选清单（不出现「无命中」文案）', async () => {
    const store = makeStore({ pages: [{ items: [], nextCursor: null }], subscriptions: [] });
    vi.stubGlobal('fetch', store.fetchMock);
    render(<Feed />);

    expect(await screen.findByTestId('feed-empty-no-subs')).toBeInTheDocument();
    expect(screen.getByTestId('feed-empty-no-subs')).toHaveTextContent('还没有订阅内容会出现在这里');
    const cta = screen.getByTestId('feed-empty-cta');
    expect(cta).toHaveAttribute('href', '#/watchlists');
    expect(screen.queryByTestId('feed-empty-no-hits')).toBeNull();
  });

  it('空态·有订阅无命中：muted 文案无 CTA；已退订（status=0）不算活跃订阅', async () => {
    const unsubscribed: SubscriptionSummary = { ...SUB_ACTIVE, status: 0 };
    const store = makeStore({
      pages: [{ items: [], nextCursor: null }],
      subscriptions: [unsubscribed],
    });
    vi.stubGlobal('fetch', store.fetchMock);
    render(<Feed />);

    expect(await screen.findByTestId('feed-empty-no-subs')).toBeInTheDocument();
  });

  it('空态·订阅查询失败降级：不阻断信息流，按「无命中」文案（不误导引导）', async () => {
    const store = makeStore({
      pages: [{ items: [], nextCursor: null }],
      subscriptions: 'FAIL',
    });
    vi.stubGlobal('fetch', store.fetchMock);
    render(<Feed />);

    expect(await screen.findByTestId('feed-empty-no-hits')).toHaveTextContent(
      '暂无命中内容，可稍后再来看看',
    );
    expect(screen.queryByTestId('feed-empty-cta')).toBeNull();
  });

  it('三态·loading 与首屏错误重试：骨架占位 → 错误 + 重试恢复', async () => {
    let feedCalls = 0;
    const fetchMock = vi.fn(async (url: string) => {
      const path = String(url);
      if (path.includes('/subscriptions')) {
        return ok({ items: [SUB_ACTIVE], nextCursor: null });
      }
      feedCalls += 1;
      return feedCalls === 1
        ? fail() // 首屏失败一次
        : ok({ items: [itemOf()], nextCursor: null } as FeedPage);
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<Feed />);

    expect(screen.getByTestId('feed-loading')).toBeInTheDocument();
    expect(await screen.findByTestId('feed-error')).toHaveTextContent('服务异常');

    // 重试：信息流恢复正常
    const user = userEvent.setup();
    await user.click(screen.getByTestId('feed-retry'));
    expect(await screen.findByTestId('feed-item-1')).toBeInTheDocument();
    expect(screen.queryByTestId('feed-error')).toBeNull();
  });

  it('分页·按钮兜底（IO 不可用）：显示「加载更多」，点击带 cursor 追加下一页', async () => {
    expect(typeof IntersectionObserver).toBe('undefined'); // jsdom 无 IO，走按钮兜底
    const page1: FeedPage = { items: [itemOf({ id: 1 })], nextCursor: 1 };
    const page2: FeedPage = {
      items: [itemOf({ id: 2, title: '第二条政策' })],
      nextCursor: null,
    };
    const store = makeStore({ pages: [page1, page2], subscriptions: [SUB_ACTIVE] });
    vi.stubGlobal('fetch', store.fetchMock);
    const user = userEvent.setup();
    render(<Feed />);

    await screen.findByTestId('feed-item-1');
    expect(screen.queryByTestId('feed-sentinel')).toBeNull();
    expect(screen.getByTestId('feed-load-more')).toBeInTheDocument();

    await user.click(screen.getByTestId('feed-load-more'));

    expect(await screen.findByTestId('feed-item-2')).toBeInTheDocument();
    expect(screen.getByTestId('feed-item-1')).toBeInTheDocument();
    expect(screen.getByTestId('feed-end')).toBeInTheDocument();

    const calls = feedCallsOf(store.fetchMock);
    expect(calls).toHaveLength(2);
    expect(calls[1]).toContain('cursor=1');
  });

  it('分页·翻页失败不清已有条目：错误可见可重试，重试后追加（数据保留）', async () => {
    let feedCalls = 0;
    const fetchMock = vi.fn(async (url: string) => {
      const path = String(url);
      if (path.includes('/subscriptions')) {
        return ok({ items: [SUB_ACTIVE], nextCursor: null });
      }
      const call = feedCalls;
      feedCalls += 1;
      if (call === 0) return ok({ items: [itemOf({ id: 1 })], nextCursor: 1 } as FeedPage);
      if (call === 1) return fail(); // 翻页失败一次
      return ok({ items: [itemOf({ id: 2, title: '第二条政策' })], nextCursor: null } as FeedPage);
    });
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    render(<Feed />);

    await screen.findByTestId('feed-item-1');
    await user.click(screen.getByTestId('feed-load-more'));

    // 失败：已有条目保留 + 错误提示 + 兜底按钮可重试
    expect(await screen.findByTestId('feed-more-error')).toHaveTextContent('服务异常');
    expect(screen.getByTestId('feed-item-1')).toBeInTheDocument();
    expect(screen.getByTestId('feed-load-more')).toBeInTheDocument();

    await user.click(screen.getByTestId('feed-load-more'));

    expect(await screen.findByTestId('feed-item-2')).toBeInTheDocument();
    expect(screen.getByTestId('feed-item-1')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByTestId('feed-more-error')).toBeNull());
  });

  it('分页·触底哨兵（IO 可用）：进入视口自动加载下一页，加载中防重复触发', async () => {
    // 最小 IntersectionObserver 桩：记录实例与观察元素，trigger 模拟进入视口
    const instances: Array<{
      elements: Element[];
      trigger: () => void;
    }> = [];
    class MockIntersectionObserver {
      elements: Element[] = [];
      private readonly callback: IntersectionObserverCallback;
      constructor(callback: IntersectionObserverCallback) {
        this.callback = callback;
        instances.push({
          elements: this.elements,
          trigger: () => {
            this.callback(
              this.elements.map(
                (el) => ({ target: el, isIntersecting: true }) as IntersectionObserverEntry,
              ),
              this as unknown as IntersectionObserver,
            );
          },
        });
      }
      observe(el: Element) {
        this.elements.push(el);
      }
      disconnect() {}
      unobserve() {}
    }
    vi.stubGlobal('IntersectionObserver', MockIntersectionObserver);

    const page1: FeedPage = { items: [itemOf({ id: 1 })], nextCursor: 1 };
    const page2: FeedPage = {
      items: [itemOf({ id: 2, title: '第二条政策' })],
      nextCursor: null,
    };
    const store = makeStore({ pages: [page1, page2], subscriptions: [SUB_ACTIVE] });
    vi.stubGlobal('fetch', store.fetchMock);
    render(<Feed />);

    // 首页就绪后哨兵被观察（不出现兜底按钮）
    await screen.findByTestId('feed-item-1');
    await waitFor(() => expect(instances.length).toBeGreaterThan(0));
    expect(instances[instances.length - 1].elements).toHaveLength(1);
    expect(screen.queryByTestId('feed-load-more')).toBeNull();

    // 哨兵进入视口 → 自动加载下一页（带 cursor）
    await act(async () => {
      instances[instances.length - 1].trigger();
    });
    expect(await screen.findByTestId('feed-item-2')).toBeInTheDocument();
    const calls = feedCallsOf(store.fetchMock);
    expect(calls).toHaveLength(2);
    expect(calls[1]).toContain('cursor=1');

    // nextCursor=null：哨兵卸载（停止观察），「已加载全部」
    await waitFor(() => expect(screen.queryByTestId('feed-sentinel')).toBeNull());
    expect(screen.getByTestId('feed-end')).toBeInTheDocument();
  });
});
