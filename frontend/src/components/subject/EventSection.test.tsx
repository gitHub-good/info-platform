// M12 T95：事件分区接入——标准页码条 + totalLabel「共 N 条（近 7 天）」窗口口径总数。

import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { EventSection } from '@/components/subject/EventSection';
import type { EventItem, EventPageView } from '@/types/subject-detail';

const SUBJECT_ID = 1;

function eventItem(n: number): EventItem {
  return {
    anomalyType: 'PRICE_CHANGE',
    changePct: n,
    currentPrice: 1680,
    triggerTime: `2026-09-2${n % 10}T02:00:00Z`,
    detail: `异动 ${n}`,
  };
}

function pageView(overrides: Partial<EventPageView> = {}): EventPageView {
  return {
    items: [eventItem(11)],
    page: 2,
    size: 10,
    total: 37,
    sourceStatus: 'ok',
    source: '事件监控',
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

type QueueItem =
  | { kind: 'view'; data: EventPageView }
  | { kind: 'reject' };

interface RenderOptions {
  queue?: QueueItem[];
  data?: EventItem[];
  status?: Parameters<typeof EventSection>[0]['status'];
  total?: number;
}

function renderSection(options: RenderOptions = {}) {
  const { queue = [], data = [eventItem(1)], status = 'ok', total = 37 } = options;
  const fetchMock = vi.fn(async (url: string | URL) => {
    const path = String(url);
    if (path.includes(`/subjects/${SUBJECT_ID}/events`)) {
      const item = queue.shift();
      if (item?.kind === 'reject') return Promise.reject(new TypeError('network down'));
      return mockResponse(200, { code: 0, msg: 'ok', data: item?.data ?? pageView() });
    }
    return mockResponse(200, { code: 0, msg: 'ok', data: null });
  });
  localStorage.setItem('access_token', 'jwt-test');
  vi.stubGlobal('fetch', fetchMock);
  render(
    <EventSection data={data} status={status} subjectId={SUBJECT_ID} total={total} />,
  );
  return fetchMock;
}

afterEach(() => {
  vi.unstubAllGlobals();
  cleanup();
  localStorage.clear();
});

describe('EventSection 事件分区分页（M12 T95）', () => {
  it('首屏 total 驱动：共 37 条（近 7 天）· 第 1/4 页 · 无条数选择器 · 零子端点请求', () => {
    const fetchMock = renderSection();

    expect(screen.getByTestId('event-pagination-total')).toHaveTextContent(
      '共 37 条（近 7 天）',
    );
    expect(screen.getByTestId('event-pagination-page-indicator')).toHaveTextContent(
      '第 1 / 4 页',
    );
    expect(screen.getByTestId('event-pagination-page-1')).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(screen.queryByTestId('event-pagination-size')).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('翻页调子端点：列表整体替换 + total 以响应覆盖 + 滚动容器回顶', async () => {
    const user = userEvent.setup();
    const fetchMock = renderSection({
      queue: [{ kind: 'view', data: pageView({ page: 2, total: 36, items: [eventItem(11), eventItem(12)] }) }],
    });

    const list = screen.getByTestId('event-list');
    const scroller = list.parentElement as HTMLElement;
    scroller.scrollTop = 200;

    await user.click(screen.getByTestId('event-pagination-page-2'));

    await waitFor(() => {
      expect(screen.getByTestId('event-pagination-page-indicator')).toHaveTextContent(
        '第 2 / 4 页',
      );
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(String(fetchMock.mock.calls[0]?.[0])).toContain(
      `/subjects/${SUBJECT_ID}/events?page=2`,
    );
    expect(within(list).getAllByRole('listitem')).toHaveLength(2);
    expect(screen.getByTestId('event-pagination-total')).toHaveTextContent(
      '共 36 条（近 7 天）',
    );
    expect(scroller.scrollTop).toBe(0);
  });

  it('单页精简态（窗内 6 条）：共 6 条（近 7 天）· 第 1/1 页，无翻页按钮', () => {
    renderSection({ total: 6, data: [eventItem(1), eventItem(2)] });

    expect(screen.getByTestId('event-pagination-total')).toHaveTextContent(
      '共 6 条（近 7 天）',
    );
    expect(screen.getByTestId('event-pagination-page-indicator')).toHaveTextContent(
      '第 1 / 1 页',
    );
    expect(screen.queryByTestId('event-pagination-prev')).toBeNull();
    expect(screen.queryByTestId('event-pagination-next')).toBeNull();
  });

  it('翻页失败：保留当前内容 + 错误行 + 重试恢复', async () => {
    const user = userEvent.setup();
    renderSection({
      queue: [{ kind: 'reject' }, { kind: 'view', data: pageView({ page: 2 }) }],
    });

    await user.click(screen.getByTestId('event-pagination-page-2'));

    const errorRow = await screen.findByTestId('event-pagination-error');
    expect(errorRow).toHaveTextContent('加载第 2 页失败');
    expect(screen.getByText('异动 1')).toBeInTheDocument();

    await user.click(screen.getByTestId('event-pagination-retry'));

    await waitFor(() => {
      expect(screen.getByTestId('event-pagination-page-indicator')).toHaveTextContent(
        '第 2 / 4 页',
      );
    });
    expect(screen.getByText('异动 11')).toBeInTheDocument();
  });

  it('空窗/无 total：不渲染分页条（total=0 隐藏）', () => {
    renderSection({ total: 0, data: [] });

    expect(screen.queryByTestId('event-pagination-root')).toBeNull();
  });

  it('非 ok 分区：三态兜底不渲染分页条（PRD 场景 3.4/5.3）', () => {
    renderSection({ status: 'missing' });

    expect(screen.getByTestId('fallback-missing')).toBeInTheDocument();
    expect(screen.queryByTestId('event-pagination-root')).toBeNull();
  });
});
