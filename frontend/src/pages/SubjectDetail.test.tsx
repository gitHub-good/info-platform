import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { resetReadingTrackerForTest } from '@/api/readingEvent';
import { SubjectDetail } from '@/pages/SubjectDetail';
import type { SubjectDetailData } from '@/types/subject-detail';

// P0-1 回归：详情页直连真实聚合接口（fetch 全链路），不再消费前端 mock 层。
// fixture 对齐 8080 实测真实响应形态（估值 peTtm / 财务 grossProfitMargin+reportDate 等真实键）。

const CODE = 'SH600519';
const SUBJECT_ID = 1;

/** 构造类 Response 的 mock（只暴露 request() 用到的 ok/status/json）。 */
function mockResponse(status: number, body: unknown) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as unknown as Response;
}

/** 真实聚合接口响应样例（sourceStatus 混合态：5 ok / 1 missing / 1 failed）。 */
function realDetailData(): SubjectDetailData {
  return {
    subject: { subjectCode: CODE, name: '贵州茅台', market: 'A_SHARE', type: 1, industry: '白酒' },
    quote: {
      price: 1701.5,
      changePct: 1.25,
      open: 1688.0,
      high: 1710.0,
      low: 1685.0,
      preClose: 1680.5,
      volume: 21000000,
      amount: 3580000000,
      turnoverRate: 0.17,
      amplitude: 1.49,
      source: '行情源(eastmoney)',
      updatedAt: '2026-09-22 10:30:00',
    },
    finance: {
      revenue: 127560000000,
      netProfit: 86280000000,
      grossProfitMargin: 91.4,
      roe: 30.55,
      reportDate: '2026-06-30',
      source: '财务源(eastmoney)',
    },
    valuation: { peTtm: 17.57, pb: 6.23, source: '估值源(eastmoney)' },
    announcements: [
      {
        title: '贵州茅台2026年半年度报告摘要',
        publishedAt: '2026-08-15T00:00:00',
        category: '半年报摘要',
        url: 'https://pdf.dfcfw.com/pdf/H2_AN202608141827994403_1.pdf',
      },
      {
        title: '贵州茅台关于召开2026年半年度业绩说明会的公告',
        publishedAt: '2026-08-15T00:00:00',
        category: '其他',
        url: 'https://pdf.dfcfw.com/pdf/H2_AN202608141827994407_1.pdf',
      },
    ],
    news: null,
    policies: null,
    events: [
      {
        anomalyType: 'PRICE_CHANGE',
        changePct: 3.25,
        currentPrice: 1680.5,
        triggerTime: '2026-09-21T02:00:00Z',
        detail: '日涨跌幅 3.25% 触发阈值 3.0%（现价 1680.50）',
      },
    ],
    sourceStatus: {
      quote: 'ok',
      finance: 'ok',
      valuation: 'ok',
      announce: 'ok',
      news: 'missing',
      policy: 'failed',
      event: 'ok',
    },
  };
}

interface StubOptions {
  detail?: SubjectDetailData;
  byCodeStatus?: number;
  byCodeBody?: unknown;
}

/** 按真实接口路径分发：by-code 解析 + 聚合 detail + 旁路（阅读埋点等）。 */
function stubSubjectFetch({ detail = realDetailData(), byCodeStatus = 200, byCodeBody }: StubOptions = {}) {
  return vi.fn(async (url: string | URL, _init?: RequestInit) => {
    const path = String(url);
    if (path.includes(`/subjects/by-code/${CODE}`)) {
      const body =
        byCodeBody ??
        (byCodeStatus >= 200 && byCodeStatus < 300
          ? { code: 0, msg: 'ok', data: { id: SUBJECT_ID, subjectCode: CODE, name: '贵州茅台' } }
          : { code: 30001, msg: '标的不存在', data: null });
      return mockResponse(byCodeStatus, body);
    }
    if (path.includes(`/subjects/${SUBJECT_ID}/detail`)) {
      return mockResponse(200, { code: 0, msg: 'ok', data: detail, traceId: 't' });
    }
    // 阅读埋点等旁路请求：统一成功空响应
    return mockResponse(200, { code: 0, msg: 'ok', data: null, traceId: 't' });
  });
}

function renderPage(fetchMock: ReturnType<typeof stubSubjectFetch> = stubSubjectFetch()) {
  localStorage.setItem('access_token', 'jwt-test');
  vi.stubGlobal('fetch', fetchMock);
  render(<SubjectDetail subjectId={CODE} />);
  return fetchMock;
}

afterEach(() => {
  vi.unstubAllGlobals();
  cleanup();
  localStorage.clear();
  window.location.hash = '';
  resetReadingTrackerForTest();
});

describe('SubjectDetail 标的详情聚合页（真实接口直连）', () => {
  it('经 by-code 解析数字主键后请求聚合接口（携带 Bearer，不再走前端 mock）', async () => {
    const fetchMock = renderPage();

    expect(await screen.findByTestId('subject-detail')).toBeInTheDocument();
    const urls = fetchMock.mock.calls.map((call) => String(call[0]));
    expect(urls.some((url) => url.includes(`/subjects/by-code/${CODE}`))).toBe(true);
    expect(urls.some((url) => url.includes(`/subjects/${SUBJECT_ID}/detail`))).toBe(true);
    // 数字主键 detail 请求不允许出现代码路径（防回归 mock 时代直接拿 code 请求）
    expect(urls.some((url) => url.includes(`/subjects/${CODE}/detail`))).toBe(false);
    // 受 JWT 保护：两跳均带 Bearer
    for (const call of fetchMock.mock.calls) {
      const headers = (call[1]?.headers ?? {}) as Record<string, string>;
      expect(headers.Authorization).toBe('Bearer jwt-test');
    }
  });

  it('渲染标的头部（名称 / 代码 / 市场 / 行业）', async () => {
    renderPage();

    expect(await screen.findByText('贵州茅台')).toBeInTheDocument();
    expect(screen.getByText(/代码：SH600519/)).toBeInTheDocument();
    expect(screen.getByText(/行业：白酒/)).toBeInTheDocument();
    expect(screen.getByText('A 股')).toBeInTheDocument();
  });

  it('行情分区展示真实接口价格 / 涨跌幅，并标注来源与时间戳', async () => {
    renderPage();

    expect(await screen.findByText('1701.50')).toBeInTheDocument();
    expect(screen.getByText('1.25%')).toBeInTheDocument();
    expect(screen.getByText(/行情源\(eastmoney\)/)).toBeInTheDocument();
    expect(screen.getByText(/更新于 2026-09-22 10:30:00/)).toBeInTheDocument();
  });

  it('财务分区适配真实键（grossProfitMargin / reportDate）', async () => {
    renderPage();

    expect(await screen.findByText('1275.60亿')).toBeInTheDocument();
    expect(screen.getByText('862.80亿')).toBeInTheDocument();
    expect(screen.getByText('91.40%')).toBeInTheDocument();
    expect(screen.getByText('30.55%')).toBeInTheDocument();
    expect(screen.getByText('2026-06-30')).toBeInTheDocument();
  });

  it('估值分区适配真实键 peTtm（PE(TTM) / PB）', async () => {
    renderPage();

    expect(await screen.findByText('17.57')).toBeInTheDocument();
    expect(screen.getByText('6.23')).toBeInTheDocument();
  });

  it('公告分区展示真实条目（标题 / 分类 / 时间 / 外链）', async () => {
    renderPage();

    const list = await screen.findByTestId('announce-list');
    const items = within(list).getAllByRole('listitem');
    expect(items).toHaveLength(2);
    expect(
      within(list).getByText('贵州茅台2026年半年度报告摘要').closest('a'),
    ).toHaveAttribute('href', 'https://pdf.dfcfw.com/pdf/H2_AN202608141827994403_1.pdf');
  });

  it('missing 分区（新闻）显示“暂无数据”且不阻断其他分区', async () => {
    renderPage();

    expect(await screen.findByText('暂无数据')).toBeInTheDocument();
    expect(screen.getByText('本分区暂未返回数据')).toBeInTheDocument();
    expect(screen.queryByTestId('news-list')).toBeNull();
    // 其他分区仍正常展示（不阻断）
    expect(screen.getByText('1701.50')).toBeInTheDocument();
  });

  it('failed 分区（政策）显示“获取失败”', async () => {
    renderPage();

    expect(await screen.findByText('获取失败')).toBeInTheDocument();
    expect(screen.getByText(/本分区数据获取失败/)).toBeInTheDocument();
    expect(screen.queryByTestId('policy-list')).toBeNull();
  });

  it('事件分区展示异动类型 / 涨跌幅 / 详情（ADR-0013 本地事件源）', async () => {
    renderPage();

    const list = await screen.findByTestId('event-list');
    const items = within(list).getAllByRole('listitem');
    expect(items).toHaveLength(1);
    expect(within(list).getByText('涨跌幅异动')).toBeInTheDocument();
    expect(within(list).getByText('3.25%')).toBeInTheDocument();
  });

  it('sourceStatus 徽章按分区状态渲染（5 ok / 1 missing / 1 failed）', async () => {
    renderPage();

    await screen.findByTestId('subject-detail');
    expect(screen.getAllByText('数据正常')).toHaveLength(5);
    expect(screen.getAllByTestId('status-badge-ok')).toHaveLength(5);
    expect(screen.getAllByTestId('status-badge-missing')).toHaveLength(1);
    expect(screen.getAllByTestId('status-badge-failed')).toHaveLength(1);
  });

  it('真实受限网络常态：全分区缺失时页面不空白（头部 + 7 分区徽章兜底）', async () => {
    const allMissing = realDetailData();
    allMissing.quote = null;
    allMissing.finance = null;
    allMissing.valuation = null;
    allMissing.announcements = null;
    allMissing.events = null;
    allMissing.sourceStatus = {
      quote: 'missing',
      finance: 'missing',
      valuation: 'missing',
      announce: 'missing',
      news: 'missing',
      policy: 'missing',
      event: 'missing',
    };
    renderPage(stubSubjectFetch({ detail: allMissing }));

    // 至少有 subject 头部 + 各分区状态徽章，整体不空白
    expect(await screen.findByTestId('subject-header')).toBeInTheDocument();
    expect(screen.getByText('贵州茅台')).toBeInTheDocument();
    expect(screen.getAllByTestId('status-badge-missing')).toHaveLength(7);
    expect(screen.getAllByText('本分区暂未返回数据')).toHaveLength(7);
  });

  it('标的不存在：错误态展示友好文案（不直出技术串）', async () => {
    renderPage(stubSubjectFetch({ byCodeStatus: 404 }));

    const error = await screen.findByTestId('subject-error');
    expect(error).toBeInTheDocument();
    expect(screen.getByText(/未找到代码为 SH600519 的标的/)).toBeInTheDocument();
    // 不直出 error.message / 后端 msg 技术串
    expect(screen.queryByText(/标的不存在/)).toBeNull();
    expect(screen.queryByText(/加载失败：/)).toBeNull();
  });

  it('错误态提供重试按钮，重试后恢复渲染', async () => {
    let byCodeFailed = true;
    const fetchMock = vi.fn(async (url: string | URL) => {
      const path = String(url);
      if (path.includes(`/subjects/by-code/${CODE}`) && byCodeFailed) {
        byCodeFailed = false;
        return mockResponse(503, { code: 50000, msg: 'service unavailable', data: null });
      }
      return stubSubjectFetch()(url);
    });
    renderPage(fetchMock);

    expect(await screen.findByTestId('subject-error')).toBeInTheDocument();
    expect(screen.queryByText(/service unavailable/)).toBeNull();

    const user = userEvent.setup();
    await user.click(screen.getByTestId('subject-retry'));

    expect(await screen.findByTestId('subject-detail')).toBeInTheDocument();
    expect(screen.getByText('1701.50')).toBeInTheDocument();
  });

  it('「AI 简报」跳转携带数字 subjectId 预填参数', async () => {
    renderPage();

    const user = userEvent.setup();
    await user.click(await screen.findByTestId('subject-goto-ai-brief'));

    await waitFor(() => expect(window.location.hash).toBe('#/ai-brief?subjectId=1'));
  });
});
