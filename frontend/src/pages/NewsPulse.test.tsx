import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NewsPulse } from '@/pages/NewsPulse';
import type { PulseRow } from '@/types/newsPulse';

// —— fetch mock：GET /news-pulse（六窗总览）/ POST /news-pulse/{w}/refresh ——

const ANALYSIS = {
  overview: { A股: 'A股震荡走强，半导体领涨', 港股: '港股科技反弹', 美股: '美股期货平稳' },
  keyEvents: [
    {
      title: '国产算力订单落地',
      importance: 5,
      markets: ['A股'],
      industries: ['电子'],
      concepts: ['AI芯片'],
      subjects: ['海光信息'],
      summary: '多家公司公告中标算力集采',
    },
  ],
  hotTracks: [
    { name: 'AI 算力', type: '概念', markets: ['A股'], newsCount: 12, summary: '产业链消息密集' },
    { name: '电子', type: '行业', markets: ['A股'], newsCount: 8, summary: '板块资讯居前' },
  ],
  sentiment: { A股: '偏多', 港股: '中性', 美股: '中性' },
};

function rowOf(overrides: Partial<PulseRow> = {}): PulseRow {
  return {
    id: 1,
    windowKey: '1h',
    windowStart: '2026-09-29T07:00:00Z',
    windowEnd: '2026-09-29T08:00:00Z',
    newsCount: 42,
    classifiedCount: 38,
    industryStats: JSON.stringify([
      { industry: '电子', count: 12 },
      { industry: '汽车', count: 6 },
    ]),
    marketStats: JSON.stringify([
      { market: 'A股', newsCount: 30, topSubjects: ['海光信息(4)', '中芯国际(2)'] },
      { market: '港股', newsCount: 3, topSubjects: [] },
      { market: '美股', newsCount: 2, topSubjects: [] },
      { market: '未关联', newsCount: 7, topSubjects: [] },
    ]),
    analysis: JSON.stringify(ANALYSIS),
    model: 'glm-4.7',
    promptVersion: 'v1.0',
    triggerSource: 'JOB',
    degraded: false,
    degradedReason: null,
    createdAt: '2026-09-29T08:00:05Z',
    ...overrides,
  };
}

function stubPulse(latestByWindow: Record<string, PulseRow | null>, overrides: Partial<{
  refreshRow: PulseRow;
  refreshError: { code: number; msg: string };
}> = {}) {
  const refreshError = overrides.refreshError;
  return vi.fn(async (url: string, init?: RequestInit) => {
    const path = String(url);
    if (init?.method === 'POST' && /\/news-pulse\/(\w+)\/refresh\?/.test(path)) {
      if (refreshError) {
        return {
          ok: false,
          status: 400,
          json: async () => ({ code: refreshError.code, msg: refreshError.msg, data: null, traceId: 't' }),
        };
      }
      return {
        ok: true,
        status: 200,
        json: async () => ({ code: 0, msg: 'ok', data: overrides.refreshRow ?? latestByWindow['1h'], traceId: 't' }),
      };
    }
    if (/\/news-pulse\?/.test(path)) {
      const data = ['30m', '1h', '3h', '6h', '12h', '24h'].map((key) => ({
        windowKey: key,
        label: key,
        latest: latestByWindow[key] ?? null,
      }));
      return { ok: true, status: 200, json: async () => ({ code: 0, msg: 'ok', data, traceId: 't' }) };
    }
    return { ok: false, status: 404, json: async () => ({ code: 40400, msg: 'not found', data: null, traceId: 't' }) };
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
  cleanup();
  window.location.hash = '';
});

describe('资讯脉搏页（V3.2 M28）', () => {
  it('默认 1h 窗渲染：tabs/元信息/三市场概览/热点主线/关键事件/行业分布', async () => {
    vi.stubGlobal('fetch', stubPulse({ '1h': rowOf() }));
    render(<NewsPulse />);

    // tabs 固定六窗 + 默认选中 1h
    expect(await screen.findByTestId('pulse-tab-1h')).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByTestId('pulse-tab-30m')).toBeInTheDocument();
    expect(screen.getByTestId('pulse-tab-24h')).toBeInTheDocument();

    // 元信息
    expect(screen.getByTestId('pulse-news-count')).toHaveTextContent('42 条');
    expect(screen.getByTestId('pulse-classified')).toHaveTextContent('90%');

    // 三市场概览 + 情绪徽章 + 标的归集 chips
    expect(screen.getByTestId('pulse-overview-A股')).toHaveTextContent('半导体领涨');
    expect(screen.getByTestId('pulse-market-港股')).toHaveTextContent('中性');
    expect(screen.getByTestId('pulse-market-A股')).toHaveTextContent('海光信息(4)');

    // 热点主线（横条 + 类型徽章）
    expect(screen.getByTestId('pulse-hot-tracks')).toHaveTextContent('AI 算力');
    expect(screen.getByTestId('pulse-hot-tracks')).toHaveTextContent('概念');

    // 关键事件（星级 + chips）
    expect(screen.getByTestId('pulse-event-0')).toHaveTextContent('国产算力订单落地');
    expect(screen.getByTestId('pulse-event-0')).toHaveTextContent('AI芯片');
    expect(screen.getByTestId('pulse-event-0')).toHaveTextContent('海光信息');

    // 行业分布（规则统计横条）
    expect(screen.getByTestId('pulse-industry-stats')).toHaveTextContent('电子');

    // M29：未关联桶计数展示（四桶不隐藏——拍板三）
    expect(screen.getByTestId('pulse-market-unlinked')).toHaveTextContent('未关联 7 条');
  });

  it('AI 降级快照：显示降级徽章与说明，规则统计照常渲染', async () => {
    vi.stubGlobal(
      'fetch',
      stubPulse({
        '1h': rowOf({ analysis: null, model: null, degraded: true, degradedReason: 'budget fused' }),
      }),
    );
    render(<NewsPulse />);

    await screen.findByTestId('pulse-meta');
    expect(screen.getByTestId('pulse-meta')).toHaveTextContent('AI 分析降级');
    expect(screen.getByTestId('pulse-degraded-note')).toHaveTextContent('budget fused');
    expect(screen.getByTestId('pulse-industry-stats')).toHaveTextContent('电子');
    expect(screen.queryByTestId('pulse-hot-tracks')).toBeNull();
  });

  it('未分析窗口：空态 + 立即分析触发 POST refresh', async () => {
    const fetchMock = stubPulse(
      {}, // 所有窗口均未分析 → 3h latest=null 走空态
      { refreshRow: rowOf({ windowKey: '3h' }) },
    );
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    render(<NewsPulse />);

    await screen.findByTestId('pulse-tab-3h');
    await user.click(screen.getByTestId('pulse-tab-3h'));
    expect(await screen.findByTestId('pulse-empty')).toBeInTheDocument();

    await user.click(screen.getByTestId('pulse-empty-refresh'));
    await waitFor(() =>
      expect(fetchMock.mock.calls.some((c) => String(c[0]).includes('/news-pulse/3h/refresh'))).toBe(true),
    );
  });

  it('手动刷新命中 5min 守卫：展示服务端错误文案不替换快照', async () => {
    vi.stubGlobal(
      'fetch',
      stubPulse(
        { '1h': rowOf() },
        { refreshError: { code: 2002, msg: '刷新过于频繁（距上次分析不足 5 分钟）' } },
      ),
    );
    const user = userEvent.setup();
    render(<NewsPulse />);

    await screen.findByTestId('pulse-news-count');
    await user.click(screen.getByTestId('pulse-refresh'));
    await waitFor(() =>
      expect(screen.getByTestId('pulse-action-error')).toHaveTextContent('刷新过于频繁'),
    );
    // 原快照未被清空
    expect(screen.getByTestId('pulse-news-count')).toHaveTextContent('42 条');
  });

  it('M29 三市场切换：请求带 market 参数 + URL 持久化 + 归集四桶保持（未关联桶继续展示）', async () => {
    const fetchMock = stubPulse({ '1h': rowOf() });
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    render(<NewsPulse />);

    // 默认 A 股：六窗总览请求带 market=A_SHARE
    await screen.findByTestId('pulse-news-count');
    expect(String(fetchMock.mock.calls[0][0])).toContain('/news-pulse?market=A_SHARE');
    // 归集四桶全量展示（A股/港股/美股 + 未关联计数）
    expect(screen.getByTestId('pulse-market-A股')).toBeInTheDocument();
    expect(screen.getByTestId('pulse-market-港股')).toBeInTheDocument();
    expect(screen.getByTestId('pulse-market-美股')).toBeInTheDocument();
    expect(screen.getByTestId('pulse-market-unlinked')).toHaveTextContent('未关联 7 条');

    // 切港股：请求重查带 market=HK，URL 持久化，四桶照常（契约零变化——容错回显）
    await user.click(screen.getByTestId('market-tab-HK'));
    await waitFor(() =>
      expect(fetchMock.mock.calls.some((call) => String(call[0]).includes('/news-pulse?market=HK'))).toBe(true),
    );
    expect(window.location.hash).toContain('market=HK');
    expect(await screen.findByTestId('pulse-market-A股')).toBeInTheDocument();
    expect(screen.getByTestId('pulse-market-unlinked')).toHaveTextContent('未关联 7 条');
  });

  it('M29 手动刷新带 market 参数（refresh?market=）', async () => {
    const fetchMock = stubPulse({ '1h': rowOf() }, { refreshRow: rowOf({ newsCount: 50 }) });
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    render(<NewsPulse />);

    await screen.findByTestId('pulse-news-count');
    await user.click(screen.getByTestId('market-tab-US'));
    await waitFor(() =>
      expect(fetchMock.mock.calls.some((call) => String(call[0]).includes('/news-pulse?market=US'))).toBe(true),
    );
    await user.click(screen.getByTestId('pulse-refresh'));
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some((call) => String(call[0]).includes('/news-pulse/1h/refresh?market=US')),
      ).toBe(true),
    );
  });
});
