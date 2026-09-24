import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { JobLog } from '@/pages/JobLog';
import type { JobExecutionStatus, JobLogPagedView, JobLogView } from '@/types/jobLog';

// —— fetch mock：GET /job-logs（页码分页 + jobName/status 过滤） —— #

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

/** 手动决斗 Promise（在途/竞态用例：挂起请求，测试择机放行）。 */
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

interface ServerOpts {
  items?: JobLogView[];
  code?: number;
}

/**
 * 构造页码模式服务端 mock：按 query（page/size/jobName/status）过滤切片，
 * 返回 { items, total, page, size }（对齐后端契约：越界页 200 空列表 + 精确 total）。
 */
function makeServer(opts: ServerOpts = {}) {
  let items = opts.items ?? [];
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET';
    const path = String(url);
    if (method !== 'GET') return fail(50000);
    if (/\/job-logs\?/.test(path)) {
      if (opts.code) return fail(opts.code);
      const q = new URL(path, 'http://x').searchParams;
      const page = Number(q.get('page') ?? '1');
      const size = Number(q.get('size') ?? '20');
      const jobName = q.get('jobName') ?? '';
      const status = q.get('status') ?? '';
      let filtered = items;
      if (jobName) filtered = filtered.filter((l) => l.jobName === jobName);
      if (status) filtered = filtered.filter((l) => l.status === status);
      const view: JobLogPagedView = {
        items: filtered.slice((page - 1) * size, page * size),
        total: filtered.length,
        page,
        size,
      };
      return ok(view);
    }
    return fail(50000);
  });
  return {
    fetch,
    /** 模拟服务端数据漂移（空页回退用例）。 */
    setItems(next: JobLogView[]) {
      items = next;
    },
  };
}

function listCallsOf(
  fetchMock: ReturnType<typeof makeServer>['fetch'],
): { url: string }[] {
  return fetchMock.mock.calls
    .map((c) => ({ url: String(c[0]) }))
    .filter((c) => /\/job-logs\?/.test(c.url));
}

function paramsOf(call: { url: string }): URLSearchParams {
  return new URL(call.url, 'http://x').searchParams;
}

// —— fixtures：45 条 = SUCCESS 18 / FAILED 15 / STARTED 12；jobName 三轮换（各 15） —— #

const JOB_NAMES = ['PolicyFetchJob', 'AnomalyDetectionJob', 'PushRetryJob'] as const;

function makeLog(index: number): JobLogView {
  const status: JobExecutionStatus =
    index < 18 ? 'SUCCESS' : index < 33 ? 'FAILED' : 'STARTED';
  return {
    id: index + 1,
    jobName: JOB_NAMES[index % 3],
    startTime: '2026-09-22T01:00:00Z',
    endTime: status === 'STARTED' ? null : '2026-09-22T01:00:02Z',
    status,
    durationMillis: status === 'STARTED' ? null : 2000,
    processedCount: 9,
    errorCount: status === 'FAILED' ? 2 : 0,
    errorMessage: status === 'FAILED' ? 'java.lang.RuntimeException: boom' : null,
  };
}

const ALL_LOGS: JobLogView[] = Array.from({ length: 45 }, (_, i) => makeLog(i));

// jsdom 未实现 scrollIntoView：打桩（翻页/筛选成功后滚回表格顶）
beforeAll(() => {
  HTMLElement.prototype.scrollIntoView = vi.fn();
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.clearAllMocks();
  localStorage.clear();
  window.location.hash = '';
});

describe('JobLog 执行日志页 · 首屏与分页', () => {
  it('首屏：表格渲染 + 分页条（共 45 条 / 第 1 / 3 页），首查 page=1&size=20 不带 status', async () => {
    const server = makeServer({ items: ALL_LOGS });
    vi.stubGlobal('fetch', server.fetch);
    render(<JobLog />);

    const row = await screen.findByTestId('job-log-row-1');
    expect(row).toHaveTextContent('PolicyFetchJob');
    // 开始/结束时间按本地时区格式化（yyyy-MM-dd HH:mm:ss）
    expect(row.textContent ?? '').toMatch(/\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/);
    expect(row).toHaveTextContent('2.00s');
    expect(screen.getByTestId('job-log-processed-1')).toHaveTextContent('9');

    // 分页条与首查参数
    expect(screen.getByTestId('pagination-total')).toHaveTextContent('共 45 条');
    expect(screen.getByTestId('pagination-page-indicator')).toHaveTextContent(
      '第 1 / 3 页',
    );
    expect(screen.getByTestId('pagination-page-1')).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(screen.getByTestId('pagination-prev')).toBeDisabled();
    expect(screen.getByTestId('pagination-next')).toBeEnabled();

    const calls = listCallsOf(server.fetch);
    expect(calls.length).toBeGreaterThanOrEqual(1);
    expect(paramsOf(calls[0]).get('page')).toBe('1');
    expect(paramsOf(calls[0]).get('size')).toBe('20');
    expect(paramsOf(calls[0]).get('status')).toBeNull();
    expect(paramsOf(calls[0]).get('cursor')).toBeNull();

    // 「加载更多」链路已删除
    expect(screen.queryByTestId('job-log-load-more')).toBeNull();
    expect(screen.queryByTestId('job-log-more-error')).toBeNull();
  });

  it('状态徽章配色与文字：SUCCESS 绿 / FAILED 红 / STARTED 黄（执行中经筛选可见）', async () => {
    const server = makeServer({ items: ALL_LOGS });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<JobLog />);

    await screen.findByTestId('job-log-row-1');
    const successes = screen.getAllByTestId('job-status-SUCCESS');
    expect(successes[0]).toHaveTextContent('成功');
    expect(successes[0].className).toContain('emerald');
    const failed = screen.getAllByTestId('job-status-FAILED');
    expect(failed[0]).toHaveTextContent('失败');
    expect(failed[0].className).toContain('rose');

    // STARTED 从第 34 条起：切「执行中」档后断言黄徽章
    await user.click(screen.getByTestId('job-status-filter-STARTED'));
    const started = await screen.findAllByTestId('job-status-STARTED');
    expect(started[0]).toHaveTextContent('执行中');
    expect(started[0].className).toContain('amber');
  });

  it('跳页整体替换：点第 2 页 → 请求 page=2，行替换、高亮切换、滚回表格顶', async () => {
    const server = makeServer({ items: ALL_LOGS });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<JobLog />);

    await screen.findByTestId('job-log-row-1');
    vi.mocked(HTMLElement.prototype.scrollIntoView).mockClear();

    await user.click(screen.getByTestId('pagination-page-2'));

    expect(await screen.findByTestId('job-log-row-21')).toBeInTheDocument();
    expect(screen.queryByTestId('job-log-row-1')).toBeNull();
    expect(screen.getByTestId('pagination-page-indicator')).toHaveTextContent(
      '第 2 / 3 页',
    );
    expect(screen.getByTestId('pagination-page-2')).toHaveAttribute(
      'aria-current',
      'page',
    );

    const calls = listCallsOf(server.fetch);
    expect(paramsOf(calls[calls.length - 1]).get('page')).toBe('2');
    expect(HTMLElement.prototype.scrollIntoView).toHaveBeenCalled();
  });

  it('翻页失败：保留当前页 + 「加载第 2 页失败」错误行 + 重试同页成功', async () => {
    const server = makeServer({ items: ALL_LOGS });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<JobLog />);

    await screen.findByTestId('job-log-row-1');

    server.fetch.mockImplementationOnce(async () => fail(50000));
    await user.click(screen.getByTestId('pagination-page-2'));

    expect(
      await screen.findByTestId('job-log-pagination-error'),
    ).toHaveTextContent('加载第 2 页失败：服务异常');
    expect(screen.getByTestId('job-log-row-1')).toBeInTheDocument();
    expect(screen.getByTestId('pagination-page-indicator')).toHaveTextContent(
      '第 1 / 3 页',
    );
    expect(screen.queryByTestId('job-log-error')).toBeNull();
    expect(screen.getByTestId('pagination-next')).toBeEnabled();

    await user.click(screen.getByTestId('job-log-pagination-retry'));
    expect(await screen.findByTestId('job-log-row-21')).toBeInTheDocument();
    expect(screen.queryByTestId('job-log-row-1')).toBeNull();
    await waitFor(() =>
      expect(screen.queryByTestId('job-log-pagination-error')).toBeNull(),
    );
    const calls = listCallsOf(server.fetch);
    expect(paramsOf(calls[calls.length - 1]).get('page')).toBe('2');
  });

  it('每页条数切换：size=50 → page=1 重查，45 条收敛单页精简态', async () => {
    const server = makeServer({ items: ALL_LOGS });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<JobLog />);

    await screen.findByTestId('job-log-row-20');

    await user.selectOptions(screen.getByTestId('pagination-size'), '50');
    await waitFor(() =>
      expect(screen.getByTestId('job-log-row-45')).toBeInTheDocument(),
    );
    expect(screen.getByTestId('pagination-page-indicator')).toHaveTextContent(
      '第 1 / 1 页',
    );
    expect(screen.queryByTestId('pagination-prev')).toBeNull();
    expect(screen.queryByTestId('pagination-next')).toBeNull();
    expect(screen.getByTestId('pagination-size')).toBeInTheDocument();
    const calls = listCallsOf(server.fetch);
    const last = paramsOf(calls[calls.length - 1]);
    expect(last.get('page')).toBe('1');
    expect(last.get('size')).toBe('50');
  });
});

describe('JobLog 执行日志页 · 筛选联动', () => {
  it('状态分段 4 档：切换失败 → status=FAILED&page=1，total 按筛选刷新', async () => {
    const server = makeServer({ items: ALL_LOGS });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<JobLog />);

    await screen.findByTestId('job-log-row-1');

    // 四档齐全（全部/成功/失败/执行中），默认「全部」高亮
    const all = screen.getByTestId('job-status-filter-all');
    expect(all).toHaveTextContent('全部');
    expect(screen.getByTestId('job-status-filter-SUCCESS')).toHaveTextContent('成功');
    expect(screen.getByTestId('job-status-filter-FAILED')).toHaveTextContent('失败');
    expect(screen.getByTestId('job-status-filter-STARTED')).toHaveTextContent('执行中');
    expect(all.className).toContain('bg-primary');

    await user.click(screen.getByTestId('job-status-filter-FAILED'));
    await waitFor(() =>
      expect(screen.getByTestId('pagination-total')).toHaveTextContent('共 15 条'),
    );
    expect(screen.getByTestId('job-status-filter-FAILED').className).toContain(
      'bg-primary',
    );
    expect(screen.getByTestId('job-status-filter-all').className).not.toContain(
      'bg-primary',
    );
    // 筛后结果全为 FAILED 行
    expect(screen.getByTestId('job-log-row-19')).toBeInTheDocument();
    expect(screen.queryByTestId('job-log-row-1')).toBeNull();
    expect(screen.getByTestId('pagination-page-indicator')).toHaveTextContent(
      '第 1 / 1 页',
    );

    const calls = listCallsOf(server.fetch);
    const last = paramsOf(calls[calls.length - 1]);
    expect(last.get('status')).toBe('FAILED');
    expect(last.get('page')).toBe('1');
  });

  it('切回「全部」：不发 status 参数，total 回全量', async () => {
    const server = makeServer({ items: ALL_LOGS });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<JobLog />);

    await screen.findByTestId('job-log-row-1');
    await user.click(screen.getByTestId('job-status-filter-FAILED'));
    await waitFor(() =>
      expect(screen.getByTestId('pagination-total')).toHaveTextContent('共 15 条'),
    );

    await user.click(screen.getByTestId('job-status-filter-all'));
    await waitFor(() =>
      expect(screen.getByTestId('pagination-total')).toHaveTextContent('共 45 条'),
    );
    const calls = listCallsOf(server.fetch);
    expect(paramsOf(calls[calls.length - 1]).get('status')).toBeNull();
  });

  it('jobName 过滤：带 jobName&page=1 重查', async () => {
    const server = makeServer({ items: ALL_LOGS });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<JobLog />);

    await screen.findByTestId('job-log-row-1');
    await user.selectOptions(
      screen.getByTestId('job-name-filter'),
      'AnomalyDetectionJob',
    );
    await waitFor(() =>
      expect(screen.getByTestId('pagination-total')).toHaveTextContent('共 15 条'),
    );

    const calls = listCallsOf(server.fetch);
    const last = paramsOf(calls[calls.length - 1]);
    expect(last.get('jobName')).toBe('AnomalyDetectionJob');
    expect(last.get('page')).toBe('1');
  });

  it('jobName × status 组合过滤：一次请求交集刷新 total', async () => {
    const server = makeServer({ items: ALL_LOGS });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<JobLog />);

    await screen.findByTestId('job-log-row-1');
    await user.selectOptions(
      screen.getByTestId('job-name-filter'),
      'PolicyFetchJob',
    );
    await waitFor(() =>
      expect(screen.getByTestId('pagination-total')).toHaveTextContent('共 15 条'),
    );

    await user.click(screen.getByTestId('job-status-filter-SUCCESS'));
    await waitFor(() =>
      expect(screen.getByTestId('pagination-total')).toHaveTextContent('共 6 条'),
    );
    const calls = listCallsOf(server.fetch);
    const last = paramsOf(calls[calls.length - 1]);
    expect(last.get('jobName')).toBe('PolicyFetchJob');
    expect(last.get('status')).toBe('SUCCESS');
    expect(last.get('page')).toBe('1');
  });

  it('URL 预过滤：initialJobName 初始即带 jobName 查第 1 页', async () => {
    const server = makeServer({ items: ALL_LOGS });
    vi.stubGlobal('fetch', server.fetch);
    render(<JobLog initialJobName="PolicyFetchJob" />);

    expect(await screen.findByTestId('job-log-row-1')).toBeInTheDocument();
    expect(screen.getByTestId('pagination-total')).toHaveTextContent('共 15 条');
    const calls = listCallsOf(server.fetch);
    const first = paramsOf(calls[0]);
    expect(first.get('jobName')).toBe('PolicyFetchJob');
    expect(first.get('page')).toBe('1');
    expect(first.get('size')).toBe('20');
  });

  it('jobName 下拉缓存全量集：状态过滤后下拉仍含全部 Job 名', async () => {
    const server = makeServer({ items: ALL_LOGS });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<JobLog />);

    await screen.findByTestId('job-log-row-1');
    await user.click(screen.getByTestId('job-status-filter-FAILED'));
    await waitFor(() =>
      expect(screen.getByTestId('pagination-total')).toHaveTextContent('共 15 条'),
    );

    const select = screen.getByTestId('job-name-filter');
    expect(within(select).getByRole('option', { name: '全部 Job' })).toBeInTheDocument();
    expect(
      within(select).getByRole('option', { name: 'PolicyFetchJob' }),
    ).toBeInTheDocument();
    expect(
      within(select).getByRole('option', { name: 'PushRetryJob' }),
    ).toBeInTheDocument();
  });

  it('空页防御回退：浏览期间数据收缩 → 静默重发末页，不渲染空页', async () => {
    const server = makeServer({ items: ALL_LOGS });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<JobLog />);

    await screen.findByTestId('job-log-row-1');
    await user.click(screen.getByTestId('pagination-page-2'));
    expect(await screen.findByTestId('job-log-row-21')).toBeInTheDocument();

    // 服务端数据 45 → 30（第 3 页消失）：UI 仍按旧 total 渲染第 3 页入口
    server.setItems(ALL_LOGS.slice(0, 30));
    await user.click(screen.getByTestId('pagination-page-3'));

    // 期望：page=3 返回空 + total=30 → 静默自动重发 ceil(30/20)=2 页
    expect(await screen.findByTestId('job-log-row-21')).toBeInTheDocument();
    expect(screen.getByTestId('pagination-page-indicator')).toHaveTextContent(
      '第 2 / 2 页',
    );
    expect(screen.getByTestId('pagination-total')).toHaveTextContent('共 30 条');

    const calls = listCallsOf(server.fetch);
    expect(paramsOf(calls[calls.length - 2]).get('page')).toBe('3');
    expect(paramsOf(calls[calls.length - 1]).get('page')).toBe('2');
  });

  it('在途竞态：筛选变更中止翻页，旧响应不覆盖新结果', async () => {
    const server = makeServer({ items: ALL_LOGS });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<JobLog />);

    await screen.findByTestId('job-log-row-1');

    // 翻第 2 页挂起
    const d = deferred<ReturnType<typeof ok>>();
    server.fetch.mockImplementationOnce(() => d.promise);
    await user.click(screen.getByTestId('pagination-page-2'));
    expect(await screen.findByTestId('job-log-page-loading')).toBeInTheDocument();

    // 状态筛选中止翻页、走骨架新查询（FAILED 15 条）
    await user.click(screen.getByTestId('job-status-filter-FAILED'));
    await waitFor(() =>
      expect(screen.getByTestId('pagination-total')).toHaveTextContent('共 15 条'),
    );

    // 旧翻页响应迟到：含独有标记行 id 999，不得覆盖筛选结果
    d.resolve(
      ok({ items: [{ ...ALL_LOGS[0], id: 999 }], total: 45, page: 2, size: 20 }),
    );
    await waitFor(() =>
      expect(screen.queryByTestId('job-log-page-loading')).toBeNull(),
    );
    expect(screen.queryByTestId('job-log-row-999')).toBeNull();
    expect(screen.getByTestId('pagination-total')).toHaveTextContent('共 15 条');
  });
});

describe('JobLog 执行日志页 · 空态与回归', () => {
  it('无筛选无结果：「暂无 Job 执行记录」，无清除筛选 CTA', async () => {
    const server = makeServer({ items: [] });
    vi.stubGlobal('fetch', server.fetch);
    render(<JobLog />);

    expect(await screen.findByTestId('job-log-empty')).toHaveTextContent(
      '暂无 Job 执行记录',
    );
    expect(screen.queryByTestId('job-log-clear-filters')).toBeNull();
    expect(screen.queryByTestId('pagination-root')).toBeNull();
  });

  it('有筛选无结果：区分性空态 + 清除筛选 CTA → 回全量（不回写 URL）', async () => {
    const onlyAnomaly = ALL_LOGS.filter((l) => l.jobName === 'AnomalyDetectionJob');
    const server = makeServer({ items: onlyAnomaly });
    vi.stubGlobal('fetch', server.fetch);
    window.location.hash = '#/job-logs?jobName=PolicyFetchJob';
    const user = userEvent.setup();
    render(<JobLog initialJobName="PolicyFetchJob" />);

    const empty = await screen.findByTestId('job-log-empty');
    expect(empty).toHaveTextContent('未找到匹配的 Job 执行记录');
    expect(empty).toHaveTextContent('可调整 Job 或状态筛选后重试');
    expect(screen.getByTestId('job-log-clear-filters')).toBeInTheDocument();
    expect(screen.queryByTestId('pagination-root')).toBeNull();

    await user.click(screen.getByTestId('job-log-clear-filters'));

    expect(await screen.findByTestId('job-log-row-2')).toBeInTheDocument();
    expect(screen.getByTestId('pagination-total')).toHaveTextContent('共 15 条');
    expect(screen.getByTestId('job-name-filter')).toHaveValue('');
    // 清除筛选只改 state：URL 入口参数不回写、不跳转（D9）
    expect(window.location.hash).toBe('#/job-logs?jobName=PolicyFetchJob');
    const calls = listCallsOf(server.fetch);
    const last = paramsOf(calls[calls.length - 1]);
    expect(last.get('jobName')).toBeNull();
    expect(last.get('status')).toBeNull();
    expect(last.get('page')).toBe('1');
  });

  it('列表 500：整页错误 + 重试恢复', async () => {
    const server = makeServer({ code: 50000 });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<JobLog />);

    expect(await screen.findByTestId('job-log-error')).toHaveTextContent('服务异常');

    server.fetch.mockImplementationOnce(async (url: string) => {
      const q = new URL(String(url), 'http://x').searchParams;
      return ok({
        items: ALL_LOGS.slice(0, Number(q.get('size') ?? '20')),
        total: ALL_LOGS.length,
        page: Number(q.get('page') ?? '1'),
        size: Number(q.get('size') ?? '20'),
      });
    });
    await user.click(screen.getByTestId('job-log-retry'));

    expect(await screen.findByTestId('job-log-row-1')).toBeInTheDocument();
    expect(screen.getByTestId('pagination-total')).toHaveTextContent('共 45 条');
  });

  it('未认证 401：http 层清 token 并跳 /login', async () => {
    const fetch = vi.fn(async () => ({
      ok: false,
      status: 401,
      json: async () => ({ code: 1003, msg: '未提供认证令牌', data: null, traceId: 't' }),
    }));
    vi.stubGlobal('fetch', fetch);
    localStorage.setItem('access_token', 'stale');
    render(<JobLog />);

    await waitFor(() => expect(localStorage.getItem('access_token')).toBeNull());
    expect(window.location.hash).toContain('/login');
  });
});
