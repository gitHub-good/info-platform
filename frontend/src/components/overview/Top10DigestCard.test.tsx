import { act, cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { MarketTopItem, MarketTopRankView } from '@/types/marketTop';

// —— fetch mock：对齐后端 MarketTopController 契约（GET /api/v1/market-top 首查 = 概览精华卡同源，拍板五） ——

const ok = (data: unknown) => ({
  ok: true,
  status: 200,
  json: async () => ({ code: 0, msg: 'ok', data, traceId: 't' }),
});
const fail = (status: number, code: number, msg: string) => ({
  ok: false,
  status,
  json: async () => ({ code, msg, data: null, traceId: 't' }),
});

function itemOf(overrides: Partial<MarketTopItem> = {}): MarketTopItem {
  return {
    rankNo: 1,
    subjectId: 11,
    subjectCode: 'SH600519',
    subjectName: '贵州茅台',
    totalScore: 88.6,
    finalScore: 87.2,
    percentile: 92,
    breakthrough: false,
    factors: [],
    generation: 'FULL',
    diveMethod: null,
    diveSummary: null,
    diveDetail: null,
    evidenceCount: 5,
    lastEventDate: '2026-09-21',
    prevRank: 3,
    changeType: 'UP',
    computedAt: '2026-09-21T10:00:00Z',
    ...overrides,
  };
}

function rankViewOf(items: MarketTopItem[]): MarketTopRankView {
  return {
    rankDate: '2026-09-22',
    version: 1,
    triggerSource: 'DAILY',
    batch: {
      snapshotDate: '2026-09-21',
      computedAt: '2026-09-21T10:00:00Z',
      degraded: false,
      degradedReason: null,
      funnelStats: {},
      dropped: [],
      lastEvent: null,
    },
    items,
    disclaimer: 'AI 分析仅供参考，非投资建议',
    recentIncrement: null,
  };
}

/** 前 5 名工厂（名次 1~5，名次 1 prevRank=3 → 变动 ↑2）。 */
function top5Of(): MarketTopItem[] {
  return [
    itemOf(),
    itemOf({ rankNo: 2, subjectId: 12, subjectCode: 'SZ002594', subjectName: '比亚迪', finalScore: 85.9, percentile: 90, prevRank: 1, changeType: 'DOWN' }),
    itemOf({ rankNo: 3, subjectId: 13, subjectCode: 'SH688981', subjectName: '中芯国际', finalScore: 83.1, percentile: 88, prevRank: null, changeType: 'NEW' }),
    itemOf({ rankNo: 4, subjectId: 14, subjectCode: 'SZ000001', subjectName: '平安银行', finalScore: 81.4, percentile: 85, prevRank: 4, changeType: 'SAME' }),
    itemOf({ rankNo: 5, subjectId: 15, subjectCode: 'SH601899', subjectName: '紫金矿业', finalScore: 80.2, percentile: 83, prevRank: 6, changeType: 'UP' }),
  ];
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  vi.useRealTimers();
  cleanup();
  window.location.hash = '';
});

describe('Top10DigestCard 全市场 Top10 精华卡（T223，概览重组新区块）', () => {
  it('主路径：前 5 名行（排名/名称/终分/百分位/变动徽章）+「查看完整榜单」入口；首查不带 date/version（与榜单页同源）', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL) => ok(rankViewOf(top5Of())));
    vi.stubGlobal('fetch', fetchMock);

    const { Top10DigestCard } = await import('@/components/overview/Top10DigestCard');
    render(<Top10DigestCard />);

    const first = await screen.findByTestId('top10-digest-row-SH600519');
    expect(first).toHaveTextContent('贵州茅台');
    expect(first).toHaveTextContent('87.2');
    expect(first).toHaveTextContent('超 92%');
    expect(first).toHaveTextContent('↑2'); // prevRank 3 → rankNo 1
    expect(screen.getByTestId('top10-digest-row-SZ002594')).toHaveTextContent('↓1');
    expect(screen.getByTestId('top10-digest-row-SH688981')).toHaveTextContent('新');
    // 完整榜单入口跳 #/market-top
    expect(screen.getByTestId('top10-digest-link')).toHaveAttribute('href', '#/market-top');
    // 首查同源：不带 date/version 参数（复用榜单页首查口径，拍板五）
    const firstCall = String(fetchMock.mock.calls[0][0]);
    expect(firstCall).toContain('/api/v1/market-top');
    expect(firstCall).not.toContain('date=');
    expect(firstCall).not.toContain('version=');
  });

  it('行可点：点击 Top10 行跳 #/market-top（任务验收「行可点跳」）', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ok(rankViewOf(top5Of()))));

    const { Top10DigestCard } = await import('@/components/overview/Top10DigestCard');
    render(<Top10DigestCard />);

    await screen.findByTestId('top10-digest-row-SH600519');
    await userEvent.click(screen.getByTestId('top10-digest-row-SH600519'));
    expect(window.location.hash).toBe('#/market-top');
  });

  it('前 5 截断：榜单 10 行只渲染 5 行（精华快览不做全量）', async () => {
    const ten = Array.from({ length: 10 }, (_, i) =>
      itemOf({ rankNo: i + 1, subjectId: 100 + i, subjectCode: `SH60000${i}`, subjectName: `标的${i + 1}` }),
    );
    vi.stubGlobal('fetch', vi.fn(async () => ok(rankViewOf(ten))));

    const { Top10DigestCard } = await import('@/components/overview/Top10DigestCard');
    render(<Top10DigestCard />);

    await screen.findByTestId('top10-digest-row-SH600001');
    expect(screen.queryByTestId('top10-digest-row-SH600006')).toBeNull();
    expect(screen.getAllByTestId(/^top10-digest-row-/)).toHaveLength(5);
  });

  it('骨架态：请求在途渲染占位不出数据不出空态', async () => {
    const pending = { resolve: null as ((value: ReturnType<typeof ok>) => void) | null };
    vi.stubGlobal(
      'fetch',
      vi.fn(
        () =>
          new Promise((resolve) => {
            pending.resolve = resolve as (value: ReturnType<typeof ok>) => void;
          }),
      ),
    );

    const { Top10DigestCard } = await import('@/components/overview/Top10DigestCard');
    render(<Top10DigestCard />);

    expect(screen.getByTestId('top10-digest-loading')).toBeInTheDocument();
    expect(screen.queryByTestId('top10-digest-empty')).toBeNull();
    expect(screen.queryByTestId('top10-digest-error')).toBeNull();

    pending.resolve?.(ok(rankViewOf(top5Of())));
    expect(await screen.findByTestId('top10-digest-row-SH600519')).toBeInTheDocument();
  });

  it('错误态：块内文案 + 重试恢复（单块降级不外抛）', async () => {
    let failing = true;
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => (failing ? fail(500, 50000, '榜单服务异常') : ok(rankViewOf(top5Of())))),
    );

    const { Top10DigestCard } = await import('@/components/overview/Top10DigestCard');
    render(<Top10DigestCard />);

    expect(await screen.findByTestId('top10-digest-error')).toHaveTextContent('榜单服务异常');
    expect(screen.getByTestId('top10-digest-retry')).toBeInTheDocument();

    failing = false;
    await userEvent.click(screen.getByTestId('top10-digest-retry'));
    expect(await screen.findByTestId('top10-digest-row-SH600519')).toBeInTheDocument();
  });

  it('空态：「榜单生成中或样本不足」+ 方法论引导（与榜单页空态口径一致）', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ok(rankViewOf([]))));

    const { Top10DigestCard } = await import('@/components/overview/Top10DigestCard');
    render(<Top10DigestCard />);

    expect(await screen.findByTestId('top10-digest-empty')).toHaveTextContent('榜单生成中或样本不足');
    const methodology = screen.getByTestId('top10-digest-empty-methodology');
    expect(methodology).toHaveAttribute('href', '#/market-top/methodology');
  });

  it('30 秒自动刷新 + document.hidden 暂停（继承 WorkbenchPanel 机制）', async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn(async () => ok(rankViewOf(top5Of())));
    vi.stubGlobal('fetch', fetchMock);
    const hiddenSpy = vi.spyOn(document, 'hidden', 'get');

    const { Top10DigestCard } = await import('@/components/overview/Top10DigestCard');
    render(<Top10DigestCard />);
    // fake timers 下 waitFor 挂起：小步推进冲刷微任务直至首份数据落地（沿 FeedDashboard 先例）
    for (let i = 0; i < 20 && screen.queryByTestId('top10-digest-row-SH600519') == null; i++) {
      await vi.advanceTimersByTimeAsync(50);
    }
    expect(screen.getByTestId('top10-digest-row-SH600519')).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(1);

    // 可见时到期即拉取
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000);
    });
    expect(fetchMock).toHaveBeenCalledTimes(2);

    // 页面隐藏：定时器到期跳过本轮
    hiddenSpy.mockReturnValue(true);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(31_000);
    });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});
