import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { JobLog } from '@/pages/JobLog';
import type { JobLogPage, JobLogView } from '@/types/jobLog';

// —— fetch mock：GET /job-logs（游标分页 + jobName 过滤） —— #

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
  pages?: JobLogPage[];
  code?: number;
}

/** 构造状态化 fetch mock：列表按次序分页，可强制错误码。 */
function makeStore(opts: StoreOpts = {}) {
  let calls = 0;
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET';
    const path = String(url);
    if (method !== 'GET') return fail(50000);
    if (/\/job-logs(\?.*)?$/.test(path) && !/\/job-logs\//.test(path)) {
      if (opts.code) return fail(opts.code);
      const pages = opts.pages ?? [];
      const idx = Math.min(calls, pages.length - 1);
      calls++;
      return ok(pages[idx] ?? { items: [], nextCursor: null });
    }
    return fail(50000);
  });
  return { fetch };
}

function listCallsOf(
  fetchMock: ReturnType<typeof makeStore>['fetch'],
): { url: string; init?: RequestInit }[] {
  return fetchMock.mock.calls
    .map((c) => ({ url: String(c[0]), init: c[1] }))
    .filter((c) => /\/job-logs(\?.*)?$/.test(c.url) && !/\/job-logs\//.test(c.url));
}

// —— fixtures —— #

const LOG_SUCCESS: JobLogView = {
  id: 10,
  jobName: 'PolicyFetchJob',
  startTime: '2026-09-22T01:00:00Z',
  endTime: '2026-09-22T01:00:02Z',
  status: 'SUCCESS',
  durationMillis: 2000,
  processedCount: 9,
  errorCount: 0,
  errorMessage: null,
};
const LOG_FAILED: JobLogView = {
  id: 11,
  jobName: 'AnomalyDetectionJob',
  startTime: '2026-09-22T02:00:00Z',
  endTime: '2026-09-22T02:00:01Z',
  status: 'FAILED',
  durationMillis: 1000,
  processedCount: 5,
  errorCount: 2,
  errorMessage: 'java.lang.RuntimeException: boom',
};
const LOG_STARTED: JobLogView = {
  id: 12,
  jobName: 'PushRetryJob',
  startTime: '2026-09-22T03:00:00Z',
  endTime: null,
  status: 'STARTED',
  durationMillis: null,
  processedCount: 0,
  errorCount: 0,
  errorMessage: null,
};

const PAGE_FULL: JobLogPage = {
  items: [LOG_SUCCESS, LOG_FAILED, LOG_STARTED],
  nextCursor: null,
};

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  localStorage.clear();
  window.location.hash = '';
});

describe('JobLog 执行日志页', () => {
  it('渲染日志列表（Job/时间/状态徽章/耗时/处理错误/异常信息），无加载更多', async () => {
    const store = makeStore({ pages: [PAGE_FULL] });
    vi.stubGlobal('fetch', store.fetch);
    render(<JobLog />);

    const row = await screen.findByTestId('job-log-row-10');
    expect(row).toHaveTextContent('PolicyFetchJob');
    // 开始时间按本地时区格式化（yyyy-MM-dd HH:mm:ss），不固化具体值以免受 CI 时区影响
    expect(row.textContent ?? '').toMatch(/\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/);
    expect(row).toHaveTextContent('2.00s');
    expect(screen.getByTestId('job-log-processed-10')).toHaveTextContent('9');

    // 三态徽章
    expect(screen.getByTestId('job-status-SUCCESS')).toHaveTextContent('成功');
    expect(screen.getByTestId('job-status-FAILED')).toHaveTextContent('失败');
    expect(screen.getByTestId('job-status-STARTED')).toHaveTextContent('执行中');

    // STARTED 行：endTime/耗时 显示 —
    const startedRow = screen.getByTestId('job-log-row-12');
    expect(startedRow).toHaveTextContent('—');

    // 无加载更多（nextCursor=null）
    expect(screen.queryByTestId('job-log-load-more')).toBeNull();
  });

  it('状态徽章配色与文字：SUCCESS 绿 / FAILED 红 / STARTED 黄', async () => {
    const store = makeStore({ pages: [PAGE_FULL] });
    vi.stubGlobal('fetch', store.fetch);
    render(<JobLog />);

    await screen.findByTestId('job-log-row-10');
    const success = screen.getByTestId('job-status-SUCCESS');
    expect(success.className).toContain('emerald');
    const failed = screen.getByTestId('job-status-FAILED');
    expect(failed.className).toContain('rose');
    const started = screen.getByTestId('job-status-STARTED');
    expect(started.className).toContain('amber');
  });

  it('切换 jobName 过滤：带 jobName 参数重新拉首页', async () => {
    const store = makeStore({ pages: [PAGE_FULL] });
    vi.stubGlobal('fetch', store.fetch);
    const user = userEvent.setup();
    render(<JobLog />);

    await screen.findByTestId('job-log-row-10');
    await user.selectOptions(screen.getByTestId('job-name-filter'), 'PolicyFetchJob');

    const calls = listCallsOf(store.fetch);
    const filtered = calls.filter((c) =>
      new URL(c.url, 'http://x').searchParams.get('jobName'),
    );
    expect(filtered.length).toBeGreaterThanOrEqual(1);
    expect(new URL(filtered[0].url, 'http://x').searchParams.get('jobName')).toBe(
      'PolicyFetchJob',
    );
    expect(new URL(filtered[0].url, 'http://x').searchParams.get('cursor')).toBeNull();
  });

  it('游标分页：nextCursor 存在时显示「加载更多」，点击追加下一页带 cursor', async () => {
    const page1: JobLogPage = {
      items: [{ ...LOG_SUCCESS, id: 1 }],
      nextCursor: 1,
    };
    const page2: JobLogPage = {
      items: [{ ...LOG_SUCCESS, id: 2 }],
      nextCursor: null,
    };
    const store = makeStore({ pages: [page1, page2] });
    vi.stubGlobal('fetch', store.fetch);
    const user = userEvent.setup();
    render(<JobLog />);

    await screen.findByTestId('job-log-row-1');
    expect(screen.getByTestId('job-log-load-more')).toBeInTheDocument();

    await user.click(screen.getByTestId('job-log-load-more'));

    expect(await screen.findByTestId('job-log-row-2')).toBeInTheDocument();
    expect(screen.getByTestId('job-log-row-1')).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.queryByTestId('job-log-load-more')).toBeNull(),
    );

    const calls = listCallsOf(store.fetch);
    expect(calls.length).toBeGreaterThanOrEqual(2);
    expect(new URL(calls[1].url, 'http://x').searchParams.get('cursor')).toBe('1');
  });

  it('加载更多失败：保留既有条目，按钮变重试入口，重试成功后追加（不清列表）', async () => {
    const page1: JobLogPage = {
      items: [{ ...LOG_SUCCESS, id: 1 }],
      nextCursor: 1,
    };
    const page2: JobLogPage = {
      items: [{ ...LOG_SUCCESS, id: 2 }],
      nextCursor: null,
    };
    const store = makeStore({ pages: [page1, page2] });
    vi.stubGlobal('fetch', store.fetch);
    const user = userEvent.setup();
    render(<JobLog />);

    await screen.findByTestId('job-log-row-1');

    // 翻页失败一次：既有条目保留 + 错误提示 + 按钮可重试（不被整页错误块替换）
    store.fetch.mockImplementationOnce(async () => fail(50000));
    await user.click(screen.getByTestId('job-log-load-more'));
    expect(await screen.findByTestId('job-log-more-error')).toHaveTextContent('服务异常');
    expect(screen.getByTestId('job-log-row-1')).toBeInTheDocument();
    expect(screen.queryByTestId('job-log-error')).toBeNull();
    expect(screen.getByTestId('job-log-load-more')).toBeInTheDocument();

    // 重试成功：追加下一页，错误清除
    await user.click(screen.getByTestId('job-log-load-more'));
    expect(await screen.findByTestId('job-log-row-2')).toBeInTheDocument();
    expect(screen.getByTestId('job-log-row-1')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByTestId('job-log-more-error')).toBeNull());
  });

  it('列表为空：展示空态文案，无加载更多', async () => {
    const store = makeStore({ pages: [{ items: [], nextCursor: null }] });
    vi.stubGlobal('fetch', store.fetch);
    render(<JobLog />);

    expect(await screen.findByTestId('job-log-empty')).toHaveTextContent(
      '暂无 Job 执行记录',
    );
    expect(screen.queryByTestId('job-log-load-more')).toBeNull();
  });

  it('列表 500：展示错误与重试，重试后恢复', async () => {
    const store = makeStore({ code: 50000 });
    vi.stubGlobal('fetch', store.fetch);
    const user = userEvent.setup();
    render(<JobLog />);

    expect(await screen.findByTestId('job-log-error')).toHaveTextContent('服务异常');

    store.fetch.mockImplementationOnce(async () => ok(PAGE_FULL));
    await user.click(screen.getByTestId('job-log-retry'));

    expect(await screen.findByTestId('job-log-row-10')).toBeInTheDocument();
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
