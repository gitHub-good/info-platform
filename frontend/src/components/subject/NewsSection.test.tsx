// M12 T95：新闻分区接入——「加载更多相关新闻」两态 + 去重不渲染 + 停止文案。
// 探页状态机三路停止的完整覆盖在 hooks/useSectionPage.test，此处聚焦分区渲染交互。

import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NewsSection } from '@/components/subject/NewsSection';
import type { NewsItem, NewsPageView } from '@/types/subject-detail';

const SUBJECT_ID = 1;

function newsItem(id: string, title?: string): NewsItem {
  return { externalId: id, title: title ?? `新闻 ${id}`, publishedAt: '2026-09-21' };
}

function pageView(items: NewsItem[], hasMore: boolean, page = 2): NewsPageView {
  return { items, page, size: 20, hasMore, sourceStatus: 'ok' };
}

/** 构造类 Response 的 mock（只暴露 request() 用到的 ok/status/json）。 */
function mockResponse(status: number, body: unknown) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as unknown as Response;
}

interface RenderOptions {
  newsQueue?: NewsPageView[];
  rejectFirst?: boolean;
  data?: NewsItem[] | null;
  status?: Parameters<typeof NewsSection>[0]['status'];
}

function renderSection(options: RenderOptions = {}) {
  const { newsQueue = [], rejectFirst = false, data = [newsItem('n1'), newsItem('n2')], status = 'ok' } =
    options;
  let rejected = false;
  const fetchMock = vi.fn(async (url: string | URL) => {
    const path = String(url);
    if (path.includes(`/subjects/${SUBJECT_ID}/news`)) {
      if (rejectFirst && !rejected) {
        rejected = true;
        return Promise.reject(new TypeError('network down'));
      }
      const next = newsQueue.shift() ?? pageView([], false);
      return mockResponse(200, { code: 0, msg: 'ok', data: next });
    }
    return mockResponse(200, { code: 0, msg: 'ok', data: null });
  });
  localStorage.setItem('access_token', 'jwt-test');
  vi.stubGlobal('fetch', fetchMock);
  render(<NewsSection data={data} status={status} subjectId={SUBJECT_ID} />);
  return fetchMock;
}

afterEach(() => {
  vi.unstubAllGlobals();
  cleanup();
  localStorage.clear();
  window.location.hash = '';
});

describe('NewsSection 新闻「加载更多」（M12 T95）', () => {
  it('首屏命中：渲染「加载更多相关新闻」按钮，不显总数与页码', () => {
    renderSection();

    expect(screen.getByTestId('news-list')).toBeInTheDocument();
    const button = screen.getByTestId('news-load-more');
    expect(button).toHaveTextContent('加载更多相关新闻');
    expect(button).toBeEnabled();
    expect(screen.queryByTestId('news-no-more')).toBeNull();
  });

  it('点击追加不重排：既有条目不动、新增接尾、首条新增滚入卡内视口', async () => {
    // jsdom 未实现 scrollIntoView：打桩（Policy.test 同款先例）
    const scrollIntoView = vi.fn();
    HTMLElement.prototype.scrollIntoView = scrollIntoView;
    const user = userEvent.setup();
    renderSection({ newsQueue: [pageView([newsItem('n3'), newsItem('n4')], true)] });

    const list = screen.getByTestId('news-list');
    const firstBefore = within(list).getAllByRole('listitem')[0];

    await user.click(screen.getByTestId('news-load-more'));

    const items = await waitFor(() => {
      const all = within(list).getAllByRole('listitem');
      expect(all).toHaveLength(4);
      return all;
    });
    expect(items[0]).toBe(firstBefore);
    expect(screen.getByText('新闻 n3')).toBeInTheDocument();
    expect(screen.getByText('新闻 n4')).toBeInTheDocument();
    expect(scrollIntoView).toHaveBeenCalledTimes(1);
    expect(scrollIntoView.mock.contexts[0]).toBe(items[2]);
  });

  it('在途两态：按钮 disabled + 文案「加载中…」+ aria-busy；成功恢复 idle', async () => {
    let resolveFetch!: (value: unknown) => void;
    const fetchMock = vi.fn(
      () =>
        new Promise((resolve) => {
          resolveFetch = resolve;
        }),
    );
    localStorage.setItem('access_token', 'jwt-test');
    vi.stubGlobal('fetch', fetchMock);
    render(<NewsSection data={[newsItem('n1')]} status="ok" subjectId={SUBJECT_ID} />);

    const user = userEvent.setup();
    await user.click(screen.getByTestId('news-load-more'));
    const button = screen.getByTestId('news-load-more');
    expect(button).toBeDisabled();
    expect(button).toHaveTextContent('加载中…');
    expect(button).toHaveAttribute('aria-busy', 'true');

    resolveFetch(
      mockResponse(200, { code: 0, msg: 'ok', data: pageView([newsItem('n3')], true) }),
    );
    await waitFor(() => {
      expect(screen.getByTestId('news-load-more')).toHaveTextContent('加载更多相关新闻');
    });
    expect(screen.getByTestId('news-load-more')).toBeEnabled();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('去重不渲染：跨源页重复条目（同 externalId）无「重复」视觉标记', async () => {
    const user = userEvent.setup();
    renderSection({
      newsQueue: [pageView([newsItem('n1', '重复旧闻'), newsItem('n3')], true)],
    });

    await user.click(screen.getByTestId('news-load-more'));

    await waitFor(() => {
      expect(within(screen.getByTestId('news-list')).getAllByRole('listitem')).toHaveLength(3);
    });
    expect(screen.queryByText('重复旧闻')).toBeNull();
  });

  it('停止终态：按钮移除，原位渲染「没有更多相关新闻」，不再发请求', async () => {
    const user = userEvent.setup();
    const fetchMock = renderSection({
      // 连续 2 空源页（hasMore 均真，排除源页耗尽条件）→ 纯「连续空页」停止路径
      newsQueue: [pageView([], true, 2), pageView([], true, 3)],
    });

    await user.click(screen.getByTestId('news-load-more'));

    expect(await screen.findByTestId('news-no-more')).toHaveTextContent(
      '没有更多相关新闻',
    );
    expect(screen.queryByTestId('news-load-more')).toBeNull();
    // 连续 2 空页后终态：入口已移除，请求计数定格
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('失败：保留已加载内容 + 错误行 + 重试重发同一探页', async () => {
    const user = userEvent.setup();
    const fetchMock = renderSection({
      rejectFirst: true,
      newsQueue: [pageView([newsItem('n3')], true)],
    });

    await user.click(screen.getByTestId('news-load-more'));

    const errorRow = await screen.findByTestId('news-load-more-error');
    expect(errorRow).toHaveTextContent('加载更多失败');
    expect(screen.getByText('新闻 n1')).toBeInTheDocument();

    await user.click(screen.getByTestId('news-load-more-retry'));

    await waitFor(() => {
      expect(within(screen.getByTestId('news-list')).getAllByRole('listitem')).toHaveLength(3);
    });
    const page2Calls = fetchMock.mock.calls
      .map((call) => String(call[0]))
      .filter((url) => url.includes('/news?page=2'));
    expect(page2Calls).toHaveLength(2);
  });

  it('首屏无命中（status ok 但空列表）：无按钮（PRD 场景 2.5）', () => {
    renderSection({ data: [] });

    expect(screen.queryByTestId('news-list')).toBeNull();
    expect(screen.queryByTestId('news-load-more')).toBeNull();
    expect(screen.queryByTestId('news-no-more')).toBeNull();
  });

  it('非 ok 分区：missing 兜底，无加载更多按钮（PRD 场景 5.3）', () => {
    renderSection({ status: 'missing', data: null });

    expect(screen.getByTestId('fallback-missing')).toBeInTheDocument();
    expect(screen.queryByTestId('news-load-more')).toBeNull();
  });
});
