import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LlmCostReport } from '@/pages/LlmCostReport';
import type { LlmCostReport as LlmCostReportView } from '@/types/llmCostReport';

// —— fetch mock：GET /llm-cost-report（窗口切换 + 可强制错误码） —— #

const ok = (data: unknown) => ({
  ok: true,
  status: 200,
  json: async () => ({ code: 0, msg: 'ok', data, traceId: 't' }),
});
const fail = (code: number) => ({
  ok: false,
  status: 500,
  json: async () => ({ code, msg: '服务异常', data: null, traceId: 't' }),
});

interface StoreOpts {
  reports?: Partial<Record<'today' | '7d' | '30d', LlmCostReportView>>;
  code?: number;
  /** 前缀匹配避免同一次交互内既失败又成功时被误伤。 */
  failFirst?: boolean;
}

/** 构造状态化 fetch mock：按 window 返回报表，可强制错误码。 */
function makeStore(opts: StoreOpts = {}) {
  let calls = 0;
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET';
    const path = String(url);
    if (method !== 'GET') return fail(50000);
    if (/\/llm-cost-report(\?.*)?$/.test(path)) {
      calls++;
      if (opts.code) return fail(opts.code);
      if (opts.failFirst && calls === 1) return fail(50000);
      const window = new URLSearchParams(path.split('?')[1] ?? '').get('window') ?? '7d';
      const report = opts.reports?.[window as 'today' | '7d' | '30d'];
      return ok(report ?? EMPTY_REPORT);
    }
    return fail(50000);
  });
  return { fetch };
}

// —— fixtures —— #

const EMPTY_REPORT: LlmCostReportView = {
  window: '7d',
  windowStart: '2026-09-15T04:00:00Z',
  totalCalls: 0,
  successCalls: 0,
  failedCalls: 0,
  rejectedCalls: 0,
  cacheHits: 0,
  successRate: 0,
  cacheHitRate: 0,
  promptTokens: 0,
  completionTokens: 0,
  totalTokens: 0,
  costMicros: 0,
  dailyBudgetTokens: 20000,
  budgetWarnRatio: 0.8,
  providers: [],
  scenes: [],
  topUserBudgets: [],
};

const FULL_REPORT: LlmCostReportView = {
  window: '7d',
  windowStart: '2026-09-15T04:00:00Z',
  totalCalls: 6,
  successCalls: 4,
  failedCalls: 1,
  rejectedCalls: 1,
  cacheHits: 2,
  successRate: 0.8,
  cacheHitRate: 0.3333,
  promptTokens: 8000,
  completionTokens: 1600,
  totalTokens: 9600,
  costMicros: 14400,
  dailyBudgetTokens: 20000,
  budgetWarnRatio: 0.8,
  providers: [
    { provider: 'deepseek', calls: 4, successCalls: 3, failedCalls: 1, totalTokens: 9600, costMicros: 14400 },
    { provider: '未发起', calls: 1, successCalls: 0, failedCalls: 0, totalTokens: 0, costMicros: 0 },
    { provider: 'glm', calls: 1, successCalls: 1, failedCalls: 0, totalTokens: 0, costMicros: 0 },
  ],
  scenes: [
    { scene: '1', calls: 3, totalTokens: 7200, costMicros: 10800 },
    { scene: '4', calls: 2, totalTokens: 2400, costMicros: 3600 },
    { scene: '3', calls: 1, totalTokens: 0, costMicros: 0 },
  ],
  topUserBudgets: [
    { userId: 1001, usedTokens: 18000, budgetTokens: 20000, remainingTokens: 2000, status: 'WARNING' },
    { userId: 1002, usedTokens: 20000, budgetTokens: 20000, remainingTokens: 0, status: 'EXHAUSTED' },
  ],
};

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  localStorage.clear();
  window.location.hash = '';
});

describe('LlmCostReport 成本报表页', () => {
  it('渲染汇总卡片与 provider/场景/预算分布（主路径）', async () => {
    const store = makeStore({ reports: { '7d': FULL_REPORT } });
    vi.stubGlobal('fetch', store.fetch);
    render(<LlmCostReport />);

    // 汇总卡片：成本微元→元、次数、比率、token
    expect(await screen.findByTestId('cost-summary-cost')).toHaveTextContent('¥0.0144');
    expect(screen.getByTestId('cost-summary-calls')).toHaveTextContent('6');
    expect(screen.getByTestId('cost-summary-calls')).toHaveTextContent('成功 4');
    expect(screen.getByTestId('cost-summary-rates')).toHaveTextContent('80.00%');
    expect(screen.getByTestId('cost-summary-rates')).toHaveTextContent('33.33%');
    expect(screen.getByTestId('cost-summary-tokens')).toHaveTextContent('9,600');

    // provider 分布（未发起兜底行 + 失败红显）
    expect(screen.getByTestId('cost-provider-row-deepseek')).toHaveTextContent('0.0144');
    expect(screen.getByTestId('cost-provider-row-未发起')).toBeInTheDocument();

    // 场景分布（sceneKey → 中文标签）
    expect(screen.getByTestId('cost-scene-row-1')).toHaveTextContent('个股简报');
    expect(screen.getByTestId('cost-scene-row-4')).toHaveTextContent('每日推荐');
    expect(screen.getByTestId('cost-scene-row-3')).toHaveTextContent('政策解读');

    // 预算状态徽章：WARNING 黄 / EXHAUSTED 红
    expect(screen.getByTestId('budget-status-WARNING')).toHaveTextContent('余量告急');
    expect(screen.getByTestId('budget-status-EXHAUSTED')).toHaveTextContent('已耗尽');
  });

  it('切换时间窗：带 window 参数重新拉取', async () => {
    const store = makeStore({ reports: { '30d': FULL_REPORT, '7d': EMPTY_REPORT } });
    vi.stubGlobal('fetch', store.fetch);
    render(<LlmCostReport />);
    await screen.findByTestId('cost-report-empty');

    await userEvent.click(screen.getByTestId('cost-window-30d'));

    expect(await screen.findByTestId('cost-summary-calls')).toHaveTextContent('6');
    const lastUrl = String(store.fetch.mock.calls.at(-1)?.[0]);
    expect(lastUrl).toContain('window=30d');
  });

  it('空数据窗口：显示空态引导（边界）', async () => {
    const store = makeStore({ reports: { '7d': EMPTY_REPORT } });
    vi.stubGlobal('fetch', store.fetch);
    render(<LlmCostReport />);

    expect(await screen.findByTestId('cost-report-empty')).toHaveTextContent('暂无 LLM 调用记录');
    expect(screen.queryByTestId('cost-summary-cost')).toBeNull();
  });

  it('接口异常：显示错误与重试，重试后恢复（异常路径）', async () => {
    const store = makeStore({ reports: { '7d': FULL_REPORT }, failFirst: true });
    vi.stubGlobal('fetch', store.fetch);
    render(<LlmCostReport />);

    expect(await screen.findByTestId('cost-report-error')).toHaveTextContent('服务异常');
    await userEvent.click(screen.getByTestId('cost-report-retry'));

    expect(await screen.findByTestId('cost-summary-cost')).toHaveTextContent('¥0.0144');
  });

  it('今日无用户用量：预算区显示空态', async () => {
    const report = { ...FULL_REPORT, window: 'today' as const, topUserBudgets: [] };
    const store = makeStore({ reports: { today: report } });
    vi.stubGlobal('fetch', store.fetch);
    render(<LlmCostReport />);

    await userEvent.click(await screen.findByTestId('cost-window-today'));
    expect(await screen.findByTestId('cost-budget-empty')).toHaveTextContent('今日暂无用户用量');
  });
});
