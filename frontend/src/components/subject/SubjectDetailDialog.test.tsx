// 交互优化二段：标的详情弹框完整版（弹框即终点）。
// 契约：弹框内展示与独立页 SubjectDetail 相同的完整内容——SubjectHeader 信息 +
// 行情/财务/估值/公告/新闻/政策/事件 7 分区（复用独立页分区组件，分页/加载更多交互沿独立页行为）；
// 「查看完整详情」链接移除（用户不再需要跳独立页）；取数沿 useSubjectDetail 两跳；三态齐备。

import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SubjectDetailDialog } from '@/components/subject/SubjectDetailDialog';
import type { SubjectDetailData } from '@/types/subject-detail';

const CODE = 'SZ000858';
const SUBJECT_ID = 100;
/** fixture 列表长度（旧核心版截断为 3；完整版应全量渲染）。 */
const LIST_SIZE = 4;
/** 首屏公告分页元数据（驱动公告分页条：108 源页 → maxPages 5 封顶）。 */
const ANNOUNCE_TOTAL = 1074;
/** 首屏事件分页元数据（驱动事件分页条：4 页）。 */
const EVENT_TOTAL = 37;
/** 完整版弹框分区数（行情/财务/估值/公告/新闻/政策/事件）。 */
const SECTION_COUNT = 7;

function mockResponse(status: number, body: unknown) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as unknown as Response;
}

/** 聚合详情样例：全分区 ok + 财务/估值有数 + sectionPagination 驱动公告/事件分页条。 */
function detailData(): SubjectDetailData {
  const range = Array.from({ length: LIST_SIZE }, (_, i) => i + 1);
  return {
    subject: {
      subjectCode: CODE,
      name: '五粮液',
      market: 'A_SHARE',
      type: 1,
      industry: '白酒',
    },
    quote: {
      price: 128.5,
      changePct: -1.25,
      open: 129.0,
      high: 130.0,
      low: 127.0,
      preClose: 130.1,
      volume: 21000000,
      amount: 2700000000,
      turnoverRate: 1.8,
      amplitude: 2.3,
      source: '行情源(eastmoney)',
      updatedAt: '2026-09-22 10:30:00',
    },
    finance: {
      revenue: 66200000000,
      netProfit: 24900000000,
      grossProfitMargin: 75.6,
      roe: 22.4,
      reportDate: '2026-06-30',
      source: '财务源(eastmoney)',
      updatedAt: '2026-09-22 08:00:00',
    },
    valuation: {
      peTtm: 15.8,
      pb: 4.2,
      source: '估值源(eastmoney)',
      updatedAt: '2026-09-22 10:30:00',
    },
    announcements: range.map((i) => ({
      id: `a${i}`,
      title: `五粮液公告${i}`,
      publishedAt: '2026-09-20',
      category: '其他',
      url: `https://example.com/a${i}`,
    })),
    news: range.map((i) => ({
      id: `n${i}`,
      externalId: `ext-n${i}`,
      title: `五粮液新闻${i}`,
      publishedAt: '2026-09-21',
      summary: `第 ${i} 条新闻摘要`,
      url: `https://example.com/n${i}`,
      source: '新浪财经',
    })),
    policies: {
      items: range.map((i) => ({
        id: i,
        title: `五粮液关联政策${i}`,
        url: `https://example.com/p${i}`,
        publishedAt: '2026-09-19',
        sourceName: '中国政府网',
        matchType: 'SUBJECT',
      })),
      fallback: null,
      basis: 'policy-scope-v1',
    },
    events: range.map((i) => ({
      anomalyType: 'PRICE_CHANGE',
      changePct: 3.25,
      triggerTime: `2026-09-21T02:0${i}:00Z`,
      detail: `五粮液异动${i}`,
    })),
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
        total: ANNOUNCE_TOTAL,
        paginationSupported: true,
        moreUrl: 'https://data.eastmoney.com/notices/stock/000858.html',
      },
      event: { total: EVENT_TOTAL },
    },
  };
}

interface StubOptions {
  detail?: SubjectDetailData | null;
  byCodeStatus?: number;
}

/** 按真实接口路径分发：by-code 解析 + 聚合 detail + 公告/新闻分区子端点。 */
function stubFetch({ detail = detailData(), byCodeStatus = 200 }: StubOptions = {}) {
  return vi.fn(async (url: string | URL) => {
    const path = String(url);
    if (path.includes(`/subjects/by-code/${CODE}`)) {
      if (byCodeStatus >= 400) {
        return mockResponse(byCodeStatus, {
          code: 30001,
          msg: '标的不存在',
          data: null,
        });
      }
      return mockResponse(200, {
        code: 0,
        msg: 'ok',
        data: { id: SUBJECT_ID, subjectCode: CODE, name: '五粮液', industry: '白酒' },
      });
    }
    if (path.includes(`/subjects/${SUBJECT_ID}/detail`)) {
      return mockResponse(200, { code: 0, msg: 'ok', data: detail, traceId: 't' });
    }
    const announcePage = /\/subjects\/\d+\/announcements\?page=(\d+)/.exec(path);
    if (announcePage) {
      const page = Number(announcePage[1]);
      return mockResponse(200, {
        code: 0,
        msg: 'ok',
        data: {
          items: [
            {
              id: `a-p${page}`,
              title: `五粮液公告页${page}`,
              publishedAt: '2026-09-18',
              category: '其他',
              url: `https://example.com/a-p${page}`,
            },
          ],
          page,
          size: 10,
          total: ANNOUNCE_TOTAL,
          paginationSupported: true,
          moreUrl: 'https://data.eastmoney.com/notices/stock/000858.html',
          sourceStatus: 'ok',
        },
      });
    }
    const newsPage = /\/subjects\/\d+\/news\?page=(\d+)/.exec(path);
    if (newsPage) {
      const page = Number(newsPage[1]);
      return mockResponse(200, {
        code: 0,
        msg: 'ok',
        data: {
          items: [
            {
              id: `n-p${page}`,
              externalId: `ext-n-p${page}`,
              title: `五粮液新闻第${page}页追加`,
              publishedAt: '2026-09-21',
              url: `https://example.com/n-p${page}`,
              source: '新浪财经',
            },
          ],
          page,
          size: 20,
          hasMore: false,
          sourceStatus: 'ok',
        },
      });
    }
    return mockResponse(200, { code: 0, msg: 'ok', data: null, traceId: 't' });
  });
}

function renderDialog(
  fetchMock: ReturnType<typeof stubFetch> = stubFetch(),
  props: { open?: boolean; subjectCode?: string | null } = {},
) {
  const onClose = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
  render(
    <SubjectDetailDialog
      open={props.open ?? true}
      subjectCode={props.subjectCode === undefined ? CODE : props.subjectCode}
      onClose={onClose}
    />,
  );
  return { fetchMock, onClose };
}

/** 子端点命中计数（分区独立翻页红线：翻 A 区 B 区零请求）。 */
function sectionCalls(fetchMock: ReturnType<typeof stubFetch>, keyword: string): string[] {
  return fetchMock.mock.calls.map((call) => String(call[0])).filter((url) => url.includes(keyword));
}

afterEach(() => {
  vi.unstubAllGlobals();
  cleanup();
  localStorage.clear();
  window.location.hash = '';
});

describe('SubjectDetailDialog 标的详情弹框（完整版·弹框即终点）', () => {
  it('关闭态：不渲染弹框、不发任何请求（打开才取数）', () => {
    const { fetchMock } = renderDialog(stubFetch(), { open: false });

    expect(screen.queryByTestId('dialog')).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('打开：先骨架后完整渲染——头部 + 7 分区全量内容 + 分页条，无「查看完整详情」链接', async () => {
    const { fetchMock } = renderDialog();

    // 骨架态
    expect(screen.getByTestId('subject-dialog-loading')).toBeInTheDocument();
    // 宽度覆盖：沿 ui/dialog contentClassName 先例，默认 max-w-md 被合并为 max-w-4xl（宽内容裁量）
    expect(screen.getByRole('dialog')).toHaveClass('max-w-4xl');
    expect(screen.getByRole('dialog')).not.toHaveClass('max-w-md');
    // 两跳取数：by-code 解析数字主键后请求聚合详情
    await screen.findByTestId('subject-dialog-content');
    const urls = fetchMock.mock.calls.map((c) => String(c[0]));
    expect(urls.some((u) => u.includes(`/subjects/by-code/${CODE}`))).toBe(true);
    expect(urls.some((u) => u.includes(`/subjects/${SUBJECT_ID}/detail`))).toBe(true);

    // 头部：与独立页同源 SubjectHeader（名称 / 代码 / 市场 / 行业 / AI 简报入口）
    const header = screen.getByTestId('subject-header');
    expect(within(header).getByText('五粮液')).toBeInTheDocument();
    expect(within(header).getByText(`代码：${CODE}`)).toBeInTheDocument();
    expect(within(header).getByText('行业：白酒')).toBeInTheDocument();
    expect(within(header).getByText('A 股')).toBeInTheDocument();
    expect(within(header).getByTestId('subject-goto-ai-brief')).toBeInTheDocument();

    // 7 分区卡片齐备（行情/财务/估值/公告/新闻/政策/事件）
    expect(screen.getAllByTestId('section-card')).toHaveLength(SECTION_COUNT);

    // 单源分区：行情/财务/估值指标全量透传
    expect(screen.getByTestId('quote-metrics')).toHaveTextContent('128.50');
    expect(screen.getByTestId('quote-metrics')).toHaveTextContent('-1.25%');
    expect(screen.getByTestId('finance-metrics')).toHaveTextContent('662.00亿');
    expect(screen.getByTestId('finance-metrics')).toHaveTextContent('249.00亿');
    expect(screen.getByTestId('finance-metrics')).toHaveTextContent('2026-06-30');
    expect(screen.getByTestId('valuation-metrics')).toHaveTextContent('15.80');
    expect(screen.getByTestId('valuation-metrics')).toHaveTextContent('4.20');

    // 四列表分区全量渲染（4 条不截断）+ 末条可见（旧核心版截断为 3）
    for (const listId of ['announce-list', 'news-list', 'policy-list', 'event-list']) {
      expect(within(screen.getByTestId(listId)).getAllByRole('listitem')).toHaveLength(LIST_SIZE);
    }
    expect(screen.getByText('五粮液公告4')).toBeInTheDocument();
    expect(screen.getByText('五粮液新闻4')).toBeInTheDocument();
    expect(screen.getByText('五粮液关联政策4')).toBeInTheDocument();
    expect(screen.getByText('五粮液异动4')).toBeInTheDocument();

    // 首屏分页元数据驱动分页条与交互入口（沿独立页行为）
    expect(screen.getByTestId('announce-pagination-total')).toHaveTextContent(
      `共 ${ANNOUNCE_TOTAL} 条`,
    );
    expect(screen.getByTestId('announce-pagination-page-indicator')).toHaveTextContent('第 1 / 5 页');
    expect(screen.getByTestId('event-pagination-total')).toHaveTextContent(
      `共 ${EVENT_TOTAL} 条（近 7 天）`,
    );
    expect(screen.getByTestId('news-load-more')).toBeInTheDocument();
    expect(screen.getByTestId('policy-more-link')).toBeInTheDocument();

    // 弹框即终点：无「查看完整详情」链接（不再跳独立页）
    expect(screen.queryByTestId('subject-dialog-full-detail')).toBeNull();
    expect(screen.queryByText(/查看完整详情/)).toBeNull();
  });

  it('分区数据驱动：政策空关联段走宏观兜底段，finance 缺数分区级降级不阻断其他分区', async () => {
    const data = detailData();
    data.policies = {
      items: [],
      fallback: {
        items: [
          {
            id: 9,
            title: '宏观政策兜底条目',
            url: null,
            publishedAt: '2026-09-18',
            sourceName: '国务院',
          },
        ],
        note: '无直接关联',
      },
      basis: 'policy-scope-v1',
    };
    data.finance = null;
    data.sourceStatus.finance = 'missing';
    renderDialog(stubFetch({ detail: data }));

    await screen.findByTestId('subject-dialog-content');
    // 政策兜底段（口径注记 + 兜底条目）
    expect(screen.getByTestId('policy-fallback')).toBeInTheDocument();
    expect(screen.getByText('宏观政策兜底条目')).toBeInTheDocument();
    // finance missing 分区级降级，其他分区不受影响
    expect(screen.getByTestId('fallback-missing')).toBeInTheDocument();
    expect(screen.queryByTestId('finance-metrics')).toBeNull();
    expect(screen.getByTestId('quote-metrics')).toBeInTheDocument();
    expect(screen.getByTestId('valuation-metrics')).toBeInTheDocument();
  });

  it('分区交互沿独立页：公告翻页仅打公告子端点、内容切换（B 区零请求）', async () => {
    const { fetchMock } = renderDialog();
    await screen.findByTestId('subject-dialog-content');

    const user = userEvent.setup();
    await user.click(screen.getByTestId('announce-pagination-page-2'));

    await waitFor(() => {
      expect(screen.getByTestId('announce-pagination-page-indicator')).toHaveTextContent(
        '第 2 / 5 页',
      );
    });
    expect(screen.getByText('五粮液公告页2')).toBeInTheDocument();
    expect(screen.queryByText('五粮液公告1')).toBeNull();
    // 分区独立翻页红线：仅 1 次公告子端点请求，事件/新闻/聚合零新增
    expect(sectionCalls(fetchMock, '/announcements')).toHaveLength(1);
    expect(sectionCalls(fetchMock, `/subjects/${SUBJECT_ID}/announcements?page=2`)).toHaveLength(1);
    expect(sectionCalls(fetchMock, '/events')).toHaveLength(0);
    expect(sectionCalls(fetchMock, '/news')).toHaveLength(0);
    expect(sectionCalls(fetchMock, '/detail')).toHaveLength(1);
  });

  it('分区交互沿独立页：新闻「加载更多」探页追加 + 停止终态', async () => {
    const { fetchMock } = renderDialog();
    await screen.findByTestId('subject-dialog-content');

    const user = userEvent.setup();
    await user.click(screen.getByTestId('news-load-more'));

    await waitFor(() => {
      expect(screen.getByText('五粮液新闻第2页追加')).toBeInTheDocument();
    });
    const list = screen.getByTestId('news-list');
    expect(within(list).getAllByRole('listitem')).toHaveLength(LIST_SIZE + 1);
    // hasMore=false → 停止终态（同标的会话内不再外呼）
    expect(screen.getByTestId('news-no-more')).toBeInTheDocument();
    expect(sectionCalls(fetchMock, `/subjects/${SUBJECT_ID}/news?page=2`)).toHaveLength(1);
    expect(sectionCalls(fetchMock, '/announcements')).toHaveLength(0);
  });

  it('子级交互：AI 简报带参跳转（数字主键预填）', async () => {
    renderDialog();
    await screen.findByTestId('subject-dialog-content');

    const user = userEvent.setup();
    await user.click(screen.getByTestId('subject-goto-ai-brief'));

    await waitFor(() =>
      expect(window.location.hash).toBe(`#/ai-brief?subjectId=${SUBJECT_ID}`),
    );
  });

  it('子级交互：政策「全部政策 →」出口跳资讯库 L1 预填', async () => {
    renderDialog();
    await screen.findByTestId('subject-dialog-content');

    const user = userEvent.setup();
    await user.click(screen.getByTestId('policy-more-link'));

    // hash 写入后非 ASCII 以百分号编码回读，解码断言
    expect(decodeURIComponent(window.location.hash)).toBe('#/news-library?l1=监管·政策');
  });

  it('错误态：友好文案 + 重试；重试后恢复渲染', async () => {
    let failed = true;
    const base = stubFetch();
    const fetchMock = vi.fn(async (url: string | URL) => {
      if (failed && String(url).includes('/by-code/')) {
        failed = false; // 仅首跳失败：重试同 code 重放即恢复
        return mockResponse(503, { code: 50000, msg: 'service unavailable', data: null });
      }
      return base(url);
    });
    renderDialog(fetchMock);

    const error = await screen.findByTestId('subject-dialog-error');
    expect(error).toBeInTheDocument();
    // 不直出技术串
    expect(screen.queryByText(/service unavailable/)).toBeNull();

    const user = userEvent.setup();
    await user.click(screen.getByTestId('subject-dialog-retry'));

    expect(await screen.findByTestId('subject-dialog-content')).toBeInTheDocument();
  });

  it('空态：详情 data 为 null 时兜底文案不崩', async () => {
    renderDialog(stubFetch({ detail: null }));

    expect(await screen.findByTestId('subject-dialog-empty')).toBeInTheDocument();
    expect(screen.getByText('暂无该标的数据')).toBeInTheDocument();
  });

  it('关闭交互：× 按钮与 Escape 均回调 onClose（受控显隐交由调用方）', async () => {
    const { onClose } = renderDialog();
    await screen.findByTestId('subject-dialog-content');

    const user = userEvent.setup();
    await user.click(screen.getByTestId('dialog-close'));
    expect(onClose).toHaveBeenCalledTimes(1);

    await user.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalledTimes(2);
  });

  it('open 但 subjectCode 为 null：不取数不渲染（调用方守门契约）', () => {
    const { fetchMock } = renderDialog(stubFetch(), { subjectCode: null });

    expect(screen.queryByTestId('dialog')).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
