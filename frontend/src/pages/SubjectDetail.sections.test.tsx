// M12 T95：详情页四分区接入集成——首屏 sectionPagination 驱动 / 分区独立翻页（验收红线：
// 翻 A 区 B 区零请求）/ 切标的 key 重挂载重置 / 翻页不触发新阅读埋点（PRD 故事 5）。
// 七分区三态/外链/AI 简报等既有回归在 SubjectDetail.test.tsx（存量零改动）。

import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { resetReadingTrackerForTest } from '@/api/readingEvent';
import { SubjectDetail } from '@/pages/SubjectDetail';
import type { SubjectDetailData } from '@/types/subject-detail';

const CODE_A = 'SH600519';
const CODE_B = 'SZ000001';
const ID_A = 1;
const ID_B = 2;

/** 构造类 Response 的 mock（只暴露 request() 用到的 ok/status/json）。 */
function mockResponse(status: number, body: unknown) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as unknown as Response;
}

function detailData(code: string, name: string): SubjectDetailData {
  return {
    subject: { subjectCode: code, name, market: 'A_SHARE', type: 1, industry: '白酒' },
    quote: { price: 1701.5, changePct: 1.25 },
    announcements: [{ title: `${code} 首屏公告`, publishedAt: '2026-08-15' }],
    news: [{ externalId: `${code}-n1`, title: `${code} 首屏新闻`, publishedAt: '2026-09-21' }],
    policies: [{ title: `${code} 命中政策`, publishedAt: '2026-09-20' }],
    events: [
      { anomalyType: 'PRICE_CHANGE', changePct: 3.25, triggerTime: '2026-09-21T02:00:00Z' },
    ],
    sourceStatus: {
      quote: 'ok',
      finance: 'ok',
      valuation: 'ok',
      announce: 'ok',
      news: 'ok',
      policy: 'ok',
      event: 'ok',
    },
    sectionPagination: {
      announce: {
        total: 1074,
        paginationSupported: true,
        moreUrl: 'https://data.eastmoney.com/notices/stock/600519.html',
      },
      event: { total: 37 },
    },
  };
}

/** 公告子端点响应。 */
function announcePage(code: string, page: number) {
  return {
    items: [{ title: `${code} 公告页 ${page}`, publishedAt: '2026-08-10' }],
    page,
    size: 10,
    total: 1074,
    paginationSupported: true,
    moreUrl: 'https://data.eastmoney.com/notices/stock/600519.html',
    sourceStatus: 'ok',
  };
}

function eventPage(page: number) {
  return {
    items: [{ anomalyType: 'VOLUME', triggerTime: '2026-09-19T02:00:00Z' }],
    page,
    size: 10,
    total: 37,
    sourceStatus: 'ok',
  };
}

function newsPage(code: string, page: number) {
  return {
    items: [{ externalId: `${code}-n${page}`, title: `${code} 追加新闻 ${page}`, publishedAt: '2026-09-20' }],
    page,
    size: 20,
    hasMore: true,
    sourceStatus: 'ok',
  };
}

/**
 * 按真实接口路径分发：by-code 解析 + 聚合 detail + 三个分区子端点 + 旁路（阅读埋点等）。
 */
function stubFetch(detail: Record<string, SubjectDetailData>) {
  return vi.fn(async (url: string | URL) => {
    const path = String(url);
    const byCode = /\/subjects\/by-code\/(\w+)/.exec(path);
    if (byCode) {
      const code = byCode[1] as keyof typeof detail;
      const subject = detail[code]?.subject;
      const id = code === CODE_B ? ID_B : ID_A;
      return mockResponse(200, {
        code: 0,
        msg: 'ok',
        data: { id, subjectCode: code, name: subject?.name ?? '标的' },
      });
    }
    const sections = /\/subjects\/(\d+)\/announcements\?page=(\d+)/.exec(path);
    if (sections) {
      const id = Number(sections[1]);
      return mockResponse(200, {
        code: 0,
        msg: 'ok',
        data: announcePage(id === ID_B ? CODE_B : CODE_A, Number(sections[2])),
      });
    }
    const events = /\/subjects\/(\d+)\/events\?page=(\d+)/.exec(path);
    if (events) {
      return mockResponse(200, { code: 0, msg: 'ok', data: eventPage(Number(events[2])) });
    }
    const news = /\/subjects\/(\d+)\/news\?page=(\d+)/.exec(path);
    if (news) {
      const id = Number(news[1]);
      return mockResponse(200, {
        code: 0,
        msg: 'ok',
        data: newsPage(id === ID_B ? CODE_B : CODE_A, Number(news[2])),
      });
    }
    const detailMatch = /\/subjects\/(\d+)\/detail/.exec(path);
    if (detailMatch) {
      const id = Number(detailMatch[1]);
      const data = id === ID_B ? detail[CODE_B] : detail[CODE_A];
      return mockResponse(200, { code: 0, msg: 'ok', data, traceId: 't' });
    }
    // 阅读埋点等旁路请求：统一成功空响应
    return mockResponse(200, { code: 0, msg: 'ok', data: null, traceId: 't' });
  });
}

function renderPage(fetchMock: ReturnType<typeof stubFetch>, code: string) {
  localStorage.setItem('access_token', 'jwt-test');
  vi.stubGlobal('fetch', fetchMock);
  return render(<SubjectDetail subjectId={code} />);
}

function sectionCalls(fetchMock: ReturnType<typeof stubFetch>, keyword: string) {
  return fetchMock.mock.calls.map((call) => String(call[0])).filter((url) => url.includes(keyword));
}

afterEach(() => {
  vi.unstubAllGlobals();
  cleanup();
  localStorage.clear();
  window.location.hash = '';
  resetReadingTrackerForTest();
});

describe('SubjectDetail 分区分页接入（M12 T95）', () => {
  it('首屏 sectionPagination 驱动：公告/事件分页条直接渲染，不发第二次请求', async () => {
    const fetchMock = stubFetch({ [CODE_A]: detailData(CODE_A, '贵州茅台') });
    renderPage(fetchMock, CODE_A);

    expect(await screen.findByText('贵州茅台')).toBeInTheDocument();
    expect(screen.getByTestId('announce-pagination-total')).toHaveTextContent('共 1074 条');
    expect(screen.getByTestId('announce-pagination-page-indicator')).toHaveTextContent(
      '第 1 / 5 页',
    );
    expect(screen.getByTestId('event-pagination-total')).toHaveTextContent('共 37 条（近 7 天）');
    expect(screen.getByTestId('news-load-more')).toBeInTheDocument();
    expect(screen.getByTestId('policy-more-link')).toBeInTheDocument();
    // 仅 by-code + detail 两跳 + 首屏阅读埋点一次，零子端点请求
    await vi.waitFor(() => {
      expect(sectionCalls(fetchMock, 'reading-events')).toHaveLength(1);
    });
    expect(fetchMock.mock.calls).toHaveLength(3);
    expect(sectionCalls(fetchMock, '/announcements')).toHaveLength(0);
    expect(sectionCalls(fetchMock, '/events')).toHaveLength(0);
    expect(sectionCalls(fetchMock, '/news')).toHaveLength(0);
  });

  it('分区独立翻页（验收红线）：翻公告页仅 1 次公告子端点请求，事件/新闻/聚合零请求、内容不变', async () => {
    const fetchMock = stubFetch({ [CODE_A]: detailData(CODE_A, '贵州茅台') });
    renderPage(fetchMock, CODE_A);
    await screen.findByText('贵州茅台');

    const user = userEvent.setup();
    await user.click(screen.getByTestId('announce-pagination-page-2'));

    await waitFor(() => {
      expect(screen.getByTestId('announce-pagination-page-indicator')).toHaveTextContent(
        '第 2 / 5 页',
      );
    });
    expect(screen.getByText(`${CODE_A} 公告页 2`)).toBeInTheDocument();
    // 仅新增 1 次公告子端点请求（page=2），聚合/事件/新闻零请求、埋点不新增
    expect(sectionCalls(fetchMock, '/announcements')).toHaveLength(1);
    expect(sectionCalls(fetchMock, '/events')).toHaveLength(0);
    expect(sectionCalls(fetchMock, '/news')).toHaveLength(0);
    expect(sectionCalls(fetchMock, '/detail')).toHaveLength(1);
    expect(sectionCalls(fetchMock, 'reading-events')).toHaveLength(1);
    // 其他分区内容不闪变
    expect(screen.getByText(`${CODE_A} 首屏新闻`)).toBeInTheDocument();
    expect(screen.getByText(`${CODE_A} 命中政策`)).toBeInTheDocument();
    expect(screen.getByText('3.25%')).toBeInTheDocument();
  });

  it('事件分区独立翻页：仅事件子端点请求，公告分区页码不动', async () => {
    const fetchMock = stubFetch({ [CODE_A]: detailData(CODE_A, '贵州茅台') });
    renderPage(fetchMock, CODE_A);
    await screen.findByText('贵州茅台');

    const user = userEvent.setup();
    await user.click(screen.getByTestId('event-pagination-page-2'));

    await waitFor(() => {
      expect(screen.getByTestId('event-pagination-page-indicator')).toHaveTextContent(
        '第 2 / 4 页',
      );
    });
    expect(sectionCalls(fetchMock, '/events')).toHaveLength(1);
    expect(sectionCalls(fetchMock, '/announcements')).toHaveLength(0);
    expect(sectionCalls(fetchMock, '/news')).toHaveLength(0);
    expect(sectionCalls(fetchMock, '/detail')).toHaveLength(1);
    expect(screen.getByTestId('announce-pagination-page-indicator')).toHaveTextContent(
      '第 1 / 5 页',
    );
    expect(screen.getByText(`${CODE_A} 首屏公告`)).toBeInTheDocument();
  });

  it('新闻加载更多与埋点回归：追加成功且不产生新的 SUBJECT_DETAIL 阅读埋点', async () => {
    const fetchMock = stubFetch({ [CODE_A]: detailData(CODE_A, '贵州茅台') });
    renderPage(fetchMock, CODE_A);
    await screen.findByText('贵州茅台');
    // 首屏埋点恰一次
    await vi.waitFor(() => {
      expect(sectionCalls(fetchMock, 'reading-events')).toHaveLength(1);
    });

    const user = userEvent.setup();
    await user.click(screen.getByTestId('news-load-more'));

    const list = screen.getByTestId('news-list');
    await waitFor(() => {
      expect(within(list).getAllByRole('listitem')).toHaveLength(2);
    });
    expect(screen.getByText(`${CODE_A} 追加新闻 2`)).toBeInTheDocument();
    expect(sectionCalls(fetchMock, '/news')).toHaveLength(1);
    // 翻页/加载不触发新埋点（会话级 once）
    expect(sectionCalls(fetchMock, 'reading-events')).toHaveLength(1);
  });

  it('切换标的 key 重挂载重置：公告回到第 1 页、新闻回到首屏命中（不残留上一标的状态）', async () => {
    const fetchMock = stubFetch({
      [CODE_A]: detailData(CODE_A, '贵州茅台'),
      [CODE_B]: detailData(CODE_B, '平安银行'),
    });
    const { rerender } = renderPage(fetchMock, CODE_A);
    await screen.findByText('贵州茅台');

    const user = userEvent.setup();
    await user.click(screen.getByTestId('announce-pagination-page-2'));
    await waitFor(() => {
      expect(screen.getByTestId('announce-pagination-page-indicator')).toHaveTextContent(
        '第 2 / 5 页',
      );
    });

    // App.tsx 的 key={subjectId} 重挂载语义：key 变化即整树重建
    rerender(<SubjectDetail key={CODE_B} subjectId={CODE_B} />);

    expect(await screen.findByText('平安银行')).toBeInTheDocument();
    expect(screen.getByTestId('announce-pagination-page-indicator')).toHaveTextContent(
      '第 1 / 5 页',
    );
    expect(screen.getByText(`${CODE_B} 首屏公告`)).toBeInTheDocument();
    expect(screen.queryByText(`${CODE_A} 公告页 2`)).toBeNull();
    const newsList = screen.getByTestId('news-list');
    expect(within(newsList).getAllByRole('listitem')).toHaveLength(1);
    expect(screen.getByText(`${CODE_B} 首屏新闻`)).toBeInTheDocument();
  });

  it('非 ok 分区不渲染分页控件：公告 failed 时无分页条（sectionPagination 不产出）', async () => {
    const data = detailData(CODE_A, '贵州茅台');
    data.sourceStatus.announce = 'failed';
    data.sectionPagination = { event: { total: 37 } };
    const fetchMock = stubFetch({ [CODE_A]: data });
    renderPage(fetchMock, CODE_A);

    expect(await screen.findByTestId('fallback-failed')).toBeInTheDocument();
    expect(screen.queryByTestId('announce-pagination-root')).toBeNull();
    expect(screen.getByTestId('event-pagination-total')).toHaveTextContent('共 37 条（近 7 天）');
  });
});
