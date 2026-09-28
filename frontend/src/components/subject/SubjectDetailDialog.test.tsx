// 交互优化：自选清单「查看详情」弹框组件测试。
// 契约：复用 useSubjectDetail 两跳取数；核心版分区预览（行情摘要 + 公告/新闻/政策/事件 各前 3 条）；
// 「查看完整详情」链接保 #/subjects/:code 独立页可达；骨架/错误/空态三态齐备。

import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SubjectDetailDialog } from '@/components/subject/SubjectDetailDialog';
import type { SubjectDetailData } from '@/types/subject-detail';

const CODE = 'SZ000858';
const SUBJECT_ID = 100;
/** 分区预览截断上限（组件契约：各分区最多展示 3 条）。 */
const PREVIEW_MAX = 3;
/** fixture 列表长度（4 > 3，验证截断）。 */
const LIST_SIZE = 4;

function mockResponse(status: number, body: unknown) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as unknown as Response;
}

/** 聚合详情样例：全分区 ok，四个列表分区各 LIST_SIZE 条（验证弹框只取前 3 条）。 */
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
      high: 130.0,
      low: 127.0,
      volume: 21000000,
      source: '行情源(eastmoney)',
      updatedAt: '2026-09-22 10:30:00',
    },
    finance: null,
    valuation: null,
    announcements: range.map((i) => ({
      title: `五粮液公告${i}`,
      publishedAt: '2026-09-20',
      category: '其他',
      url: `https://example.com/a${i}`,
    })),
    news: range.map((i) => ({
      id: `n${i}`,
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
      finance: 'missing',
      valuation: 'missing',
      announce: 'ok',
      news: 'ok',
      policy: 'ok',
      event: 'ok',
    },
  };
}

interface StubOptions {
  detail?: SubjectDetailData | null;
  byCodeStatus?: number;
}

/** 按真实接口路径分发：by-code 解析 + 聚合 detail（detail=null 模拟空态）。 */
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

afterEach(() => {
  vi.unstubAllGlobals();
  cleanup();
  localStorage.clear();
  window.location.hash = '';
});

describe('SubjectDetailDialog 标的详情弹框（自选清单查看详情）', () => {
  it('关闭态：不渲染弹框、不发任何请求（打开才取数）', () => {
    const { fetchMock } = renderDialog(stubFetch(), { open: false });

    expect(screen.queryByTestId('dialog')).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('打开：先骨架后渲染——标题栏名/码/行业徽章 + 行情摘要 + 四分区各前 3 条 + 完整详情链接', async () => {
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

    // 标题栏：名称 / 代码 / 行业徽章
    const header = screen.getByTestId('subject-dialog-header');
    expect(within(header).getByText('五粮液')).toBeInTheDocument();
    expect(within(header).getByText(CODE)).toBeInTheDocument();
    expect(within(header).getByText('白酒')).toBeInTheDocument();

    // 行情摘要：最新价 + 涨跌幅（着色）+ 来源与时间戳
    const quote = screen.getByTestId('subject-dialog-quote');
    expect(within(quote).getByText('128.50')).toBeInTheDocument();
    expect(within(quote).getByText('-1.25%')).toBeInTheDocument();
    expect(within(quote).getByText(/行情源\(eastmoney\)/)).toBeInTheDocument();

    // 四分区各取前 3 条（fixture 4 条 → 截断为 3）
    for (const listId of [
      'subject-dialog-announce-list',
      'subject-dialog-news-list',
      'subject-dialog-policy-list',
      'subject-dialog-event-list',
    ]) {
      const list = screen.getByTestId(listId);
      expect(within(list).getAllByRole('listitem')).toHaveLength(PREVIEW_MAX);
    }
    // 首条内容可见（截断保序：取前 3）
    expect(within(screen.getByTestId('subject-dialog-announce-list')).getByText('五粮液公告1')).toBeInTheDocument();
    expect(within(screen.getByTestId('subject-dialog-announce-list')).queryByText('五粮液公告4')).toBeNull();

    // 完整详情链接：保独立页可达
    expect(screen.getByTestId('subject-dialog-full-detail')).toHaveAttribute(
      'href',
      `#/subjects/${CODE}`,
    );
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

  it('「查看完整详情」点击后回调 onClose（弹框收起，路由跳转由锚链接承载）', async () => {
    const { onClose } = renderDialog();
    await screen.findByTestId('subject-dialog-content');

    const user = userEvent.setup();
    await user.click(screen.getByTestId('subject-dialog-full-detail'));

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('open 但 subjectCode 为 null：不取数不渲染（调用方守门契约）', () => {
    const { fetchMock } = renderDialog(stubFetch(), { subjectCode: null });

    expect(screen.queryByTestId('dialog')).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
