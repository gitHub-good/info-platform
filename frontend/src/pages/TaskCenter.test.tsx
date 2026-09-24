import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { TaskCenter } from '@/pages/TaskCenter';
import type { JobView } from '@/types/taskCenter';

// —— fetch mock：jobs 组（GET/PATCH/run，状态化可变异） ——

const ok = (data: unknown) => ({
  ok: true,
  status: 200,
  json: async () => ({ code: 0, msg: 'ok', data, traceId: 't' }),
});
const accepted = (data: unknown) => ({
  ok: true,
  status: 202,
  json: async () => ({ code: 0, msg: 'ok', data, traceId: 't' }),
});
const fail = (status: number, code: number, msg: string) => ({
  ok: false,
  status,
  json: async () => ({ code, msg, data: null, traceId: 't' }),
});

const EFFECTIVE_MODES = {
  enabled: 'LIVE',
  intervalMillis: 'LIVE_NEXT_CYCLE',
  cron: 'LIVE_NEXT_CYCLE',
  userIds: 'LIVE_NEXT_CYCLE',
} as const;

function jobOf(overrides: Partial<JobView> = {}): JobView {
  return {
    jobKey: 'POLICY_FETCH',
    jobName: 'PolicyFetchJob',
    name: '政策抓取',
    description: '抓取 gov.cn/zhengce 最近政策，去重后标注行业入库',
    scheduleType: 'FIXED_DELAY',
    intervalMillis: 3600000,
    cron: null,
    userIds: '',
    enabled: true,
    running: false,
    lastExecution: {
      status: 'SUCCESS',
      startTime: '2026-09-22T03:00:00Z',
      endTime: '2026-09-22T03:00:01Z',
      durationMillis: 1200,
    },
    nextExecutionTime: '2026-09-22T04:00:01Z',
    updatedAt: '2026-09-22T01:00:00Z',
    effectiveModes: { ...EFFECTIVE_MODES },
    ...overrides,
  };
}

/** 5 任务全量视图（对齐 PRD 场景 4.1）。 */
function fiveJobs(): JobView[] {
  return [
    jobOf(),
    jobOf({
      jobKey: 'POLICY_TENDENCY',
      jobName: 'PolicyTendencyJob',
      name: '政策倾向判断',
      intervalMillis: 1800000,
      lastExecution: null,
    }),
    jobOf({
      jobKey: 'ANOMALY_DETECT',
      jobName: 'AnomalyDetectionJob',
      name: '异动检测',
      intervalMillis: 10000,
      running: true,
      lastExecution: {
        status: 'STARTED',
        startTime: '2026-09-22T03:00:00Z',
        endTime: null,
        durationMillis: null,
      },
      nextExecutionTime: null,
    }),
    jobOf({
      jobKey: 'PUSH_RETRY',
      jobName: 'PushRetryJob',
      name: '推送补推',
      intervalMillis: 30000,
      lastExecution: {
        status: 'FAILED',
        startTime: '2026-09-22T02:00:00Z',
        endTime: '2026-09-22T02:00:01Z',
        durationMillis: 900,
      },
    }),
    jobOf({
      jobKey: 'DAILY_RECOMMEND',
      jobName: 'DailyRecommendationJob',
      name: '每日推荐',
      scheduleType: 'CRON',
      intervalMillis: null,
      cron: '0 0 9 * * ?',
      enabled: false,
      lastExecution: null,
      nextExecutionTime: null,
    }),
  ];
}

interface StoreOpts {
  jobs?: JobView[];
  failGet?: boolean;
  /** 这些 jobKey 的手动触发返回失败（模拟非防重入类错误）。 */
  failRunFor?: string[];
  /** 这些 jobKey 的手动触发返回 409/30063（防重入收敛路径）。 */
  runningRejectFor?: string[];
}

/** 状态化 mock：GET 返回任务列表副本；PATCH 合并返回；run 受理或按需失败/409。 */
function makeStore({ jobs = fiveJobs(), failGet = false, failRunFor = [], runningRejectFor = [] }: StoreOpts = {}) {
  const state = { jobs };
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    const path = String(url);
    const runMatch = path.match(/\/jobs\/([A-Z_]+)\/run$/);
    if (runMatch && init?.method === 'POST') {
      if (runningRejectFor.includes(runMatch[1])) {
        return fail(409, 30063, '任务正在运行，请等待本轮执行完成');
      }
      if (failRunFor.includes(runMatch[1])) {
        return fail(500, 50000, '服务异常');
      }
      return accepted({ executionId: 123, status: 'STARTED' });
    }
    const patchMatch = path.match(/\/jobs\/([A-Z_]+)$/);
    if (patchMatch && init?.method === 'PATCH') {
      const body = JSON.parse(String(init.body)) as Record<string, unknown>;
      const updated = state.jobs.map((job) =>
        job.jobKey === patchMatch[1]
          ? { ...job, ...body, effectiveModes: job.effectiveModes, updatedAt: '2026-09-22T05:00:00Z' }
          : job,
      );
      const saved = updated.find((job) => job.jobKey === patchMatch[1]);
      state.jobs = updated;
      return ok(saved);
    }
    if (path.endsWith('/jobs')) {
      if (failGet) {
        return fail(500, 50000, '服务异常');
      }
      return ok({ jobs: state.jobs.map((job) => ({ ...job })) });
    }
    return ok(null);
  });
  return { fetchMock, state };
}

function renderPage(store: ReturnType<typeof makeStore>) {
  vi.stubGlobal('fetch', store.fetchMock);
  render(<TaskCenter />);
}

const countGets = (store: ReturnType<typeof makeStore>) =>
  store.fetchMock.mock.calls.filter((call) => String(call[0]).endsWith('/jobs')).length;

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
  cleanup();
  window.location.hash = '';
});

describe('TaskCenter 页面（T41）', () => {
  it('5 任务总览渲染：调度参数/三色徽章/从未执行空态/停用下次执行为 —（PRD 场景 4.1/4.5）', async () => {
    renderPage(makeStore());

    expect(screen.getByTestId('task-center-loading')).toBeInTheDocument();
    expect(await screen.findByTestId('task-row-POLICY_FETCH')).toBeInTheDocument();

    // 5 行 + 调度参数（间隔型「每 1h」/ cron 型原文）
    expect(screen.getByTestId('task-row-ANOMALY_DETECT')).toBeInTheDocument();
    expect(screen.getByTestId('task-row-DAILY_RECOMMEND')).toBeInTheDocument();
    expect(screen.getByTestId('task-schedule-POLICY_FETCH')).toHaveTextContent('每 1h');
    expect(screen.getByTestId('task-schedule-DAILY_RECOMMEND')).toHaveTextContent('cron 0 0 9 * * ?');

    // 上次执行：成功徽章 + 从未执行空态 + 失败徽章
    expect(screen.getByTestId('task-last-POLICY_FETCH')).toHaveTextContent('成功');
    expect(screen.getByTestId('task-last-POLICY_TENDENCY')).toHaveTextContent('从未执行');
    expect(screen.getByTestId('task-last-PUSH_RETRY')).toHaveTextContent('失败');

    // 下次执行：启用展示时间、停用为 —
    expect(screen.getByTestId('task-next-POLICY_FETCH')).not.toHaveTextContent('—');
    expect(screen.getByTestId('task-next-DAILY_RECOMMEND')).toHaveTextContent('—');
  });

  it('运行中行「立即执行」置灰为「运行中…」，上次执行显示运行中徽章（防重入红线，PRD 场景 4.4）', async () => {
    renderPage(makeStore());
    await screen.findByTestId('task-row-ANOMALY_DETECT');

    const runButton = screen.getByTestId('task-run-ANOMALY_DETECT') as HTMLButtonElement;
    expect(runButton.disabled).toBe(true);
    expect(runButton).toHaveTextContent('运行中…');
    expect(screen.getByTestId('task-last-ANOMALY_DETECT')).toHaveTextContent('运行中');
  });

  it('手动触发成功 → 受理后立即单次刷新（不等下个轮询周期）', async () => {
    const store = makeStore();
    renderPage(store);
    const user = userEvent.setup();
    await screen.findByTestId('task-row-POLICY_FETCH');

    const getsBefore = countGets(store);
    await user.click(screen.getByTestId('task-run-POLICY_FETCH'));
    await waitFor(() => expect(countGets(store)).toBeGreaterThan(getsBefore));
    expect(
      store.fetchMock.mock.calls.some(
        (call) => String(call[0]).endsWith('/POLICY_FETCH/run') && call[1]?.method === 'POST',
      ),
    ).toBe(true);
  });

  it('后端「已在运行」30063 收敛为刷新，不弹错误（交互 3 双重保险）', async () => {
    const store = makeStore({ runningRejectFor: ['PUSH_RETRY'] });
    renderPage(store);
    const user = userEvent.setup();
    await screen.findByTestId('task-row-PUSH_RETRY');

    await user.click(screen.getByTestId('task-run-PUSH_RETRY'));
    await waitFor(() =>
      expect(
        store.fetchMock.mock.calls.some(
          (call) => String(call[0]).endsWith('/PUSH_RETRY/run') && call[1]?.method === 'POST',
        ),
      ).toBe(true),
    );
    expect(screen.queryByTestId('task-run-error-PUSH_RETRY')).toBeNull();
  });

  it('触发失败（非防重入类错误）行内 rose 文案', async () => {
    renderPage(makeStore({ failRunFor: ['PUSH_RETRY'] }));
    const user = userEvent.setup();
    await screen.findByTestId('task-row-PUSH_RETRY');

    await user.click(screen.getByTestId('task-run-PUSH_RETRY'));
    expect(await screen.findByTestId('task-run-error-PUSH_RETRY')).toHaveTextContent('服务异常');
  });

  it('停用需二次确认，确认后保存并短暂显示「已生效」（启停立即）', async () => {
    const store = makeStore();
    renderPage(store);
    const user = userEvent.setup();
    await screen.findByTestId('task-row-POLICY_FETCH');

    // 关闭开关 → 确认 Dialog（UI 方案 §3.4 交互 7）
    await user.click(screen.getByTestId('task-enabled-POLICY_FETCH'));
    expect(await screen.findByTestId('task-stop-confirm-POLICY_FETCH')).toBeInTheDocument();
    await user.click(screen.getByTestId('task-stop-confirm-POLICY_FETCH'));

    // 已保存 enabled=false + enabled 生效级别为 LIVE → 短暂「已生效」
    await waitFor(() =>
      expect(
        store.fetchMock.mock.calls.some(
          (call) =>
            String(call[0]).endsWith('/jobs/POLICY_FETCH') &&
            call[1]?.method === 'PATCH' &&
            String(call[1]?.body).includes('"enabled":false'),
        ),
      ).toBe(true),
    );
    expect(await screen.findByTestId('task-note-done')).toBeInTheDocument();
  });

  it('编辑调度 Dialog：间隔非法值前端拦截不发请求；保存后「下一调度周期生效」常驻明示（PRD 场景 4.6 红线）', async () => {
    const store = makeStore();
    renderPage(store);
    const user = userEvent.setup();
    await screen.findByTestId('task-row-POLICY_FETCH');

    await user.click(screen.getByTestId('task-edit-POLICY_FETCH'));
    const intervalInput = await screen.findByTestId('task-edit-interval-POLICY_FETCH');
    await user.clear(intervalInput);
    await user.type(intervalInput, '0');
    await user.click(screen.getByTestId('task-edit-save-POLICY_FETCH'));

    // 前端拦截：不发 PATCH（§5.3 提交前拦截）
    expect(await screen.findByText('间隔须为正整数（秒）')).toBeInTheDocument();
    expect(store.fetchMock.mock.calls.filter((call) => call[1]?.method === 'PATCH')).toHaveLength(0);

    // 合法值保存 → PATCH intervalMillis（秒→毫秒）+ LIVE_NEXT_CYCLE 常驻明示
    await user.clear(intervalInput);
    await user.type(intervalInput, '7200');
    await user.click(screen.getByTestId('task-edit-save-POLICY_FETCH'));
    await waitFor(() =>
      expect(
        store.fetchMock.mock.calls.some(
          (call) =>
            String(call[0]).endsWith('/jobs/POLICY_FETCH') &&
            call[1]?.method === 'PATCH' &&
            String(call[1]?.body).includes('"intervalMillis":7200000'),
        ),
      ).toBe(true),
    );
    expect(await screen.findByTestId('task-note-next-cycle')).toBeInTheDocument();
  });

  it('编辑调度保存失败：Dialog 保持打开、错误渲染在字段下方且输入不丢，重试后成功关闭', async () => {
    const store = makeStore();
    renderPage(store);
    const user = userEvent.setup();
    await screen.findByTestId('task-row-POLICY_FETCH');

    await user.click(screen.getByTestId('task-edit-POLICY_FETCH'));
    const intervalInput = await screen.findByTestId('task-edit-interval-POLICY_FETCH');
    await user.clear(intervalInput);
    await user.type(intervalInput, '7200');

    // 保存失败一次：Dialog 不关、错误在 Dialog 内字段下方、输入保留
    store.fetchMock.mockImplementationOnce(async () => fail(500, 50000, '服务异常'));
    await user.click(screen.getByTestId('task-edit-save-POLICY_FETCH'));
    expect(await screen.findByTestId('task-edit-error-POLICY_FETCH')).toHaveTextContent(
      '服务异常',
    );
    expect(screen.getByTestId('task-edit-interval-POLICY_FETCH')).toHaveValue('7200');

    // 再次保存成功：Dialog 关闭，行内出现生效提示
    await user.click(screen.getByTestId('task-edit-save-POLICY_FETCH'));
    await waitFor(() =>
      expect(screen.queryByTestId('task-edit-interval-POLICY_FETCH')).toBeNull(),
    );
    expect(await screen.findByTestId('task-note-next-cycle')).toBeInTheDocument();
  });

  it('RESTART 生效模式：保存后渲染 amber「重启后生效」徽章（不误显已生效）', async () => {
    const jobs = fiveJobs().map((job) =>
      job.jobKey === 'POLICY_FETCH'
        ? { ...job, effectiveModes: { ...job.effectiveModes, intervalMillis: 'RESTART' as const } }
        : job,
    );
    const store = makeStore({ jobs });
    renderPage(store);
    const user = userEvent.setup();
    await screen.findByTestId('task-row-POLICY_FETCH');

    await user.click(screen.getByTestId('task-edit-POLICY_FETCH'));
    const intervalInput = await screen.findByTestId('task-edit-interval-POLICY_FETCH');
    await user.clear(intervalInput);
    await user.type(intervalInput, '7200');
    await user.click(screen.getByTestId('task-edit-save-POLICY_FETCH'));

    // intervalMillis 为 RESTART → 常驻「重启后生效」，不显示「已生效」/「下一调度周期生效」
    const badge = await screen.findByTestId('task-note-restart');
    expect(badge).toHaveTextContent('重启后生效');
    expect(badge.className).toContain('amber');
    expect(screen.queryByTestId('task-note-done')).toBeNull();
    expect(screen.queryByTestId('task-note-next-cycle')).toBeNull();
  });

  it('cron 型任务编辑：非法段数前端拦截（对齐后端 6 段 Spring cron）', async () => {
    const store = makeStore();
    renderPage(store);
    const user = userEvent.setup();
    await screen.findByTestId('task-row-DAILY_RECOMMEND');

    await user.click(screen.getByTestId('task-edit-DAILY_RECOMMEND'));
    const cronInput = await screen.findByTestId('task-edit-cron-DAILY_RECOMMEND');
    await user.clear(cronInput);
    await user.type(cronInput, '0 0 9 * *');
    await user.click(screen.getByTestId('task-edit-save-DAILY_RECOMMEND'));

    expect(await screen.findByText('cron 格式不正确（须为 6 段，如 0 0 9 * * ?）')).toBeInTheDocument();
    expect(store.fetchMock.mock.calls.filter((call) => call[1]?.method === 'PATCH')).toHaveLength(0);
  });

  it('「历史」跳转 #/job-logs?jobName= 预过滤（D2）', async () => {
    renderPage(makeStore());
    const user = userEvent.setup();
    await screen.findByTestId('task-row-POLICY_FETCH');

    await user.click(screen.getByTestId('task-history-POLICY_FETCH'));
    expect(window.location.hash).toBe('#/job-logs?jobName=PolicyFetchJob');
  });

  it('列表失败：错误 + 重试可恢复', async () => {
    const store = makeStore({ failGet: true });
    renderPage(store);
    expect(await screen.findByTestId('task-center-error')).toBeInTheDocument();

    store.fetchMock.mockImplementation(async (url: string) => {
      if (String(url).endsWith('/jobs')) {
        return ok({ jobs: fiveJobs() });
      }
      return ok(null);
    });
    await userEvent.setup().click(screen.getByTestId('task-center-retry'));
    expect(await screen.findByTestId('task-row-POLICY_FETCH')).toBeInTheDocument();
  });

  it('轮询节流：运行中 3s 一轮，全部终态切 30s 慢轮询（交互 4）', async () => {
    vi.useFakeTimers();
    const store = makeStore();
    renderPage(store);
    // 首屏加载（fake timers 下推进微任务直至行渲染）
    for (let i = 0; i < 10 && screen.queryByTestId('task-row-POLICY_FETCH') === null; i++) {
      await vi.advanceTimersByTimeAsync(10);
    }
    expect(screen.getByTestId('task-row-POLICY_FETCH')).toBeInTheDocument();

    // 存在运行中任务（ANOMALY_DETECT running）→ 3s 一轮
    const before3s = countGets(store);
    await vi.advanceTimersByTimeAsync(3000);
    expect(countGets(store)).toBe(before3s + 1);

    // 全部终态 → 下一轮切换 30s：3s 处不再触发，30s 处触发一次
    store.state.jobs = store.state.jobs.map((job) => ({ ...job, running: false }));
    await vi.advanceTimersByTimeAsync(3000); // 本轮 GET 携带全部终态 → hasRunning=false
    const before30s = countGets(store);
    await vi.advanceTimersByTimeAsync(3000);
    expect(countGets(store)).toBe(before30s);
    await vi.advanceTimersByTimeAsync(30000);
    expect(countGets(store)).toBe(before30s + 1);
  });
});
