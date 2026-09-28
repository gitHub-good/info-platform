import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { FeedDashboardView } from '@/types/feedDashboard';
import type { PipelineStatusView } from '@/types/pipelineStatus';

// —— fetch mock：健康状态条自管 GET /api/v1/pipeline/status + GET /api/v1/feed-dashboard，
//    概览聚合段（llmToday/jobHealth）经 props 注入（同源对账，不二次请求 /overview） ——

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

function dashboardOf(
  overrides: Partial<FeedDashboardView['global']> = {},
): FeedDashboardView {
  return {
    global: {
      todayNewCount: 1204,
      todayDupCount: 40,
      activeSourceCount: 1,
      failedSourceCount: 0,
      latency: {
        p50Millis: 180000,
        p90Millis: 540000,
        sampleCount: 200,
        basis: 'incremental-only-v1',
        excludedSourceCodes: [],
      },
      ...overrides,
    },
    sources: [
      {
        sourceId: 1,
        sourceCode: 's1',
        name: '源一',
        category: '分类',
        adapterType: 'preset',
        intervalMinutes: 15,
        enabled: true,
        preset: true,
        deleted: false,
        staleSince: null,
        todayPollCount: 10,
        todayNewCount: 5,
        todayFailCount: 0,
        todayDupCount: 0,
        totalCount: 100,
        lastAttemptAt: null,
        lastSuccessAt: null,
        nextDueAt: null,
        backoffUntil: null,
        consecutiveFailures: 0,
        lastError: null,
        lastRoundDetail: null,
        runState: 'ok',
        abnormal: false,
      },
    ],
    failures: [],
  };
}

function pipelineOf(
  overrides: Partial<PipelineStatusView> = {},
): PipelineStatusView {
  return {
    jobKey: 'NEWS_PIPELINE',
    level: 'NORMAL',
    todayCostMicros: 500_000,
    budgetMicros: 2_600_000,
    costBasis: 'cost-v2:m18-30src',
    ...overrides,
  };
}

function stubFetch(routes: {
  pipeline?: () => ReturnType<typeof ok> | ReturnType<typeof fail>;
  dashboard?: () => ReturnType<typeof ok> | ReturnType<typeof fail>;
} = {}) {
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/pipeline/status')) return routes.pipeline?.() ?? ok(pipelineOf());
    if (url.includes('/feed-dashboard')) return routes.dashboard?.() ?? ok(dashboardOf());
    return fail(404, 50000, `unexpected fetch: ${url}`);
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  vi.useRealTimers();
  cleanup();
  localStorage.clear();
  window.location.hash = '';
});

describe('HealthStatusStrip 平台健康状态条（T223，概览重组新区块）', () => {
  it('主路径：五段单行（管道/源在线/今日入库/成本水位/任务失败），每段可点跳对应运维页，全绿低存在感', async () => {
    vi.stubGlobal('fetch', stubFetch());

    const { HealthStatusStrip } = await import('@/components/overview/HealthStatusStrip');
    render(
      <HealthStatusStrip
        llmToday={{ status: 'OK', error: null }}
        jobHealth={{ failed: 0, error: null }}
      />,
    );

    const strip = await screen.findByTestId('health-strip');
    expect(strip).toHaveTextContent('管道正常');
    expect(screen.getByTestId('health-strip-sources')).toHaveTextContent('源在线 1/1');
    expect(screen.getByTestId('health-strip-intake')).toHaveTextContent('今日入库 1204 条');
    expect(screen.getByTestId('health-strip-cost')).toHaveTextContent('成本水位 19%');
    expect(screen.getByTestId('health-strip-jobs')).toHaveTextContent('任务失败 0');
    // 每段可点跳对应运维页
    expect(screen.getByTestId('health-strip-pipeline')).toHaveAttribute('href', '#/feed-dashboard');
    expect(screen.getByTestId('health-strip-sources')).toHaveAttribute('href', '#/feed-dashboard');
    expect(screen.getByTestId('health-strip-intake')).toHaveAttribute('href', '#/feed-dashboard');
    expect(screen.getByTestId('health-strip-cost')).toHaveAttribute('href', '#/cost-report');
    expect(screen.getByTestId('health-strip-jobs')).toHaveAttribute('href', '#/task-center');
    // 全绿低存在感：text-muted-foreground，无语义色高亮
    for (const tid of [
      'health-strip-pipeline',
      'health-strip-sources',
      'health-strip-intake',
      'health-strip-cost',
      'health-strip-jobs',
    ]) {
      expect(screen.getByTestId(tid).className).toContain('text-muted-foreground');
      expect(screen.getByTestId(tid).className).not.toContain('amber');
      expect(screen.getByTestId(tid).className).not.toContain('rose');
    }
  });

  it('异常段语义色高亮：管道降级琥珀 / 源不在线琥珀 / LLM 余量告急琥珀（成本水位段）/ 任务失败红', async () => {
    vi.stubGlobal(
      'fetch',
      stubFetch({
        pipeline: () => ok(pipelineOf({ level: 'DEGRADED' })),
        dashboard: () => ok(dashboardOf({ activeSourceCount: 0 })),
      }),
    );

    const { HealthStatusStrip } = await import('@/components/overview/HealthStatusStrip');
    render(
      <HealthStatusStrip
        llmToday={{ status: 'WARNING', error: null }}
        jobHealth={{ failed: 2, error: null }}
      />,
    );

    await screen.findByTestId('health-strip');
    expect(screen.getByTestId('health-strip-pipeline').className).toContain('amber');
    expect(screen.getByTestId('health-strip-sources').className).toContain('amber');
    expect(screen.getByTestId('health-strip-cost').className).toContain('amber'); // 余量告急→琥珀
    expect(screen.getByTestId('health-strip-jobs').className).toContain('rose');
    // 正常段不受影响
    expect(screen.getByTestId('health-strip-intake').className).not.toContain('amber');
  });

  it('骨架态：自管请求在途渲染整行 shimmer 占位', async () => {
    // 两路自管请求各自挂起（按 URL 分桶 deferred，避免共享 resolver 互相覆盖）
    const deferred: Record<string, (value: ReturnType<typeof ok>) => void> = {};
    const pendingFor = (key: string) =>
      new Promise<ReturnType<typeof ok>>((resolve) => {
        deferred[key] = resolve;
      });
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) =>
        String(input).includes('/pipeline/status') ? pendingFor('pipeline') : pendingFor('dashboard'),
      ),
    );

    const { HealthStatusStrip } = await import('@/components/overview/HealthStatusStrip');
    render(<HealthStatusStrip llmToday={{ status: 'OK', error: null }} jobHealth={{ failed: 0, error: null }} />);

    expect(screen.getByTestId('health-strip-loading')).toBeInTheDocument();
    expect(screen.queryByTestId('health-strip-pipeline')).toBeNull();

    await act(async () => {
      deferred['pipeline']?.(ok(pipelineOf()));
      deferred['dashboard']?.(ok(dashboardOf()));
      await Promise.resolve();
    });
    expect(await screen.findByTestId('health-strip-pipeline')).toBeInTheDocument();
  });

  it('单段数据失败降级：pipeline 500 → 管道/成本水位段「—」，其余段正常（不拖垮整条）', async () => {
    vi.stubGlobal(
      'fetch',
      stubFetch({ pipeline: () => fail(500, 50000, '管道状态取数失败') }),
    );

    const { HealthStatusStrip } = await import('@/components/overview/HealthStatusStrip');
    render(
      <HealthStatusStrip
        llmToday={{ status: 'OK', error: null }}
        jobHealth={{ failed: 0, error: null }}
      />,
    );

    await screen.findByTestId('health-strip');
    expect(screen.getByTestId('health-strip-pipeline')).toHaveTextContent('管道');
    expect(screen.getByTestId('health-strip-pipeline')).toHaveTextContent('—');
    expect(screen.getByTestId('health-strip-cost')).toHaveTextContent('成本水位');
    expect(screen.getByTestId('health-strip-cost')).toHaveTextContent('—');
    // 大盘两段照常出数
    expect(screen.getByTestId('health-strip-sources')).toHaveTextContent('源在线 1/1');
    expect(screen.getByTestId('health-strip-intake')).toHaveTextContent('今日入库 1204 条');
    expect(screen.getByTestId('health-strip-jobs')).toHaveTextContent('任务失败 0');
  });

  it('概览段未就绪（props null）：任务段「—」、成本水位不升高亮，整条不崩', async () => {
    vi.stubGlobal('fetch', stubFetch());

    const { HealthStatusStrip } = await import('@/components/overview/HealthStatusStrip');
    render(<HealthStatusStrip llmToday={null} jobHealth={null} />);

    await screen.findByTestId('health-strip');
    expect(screen.getByTestId('health-strip-jobs')).toHaveTextContent('—');
    expect(screen.getByTestId('health-strip-cost').className).not.toContain('amber');
    expect(screen.getByTestId('health-strip-pipeline')).toHaveTextContent('管道正常');
  });

  it('sm/md 断点允许换行（flex-wrap 单行状态条）', async () => {
    vi.stubGlobal('fetch', stubFetch());

    const { HealthStatusStrip } = await import('@/components/overview/HealthStatusStrip');
    render(
      <HealthStatusStrip
        llmToday={{ status: 'OK', error: null }}
        jobHealth={{ failed: 0, error: null }}
      />,
    );

    expect(await screen.findByTestId('health-strip')).toBeInTheDocument();
    expect(screen.getByTestId('health-strip').className).toContain('flex-wrap');
  });

  it('30 秒自动刷新 + document.hidden 暂停（继承机制）', async () => {
    vi.useFakeTimers();
    const fetchMock = stubFetch();
    vi.stubGlobal('fetch', fetchMock);
    const hiddenSpy = vi.spyOn(document, 'hidden', 'get');

    const { HealthStatusStrip } = await import('@/components/overview/HealthStatusStrip');
    render(
      <HealthStatusStrip
        llmToday={{ status: 'OK', error: null }}
        jobHealth={{ failed: 0, error: null }}
      />,
    );
    await vi.advanceTimersByTimeAsync(500);
    expect(fetchMock).toHaveBeenCalledTimes(2); // pipeline + dashboard 各一

    await vi.advanceTimersByTimeAsync(30_000);
    expect(fetchMock).toHaveBeenCalledTimes(4);

    hiddenSpy.mockReturnValue(true);
    await vi.advanceTimersByTimeAsync(31_000);
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });
});
