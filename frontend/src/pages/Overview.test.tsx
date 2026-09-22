import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Overview } from '@/pages/Overview';
import type { OverviewView } from '@/types/overview';

// —— fetch mock：GET /api/v1/overview（五卡片聚合，可变异供重试路径复用） ——

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

function viewOf(overrides: Partial<OverviewView> = {}): OverviewView {
  return {
    llmToday: { tokenUsed: 8400, costMicros: 34_200, budgetTokens: 20_000, status: 'OK', error: null },
    anomalyToday: { count: 3, error: null },
    policy24h: {
      count: 12,
      latest: [{ id: 101, title: '关于人工智能的行动方案', publishedAt: '2026-09-21' }],
      error: null,
    },
    jobHealth: { windowRuns: 412, windowFailed: 0, unhealthyJobs: [], error: null },
    sourceHealth: [
      { sourceCode: 'QUOTE', mode: 'REAL', lastEventType: 'OK', lastEventAt: '2026-09-22T02:00:00Z', errors24h: 0 },
      { sourceCode: 'POLICY', mode: 'MOCK', lastEventType: null, lastEventAt: null, errors24h: 0 },
    ],
    sourceHealthError: null,
    ...overrides,
  };
}

function stubFetch(responder: () => ReturnType<typeof ok> | ReturnType<typeof fail>) {
  return vi.fn(async () => responder());
}

afterEach(() => {
  vi.unstubAllGlobals();
  cleanup();
  localStorage.clear();
  window.location.hash = '';
});

describe('Overview 概览仪表盘（T42）', () => {
  it('主路径：渲染五卡片数据（成本水位含进度与徽章 / 政策最新条 / 数据源成功计数）', async () => {
    vi.stubGlobal('fetch', stubFetch(() => ok(viewOf())));

    render(<Overview />);

    await waitFor(() =>
      expect(screen.getByTestId('stat-card-llm-today')).toBeInTheDocument(),
    );
    // 成本水位：¥ + 已用/预算 + 进度条 + 状态徽章（带文字不裸色）
    expect(screen.getByTestId('stat-card-llm-today')).toHaveTextContent('¥0.0342');
    expect(screen.getByTestId('stat-card-llm-today')).toHaveTextContent('8,400 / 预算 20,000');
    expect(screen.getByTestId('stat-card-llm-today-progress')).toHaveAttribute(
      'aria-valuenow',
      '42',
    );
    expect(screen.getByTestId('llm-status-OK')).toHaveTextContent('正常');
    // 异动 / 政策 / 任务 / 数据源
    expect(screen.getByTestId('stat-card-anomaly')).toHaveTextContent('3 条');
    expect(screen.getByTestId('stat-card-policy')).toHaveTextContent('12 条');
    expect(screen.getByTestId('stat-card-policy')).toHaveTextContent('关于人工智能的行动方案');
    expect(screen.getByTestId('stat-card-job-health')).toHaveTextContent('失败 0 次');
    expect(screen.getByTestId('job-health-ok')).toHaveTextContent('健康');
    expect(screen.getByTestId('stat-card-source-health')).toHaveTextContent('抓取成功 1/2');
    expect(screen.getByTestId('stat-card-source-health')).toHaveTextContent('全部正常');
  });

  it('主路径：预算告警态显示 WARNING 徽章与任务失败明细', async () => {
    vi.stubGlobal(
      'fetch',
      stubFetch(() =>
        ok(
          viewOf({
            llmToday: { tokenUsed: 18_000, costMicros: 90_000, budgetTokens: 20_000, status: 'WARNING', error: null },
            jobHealth: { windowRuns: 50, windowFailed: 2, unhealthyJobs: ['PUSH_RETRY', 'ANOMALY_DETECT'], error: null },
          }),
        ),
      ),
    );

    render(<Overview />);

    await waitFor(() => expect(screen.getByTestId('llm-status-WARNING')).toBeInTheDocument());
    expect(screen.getByTestId('llm-status-WARNING')).toHaveTextContent('余量告急');
    expect(screen.getByTestId('stat-card-job-health')).toHaveTextContent('失败 2 次');
    expect(screen.getByTestId('stat-card-job-health')).toHaveTextContent(
      '涉及 PUSH_RETRY、ANOMALY_DETECT',
    );
    expect(screen.getByTestId('job-health-failed')).toHaveTextContent('2 任务异常');
  });

  it('三态·loading：五卡片骨架占位', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ok(viewOf())),
    );

    render(<Overview />);

    expect(screen.getByTestId('overview-loading')).toBeInTheDocument();
    // 首帧不出现卡片数据
    expect(screen.queryByTestId('stat-card-llm-today')).toBeNull();
    await waitFor(() =>
      expect(screen.getByTestId('stat-card-llm-today')).toBeInTheDocument(),
    );
  });

  it('三态·empty：当日无数据各卡显示 0 / 暂无抓取记录，不报错', async () => {
    vi.stubGlobal(
      'fetch',
      stubFetch(() =>
        ok(
          viewOf({
            llmToday: { tokenUsed: 0, costMicros: 0, budgetTokens: 20_000, status: 'OK', error: null },
            anomalyToday: { count: 0, error: null },
            policy24h: { count: 0, latest: [], error: null },
            jobHealth: { windowRuns: 0, windowFailed: 0, unhealthyJobs: [], error: null },
            sourceHealth: [
              { sourceCode: 'QUOTE', mode: 'MOCK', lastEventType: null, lastEventAt: null, errors24h: 0 },
            ],
            sourceHealthError: null,
          }),
        ),
      ),
    );

    render(<Overview />);

    await waitFor(() => expect(screen.getByTestId('stat-card-anomaly')).toBeInTheDocument());
    expect(screen.getByTestId('stat-card-anomaly')).toHaveTextContent('0 条');
    expect(screen.getByTestId('stat-card-policy')).toHaveTextContent('0 条');
    expect(screen.getByTestId('stat-card-job-health')).toHaveTextContent('失败 0 次');
    expect(screen.getByTestId('stat-card-source-health')).toHaveTextContent('抓取成功 0/1');
    expect(screen.getByTestId('stat-card-source-health')).toHaveTextContent('暂无抓取记录');
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('三态·error：整页失败显示错误与重试，重试后恢复', async () => {
    let call = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        call += 1;
        return call === 1 ? fail(500, 50000, '服务异常') : ok(viewOf());
      }),
    );

    render(<Overview />);

    await waitFor(() => expect(screen.getByTestId('overview-error')).toBeInTheDocument());
    expect(screen.getByTestId('overview-error')).toHaveTextContent('服务异常');

    await userEvent.click(screen.getByTestId('overview-retry'));

    await waitFor(() => expect(screen.getByTestId('stat-card-llm-today')).toBeInTheDocument());
    expect(screen.queryByTestId('overview-error')).toBeNull();
  });

  it('三态·单卡错误：仅该卡显示错误与独立重试，其余卡正常渲染', async () => {
    let call = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        call += 1;
        return ok(
          call === 1
            ? viewOf({ policy24h: { count: 0, latest: [], error: '取数失败：database is locked' } })
            : viewOf(),
        );
      }),
    );

    render(<Overview />);

    await waitFor(() => expect(screen.getByTestId('stat-card-policy')).toBeInTheDocument());
    expect(screen.getByTestId('stat-card-policy')).toHaveTextContent('database is locked');
    expect(screen.getByTestId('stat-card-policy-retry')).toBeInTheDocument();
    // 其余卡不受拖累
    expect(screen.getByTestId('stat-card-llm-today')).toHaveTextContent('¥0.0342');
    expect(screen.getByTestId('stat-card-anomaly')).toHaveTextContent('3 条');

    await userEvent.click(screen.getByTestId('stat-card-policy-retry'));

    await waitFor(() =>
      expect(screen.getByTestId('stat-card-policy')).toHaveTextContent('12 条'),
    );
  });

  it('交互：整卡可点跳转（成本→#/cost-report、数据源→#/datasource-config）', async () => {
    vi.stubGlobal('fetch', stubFetch(() => ok(viewOf())));

    render(<Overview />);

    await waitFor(() => expect(screen.getByTestId('stat-card-llm-today-link')).toBeInTheDocument());
    expect(screen.getByTestId('stat-card-llm-today-link')).toHaveAttribute('href', '#/cost-report');
    expect(screen.getByTestId('stat-card-anomaly-link')).toHaveAttribute('href', '#/watchlists');
    expect(screen.getByTestId('stat-card-policy-link')).toHaveAttribute('href', '#/policies');
    expect(screen.getByTestId('stat-card-job-health-link')).toHaveAttribute('href', '#/task-center');
    expect(screen.getByTestId('stat-card-source-health-link')).toHaveAttribute(
      'href',
      '#/datasource-config',
    );

    // 键盘可达路径同锚点：点击后 hash 真实跳变
    await userEvent.click(screen.getByTestId('stat-card-llm-today-link'));
    expect(window.location.hash).toBe('#/cost-report');
  });

  it('数据源健康：最差源提示行（源名 + 最近失败时间 + 异常计数）', async () => {
    vi.stubGlobal(
      'fetch',
      stubFetch(() =>
        ok(
          viewOf({
            sourceHealth: [
              { sourceCode: 'QUOTE', mode: 'REAL', lastEventType: 'OK', lastEventAt: '2026-09-22T02:00:00Z', errors24h: 0 },
              { sourceCode: 'POLICY', mode: 'REAL', lastEventType: 'ERROR', lastEventAt: '2026-09-22T06:30:00Z', errors24h: 4 },
            ],
          }),
        ),
      ),
    );

    render(<Overview />);

    await waitFor(() => expect(screen.getByTestId('stat-card-source-health')).toBeInTheDocument());
    expect(screen.getByTestId('stat-card-source-health')).toHaveTextContent('抓取成功 1/2');
    expect(screen.getByTestId('stat-card-source-health')).toHaveTextContent('政策源');
    expect(screen.getByTestId('stat-card-source-health')).toHaveTextContent('ERROR');
    expect(screen.getByTestId('stat-card-source-health')).toHaveTextContent('24h 异常 4 次');
  });
});
