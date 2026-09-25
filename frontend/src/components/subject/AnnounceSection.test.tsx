// M12 T95：公告分区接入——分区分页条（maxPages=5）+ 巨潮降级 + 更多历史外链 + 越界回退。
// 独立性（仅本分区刷新）在 SubjectDetail.sections.test 断言，此处聚焦分区自身交互。

import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AnnounceSection } from '@/components/subject/AnnounceSection';
import type { Announcement, AnnouncementPageView } from '@/types/subject-detail';

const SUBJECT_ID = 1;
const MORE_URL = 'https://data.eastmoney.com/notices/stock/600519.html';

function announceItem(n: number): Announcement {
  return {
    title: `公告 ${n}`,
    publishedAt: `2026-08-${String(n).padStart(2, '0')}T00:00:00`,
    category: '其他',
    url: `https://example.com/an${n}`,
  };
}

function pageView(overrides: Partial<AnnouncementPageView> = {}): AnnouncementPageView {
  return {
    items: [announceItem(21)],
    page: 2,
    size: 10,
    total: 1074,
    paginationSupported: true,
    moreUrl: MORE_URL,
    sourceStatus: 'ok',
    source: '东方财富公告',
    ...overrides,
  };
}

/** 构造类 Response 的 mock（只暴露 request() 用到的 ok/status/json）。 */
function mockResponse(status: number, body: unknown) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as unknown as Response;
}

/** 响应队列项：视图 / 网络拒绝 / 降级响应（200 + sourceStatus 非 ok）。 */
type QueueItem =
  | { kind: 'view'; data: AnnouncementPageView }
  | { kind: 'reject' }
  | { kind: 'degraded'; status: 'failed' | 'timeout' };

interface RenderOptions {
  queue?: QueueItem[];
  data?: Announcement[];
  status?: Parameters<typeof AnnounceSection>[0]['status'];
  pagination?: Parameters<typeof AnnounceSection>[0]['pagination'];
}

function renderSection(options: RenderOptions = {}) {
  const { queue = [], data = [announceItem(1), announceItem(2)], status = 'ok' } = options;
  const fetchMock = vi.fn(async (url: string | URL) => {
    const path = String(url);
    if (path.includes(`/subjects/${SUBJECT_ID}/announcements`)) {
      const item = queue.shift();
      if (item?.kind === 'reject') return Promise.reject(new TypeError('network down'));
      if (item?.kind === 'degraded') {
        return mockResponse(200, {
          code: 0,
          msg: 'ok',
          data: pageView({ items: [], sourceStatus: item.status }),
        });
      }
      return mockResponse(200, { code: 0, msg: 'ok', data: item?.data ?? pageView() });
    }
    return mockResponse(200, { code: 0, msg: 'ok', data: null });
  });
  localStorage.setItem('access_token', 'jwt-test');
  vi.stubGlobal('fetch', fetchMock);
  render(
    <AnnounceSection
      data={data}
      status={status}
      subjectId={SUBJECT_ID}
      pagination={
        options.pagination ?? { total: 1074, paginationSupported: true, moreUrl: MORE_URL }
      }
    />,
  );
  return fetchMock;
}

afterEach(() => {
  vi.unstubAllGlobals();
  cleanup();
  localStorage.clear();
});

describe('AnnounceSection 公告分区分页（M12 T95）', () => {
  it('首屏 sectionPagination 驱动：共 1074 条 · 第 1/5 页 · 页码 1~5 · 无条数选择器 · 零子端点请求', () => {
    const fetchMock = renderSection();

    expect(screen.getByTestId('announce-pagination-total')).toHaveTextContent('共 1074 条');
    expect(screen.getByTestId('announce-pagination-page-indicator')).toHaveTextContent(
      '第 1 / 5 页',
    );
    for (let n = 1; n <= 5; n++) {
      expect(screen.getByTestId(`announce-pagination-page-${n}`)).toBeInTheDocument();
    }
    expect(screen.queryByTestId('announce-pagination-size')).toBeNull();
    expect(screen.queryByTestId('announce-more-history')).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('翻页调子端点并整体替换：第 2 页条目落地、首屏条目移除、滚动容器回顶', async () => {
    const user = userEvent.setup();
    const fetchMock = renderSection({
      queue: [{ kind: 'view', data: pageView({ page: 2, items: [announceItem(11)] }) }],
    });

    const list = screen.getByTestId('announce-list');
    const scroller = list.parentElement as HTMLElement;
    scroller.scrollTop = 120;

    await user.click(screen.getByTestId('announce-pagination-page-2'));

    await waitFor(() => {
      expect(screen.getByTestId('announce-pagination-page-indicator')).toHaveTextContent(
        '第 2 / 5 页',
      );
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(String(fetchMock.mock.calls[0]?.[0])).toContain(
      `/subjects/${SUBJECT_ID}/announcements?page=2`,
    );
    expect(within(list).getAllByRole('listitem')).toHaveLength(1);
    expect(within(list).getByText('公告 11')).toBeInTheDocument();
    expect(screen.queryByText('公告 1')).toBeNull();
    expect(scroller.scrollTop).toBe(0);
  });

  it('翻页在途：控件禁用 + 「加载中…」提示行；成功后恢复', async () => {
    let resolveFetch!: (value: unknown) => void;
    const fetchMock = vi.fn(
      () =>
        new Promise((resolve) => {
          resolveFetch = resolve;
        }),
    );
    localStorage.setItem('access_token', 'jwt-test');
    vi.stubGlobal('fetch', fetchMock);
    render(
      <AnnounceSection
        data={[announceItem(1)]}
        status="ok"
        subjectId={SUBJECT_ID}
        pagination={{ total: 1074, paginationSupported: true, moreUrl: MORE_URL }}
      />,
    );

    const user = userEvent.setup();
    await user.click(screen.getByTestId('announce-pagination-next'));
    await waitFor(() => expect(screen.getByText('加载中…')).toBeInTheDocument());
    expect(screen.getByTestId('announce-pagination-next')).toBeDisabled();
    expect(screen.getByTestId('announce-pagination-prev')).toBeDisabled();
    expect(screen.getByTestId('announce-pagination-page-3')).toBeDisabled();

    resolveFetch(
      mockResponse(200, {
        code: 0,
        msg: 'ok',
        data: pageView({ page: 2, items: [announceItem(11)] }),
      }),
    );
    await waitFor(() => {
      expect(screen.getByTestId('announce-pagination-page-indicator')).toHaveTextContent(
        '第 2 / 5 页',
      );
    });
    expect(screen.queryByText('加载中…')).toBeNull();
    expect(screen.getByTestId('announce-pagination-next')).toBeEnabled();
  });

  it('翻页失败：保留当前页内容 + 错误行 + 重试重发同目标页', async () => {
    const user = userEvent.setup();
    const fetchMock = renderSection({
      queue: [
        { kind: 'reject' },
        { kind: 'view', data: pageView({ page: 2, items: [announceItem(11)] }) },
      ],
    });

    await user.click(screen.getByTestId('announce-pagination-page-2'));

    const errorRow = await screen.findByTestId('announce-pagination-error');
    expect(errorRow).toHaveTextContent('加载第 2 页失败');
    expect(screen.getByTestId('announce-pagination-page-indicator')).toHaveTextContent(
      '第 1 / 5 页',
    );
    expect(screen.getByText('公告 1')).toBeInTheDocument();

    await user.click(screen.getByTestId('announce-pagination-retry'));

    await waitFor(() => {
      expect(screen.getByTestId('announce-pagination-page-indicator')).toHaveTextContent(
        '第 2 / 5 页',
      );
    });
    const page2Calls = fetchMock.mock.calls
      .map((call) => String(call[0]))
      .filter((url) => url.includes('announcements?page=2'));
    expect(page2Calls).toHaveLength(2);
  });

  it('巨潮降级首屏：分页条替换为「当前公告源不支持翻页」，无页码无更多历史链接', () => {
    renderSection({
      pagination: { total: null, paginationSupported: false, moreUrl: MORE_URL },
    });

    expect(screen.getByTestId('announce-pagination-unsupported')).toHaveTextContent(
      '当前公告源不支持翻页',
    );
    expect(screen.queryByTestId('announce-pagination-root')).toBeNull();
    expect(screen.queryByTestId('announce-more-history')).toBeNull();
    expect(screen.getByText('公告 1')).toBeInTheDocument();
  });

  it('翻页中途巨潮降级：保留当前内容，切降级文案（不视为错误）', async () => {
    const user = userEvent.setup();
    renderSection({
      queue: [
        {
          kind: 'view',
          data: pageView({
            page: 2,
            items: [],
            total: null,
            paginationSupported: false,
          }),
        },
      ],
    });

    await user.click(screen.getByTestId('announce-pagination-page-2'));

    expect(await screen.findByTestId('announce-pagination-unsupported')).toBeVisible();
    expect(screen.queryByTestId('announce-pagination-error')).toBeNull();
    expect(screen.getByText('公告 1')).toBeInTheDocument();
  });

  it('末页且截断：渲染「更多历史公告」源站外链（新窗口 + noopener）；下一页禁用', async () => {
    const user = userEvent.setup();
    renderSection({
      queue: [{ kind: 'view', data: pageView({ page: 5, items: [announceItem(41)] }) }],
    });

    await user.click(screen.getByTestId('announce-pagination-page-5'));

    const link = await screen.findByTestId('announce-more-history');
    expect(link).toHaveAttribute('href', MORE_URL);
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    expect(link).toHaveTextContent('更多历史公告');
    expect(screen.getByTestId('announce-pagination-next')).toBeDisabled();
  });

  it('未截断（total ≤ pageSize × maxPages）：末页不渲染「更多历史公告」', async () => {
    const user = userEvent.setup();
    renderSection({
      queue: [
        {
          kind: 'view',
          data: pageView({ page: 4, items: [announceItem(31)], total: 40 }),
        },
      ],
      pagination: { total: 40, paginationSupported: true, moreUrl: MORE_URL },
    });

    await user.click(screen.getByTestId('announce-pagination-page-4'));

    await waitFor(() => {
      expect(screen.getByTestId('announce-pagination-page-indicator')).toHaveTextContent(
        '第 4 / 4 页',
      );
    });
    expect(screen.queryByTestId('announce-more-history')).toBeNull();
  });

  it('越界空页：静默回第 1 页（自动重发 page=1）', async () => {
    const user = userEvent.setup();
    const fetchMock = renderSection({
      queue: [
        { kind: 'view', data: pageView({ page: 2, items: [] }) },
        { kind: 'view', data: pageView({ page: 1, items: [announceItem(1)] }) },
      ],
    });

    await user.click(screen.getByTestId('announce-pagination-page-2'));

    await waitFor(() => {
      expect(screen.getByTestId('announce-pagination-page-indicator')).toHaveTextContent(
        '第 1 / 5 页',
      );
    });
    const urls = fetchMock.mock.calls.map((call) => String(call[0]));
    expect(urls.some((url) => url.includes('announcements?page=2'))).toBe(true);
    expect(urls.some((url) => url.includes('announcements?page=1'))).toBe(true);
    expect(screen.getByText('公告 1')).toBeInTheDocument();
  });

  it('非 ok 分区：三态兜底不渲染任何翻页控件（PRD 场景 5.3）', () => {
    renderSection({ status: 'failed' });

    expect(screen.getByTestId('fallback-failed')).toBeInTheDocument();
    expect(screen.queryByTestId('announce-pagination-root')).toBeNull();
    expect(screen.queryByTestId('announce-pagination-unsupported')).toBeNull();
  });
});
