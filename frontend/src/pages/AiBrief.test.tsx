import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent, { type UserEvent } from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AiBrief } from '@/pages/AiBrief';
import type { AiBriefView } from '@/types/aibrief';

// —— fetch mock store：POST /ai-briefs（202+taskId 或错误码）/ GET /ai-briefs/{id}（按序列返回） —— #

const HTTP_BY_CODE: Record<number, number> = {
  2001: 400,
  30001: 404,
  30030: 429,
  30032: 404,
  50000: 500,
};
const MSG_BY_CODE: Record<number, string> = {
  2001: '参数非法',
  30001: '标的不存在',
  30030: '配额用尽',
  30032: '简报任务不存在',
  50000: '服务异常',
};

const ok = (data: unknown) => ({
  ok: true,
  status: 200,
  json: async () => ({ code: 0, msg: 'ok', data, traceId: 't' }),
});
const accepted = (data: unknown) => ({
  ok: true,
  status: 202,
  json: async () => ({ code: 0, msg: 'accepted', data, traceId: 't' }),
});
const httpFail = (code: number) => ({
  ok: false,
  status: HTTP_BY_CODE[code] ?? 500,
  json: async () => ({
    code,
    msg: MSG_BY_CODE[code] ?? '服务异常',
    data: null,
    traceId: 't',
  }),
});

interface StoreOpts {
  /** POST /ai-briefs 强制返回该错误码（模拟配额 30030 / 标的缺失 30001）。 */
  createCode?: number;
  /** GET /ai-briefs/{id} 按调用次序返回的简报视图序列（首条通常 PENDING，演示轮询）。 */
  getSequence?: AiBriefView[];
  /** 若设置，GET 恒返回该视图（用于 loading 态：始终 PENDING）。 */
  getAlways?: AiBriefView;
}

function makeStore(opts: StoreOpts = {}) {
  let getCalls = 0;
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET';
    const path = String(url);

    if (method === 'POST' && /\/ai-briefs$/.test(path)) {
      if (opts.createCode) return httpFail(opts.createCode);
      return accepted({ taskId: 1 });
    }
    if (method === 'GET') {
      const m = path.match(/\/ai-briefs\/(\d+)$/);
      if (m) {
        let view: AiBriefView;
        if (opts.getAlways) {
          view = opts.getAlways;
        } else {
          const seq = opts.getSequence ?? [];
          const idx = Math.min(getCalls, seq.length - 1);
          view = seq[idx];
          getCalls++;
        }
        return ok(view);
      }
    }
    return httpFail(50000);
  });
  return { fetch };
}

const DISCLAIMER = 'AI 生成，非投资建议';

const PENDING_VIEW: AiBriefView = {
  status: 0,
  content: null,
  sourceLinks: null,
  disclaimer: DISCLAIMER,
};

const DONE_CONTENT = {
  summary: '近24h公告与新闻偏利好，半年度报告营收净利双增。',
  keyEvents: [
    {
      event: '半年度报告发布',
      impact: '利好',
      reason: '营收同比增长',
      sourceUrl: 'http://example.com/event1',
    },
  ],
  bias: '利好',
  biasReason: '营收与净利双增',
  watchSuggestion: '关注后续量能配合',
  facts: [
    {
      claim: '归母净利润同比增长15%',
      metric: 'net_profit',
      value: 415.5,
      source: 'FINANCE',
      sourceUrl: 'http://example.com/fact1',
    },
  ],
  disclaimer: DISCLAIMER,
};

const DONE_VIEW: AiBriefView = {
  status: 1,
  content: DONE_CONTENT,
  sourceLinks: ['http://example.com/fact1', 'http://example.com/event1'],
  disclaimer: DISCLAIMER,
};

const VERIFY_VIEW: AiBriefView = {
  status: 3,
  content: DONE_CONTENT,
  sourceLinks: ['http://example.com/fact1'],
  disclaimer: DISCLAIMER,
};

const FAILED_VIEW: AiBriefView = {
  status: 2,
  content: null,
  sourceLinks: null,
  disclaimer: DISCLAIMER,
};

afterEach(() => {
  // 先卸载（停轮询定时器、置 cancelled），再还原 fetch，避免在途 tick 命中已还原的全局
  cleanup();
  vi.unstubAllGlobals();
  localStorage.clear();
  window.location.hash = '';
});

/** 触发简报：填标的 ID 1（默认个股型）→ 提交。 */
async function triggerBrief(user: UserEvent, subjectId = '1') {
  await user.type(screen.getByTestId('brief-subjectId'), subjectId);
  await user.click(screen.getByTestId('brief-trigger-submit'));
}

describe('AiBrief AI 简报页', () => {
  it('触发→202→轮询→status=1 展示 content / 事实回链 / 免责', async () => {
    const store = makeStore({ getSequence: [PENDING_VIEW, DONE_VIEW] });
    vi.stubGlobal('fetch', store.fetch);
    const user = userEvent.setup();
    render(<AiBrief pollIntervalMs={50} />);

    await triggerBrief(user);

    // 轮询：PENDING→DONE，最终展示简报内容
    const result = await screen.findByTestId('brief-result');
    expect(within(result).getByTestId('brief-content')).toBeInTheDocument();
    // 核心摘要 / 关键事件 / 倾向理由 / 关注建议
    expect(screen.getByTestId('brief-summary')).toHaveTextContent(
      '近24h公告与新闻偏利好',
    );
    expect(screen.getByTestId('brief-key-event-0')).toHaveTextContent(
      '半年度报告发布',
    );
    expect(screen.getByTestId('brief-bias-reason')).toHaveTextContent(
      '营收与净利双增',
    );
    expect(screen.getByTestId('brief-suggestion')).toHaveTextContent(
      '关注后续量能配合',
    );
    // 倾向徽章（A 股惯例：利好→红）
    expect(screen.getByTestId('brief-bias')).toBeInTheDocument();
    // 事实回链：metric / value / 原文链接
    expect(screen.getByTestId('brief-fact-0')).toHaveTextContent('net_profit');
    expect(screen.getByTestId('brief-fact-value-0')).toHaveTextContent('415.5');
    expect(screen.getByTestId('brief-fact-link-0')).toHaveAttribute(
      'href',
      'http://example.com/fact1',
    );
    // 顶层 sourceLinks 去重后展示为相关来源
    expect(screen.getByTestId('brief-source-link-0')).toBeInTheDocument();
    // 免责声明（恒附）
    expect(screen.getAllByTestId('brief-disclaimer').length).toBeGreaterThan(0);
    // 状态徽章：已完成
    expect(screen.getByTestId('brief-status-1')).toBeInTheDocument();
  });

  it('status=3 待核实：content 仍展示并标「部分数值待核实」', async () => {
    const store = makeStore({ getSequence: [PENDING_VIEW, VERIFY_VIEW] });
    vi.stubGlobal('fetch', store.fetch);
    const user = userEvent.setup();
    render(<AiBrief pollIntervalMs={50} />);

    await triggerBrief(user);

    await screen.findByTestId('brief-content');
    expect(await screen.findByTestId('brief-need-verify')).toHaveTextContent(
      '部分数值待核实',
    );
    // 待核实徽章 + 免责仍在
    expect(screen.getByTestId('brief-status-3')).toBeInTheDocument();
    expect(screen.getAllByTestId('brief-disclaimer').length).toBeGreaterThan(0);
  });

  it('status=2 失败：展示「生成失败，请重试」+ 重试按钮，重试再次发起 POST', async () => {
    const store = makeStore({ getSequence: [PENDING_VIEW, FAILED_VIEW] });
    vi.stubGlobal('fetch', store.fetch);
    const user = userEvent.setup();
    render(<AiBrief pollIntervalMs={50} />);

    await triggerBrief(user);

    const failed = await screen.findByTestId('brief-failed');
    expect(failed).toHaveTextContent('生成失败，请重试');
    expect(screen.getByTestId('brief-status-2')).toBeInTheDocument();

    // 重试：再次 POST /ai-briefs（幂等可能返回同 taskId，仍重启轮询）
    await user.click(screen.getByTestId('brief-retry'));
    await screen.findByTestId('brief-failed'); // 序列已耗尽，仍 FAILED
    const postCalls = store.fetch.mock.calls.filter(
      (c) => (c[1] as RequestInit).method === 'POST' && /\/ai-briefs$/.test(String(c[0])),
    );
    expect(postCalls).toHaveLength(2);
  });

  it('loading 态：处理中显示骨架与「生成中…」（不裸转圈）', async () => {
    const store = makeStore({ getAlways: PENDING_VIEW });
    vi.stubGlobal('fetch', store.fetch);
    const user = userEvent.setup();
    render(<AiBrief pollIntervalMs={50} />);

    await triggerBrief(user);

    const loading = await screen.findByTestId('brief-loading');
    expect(loading).toHaveTextContent('生成中…');
    expect(screen.getByTestId('brief-status-0')).toBeInTheDocument();
    // 提交按钮带 Idempotency-Key 头（审计）
    const postCall = store.fetch.mock.calls.find(
      (c) => (c[1] as RequestInit).method === 'POST' && /\/ai-briefs$/.test(String(c[0])),
    );
    expect(postCall).toBeDefined();
    expect(
      (postCall![1] as RequestInit).headers as Record<string, string>,
    ).toHaveProperty('Idempotency-Key');
  });

  it('创建 30030 配额用尽：展示配额提示且不进入轮询', async () => {
    const store = makeStore({ createCode: 30030 });
    vi.stubGlobal('fetch', store.fetch);
    const user = userEvent.setup();
    render(<AiBrief pollIntervalMs={50} />);

    await triggerBrief(user);

    expect(await screen.findByTestId('brief-create-error')).toHaveTextContent(
      '今日 AI 简报配额已用尽',
    );
    // 未生成 taskId → 仍为初始空态，无 loading
    expect(screen.getByTestId('brief-empty')).toBeInTheDocument();
    expect(screen.queryByTestId('brief-loading')).toBeNull();
    // 未发起 GET 轮询
    const getCalls = store.fetch.mock.calls.filter(
      (c) => (c[1] as RequestInit | undefined)?.method !== 'POST' && /\/ai-briefs\/\d+$/.test(String(c[0])),
    );
    expect(getCalls).toHaveLength(0);
  });

  it('briefType=4 每日推荐：标的 ID 可留空（后端 subjectId 可空）仍受理', async () => {
    const store = makeStore({ getSequence: [PENDING_VIEW, DONE_VIEW] });
    vi.stubGlobal('fetch', store.fetch);
    const user = userEvent.setup();
    render(<AiBrief pollIntervalMs={50} />);

    // 切到每日推荐，不填标的 ID
    await user.click(screen.getByTestId('brief-type-4'));
    await user.click(screen.getByTestId('brief-trigger-submit'));

    // 无校验错误，且 POST body.subjectId=null
    await screen.findByTestId('brief-content');
    const postCall = store.fetch.mock.calls.find(
      (c) => (c[1] as RequestInit).method === 'POST' && /\/ai-briefs$/.test(String(c[0])),
    );
    expect(postCall).toBeDefined();
    const body = JSON.parse((postCall![1] as RequestInit).body as string) as {
      subjectId: number | null;
      briefType: number;
    };
    expect(body.briefType).toBe(4);
    expect(body.subjectId).toBeNull();
    // 未出现校验错误
    expect(screen.queryByTestId('brief-validation-error')).toBeNull();
  });
});
