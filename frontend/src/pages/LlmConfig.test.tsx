import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LlmConfig } from '@/pages/LlmConfig';
import type { LlmConfigView } from '@/types/llmConfig';

// —— fetch mock：llm-config 组（GET/PATCH/PUT/api-key/connectivity-test，状态化可变异） ——

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

const LIVE = 'LIVE' as const;
const RESTART = 'RESTART' as const;

function fullView(todayUsedTokens = 8400): LlmConfigView {
  return {
    global: {
      timeoutSeconds: 30,
      retry: 1,
      dailyTokenBudgetPerUser: 20000,
      budgetWarnRatio: 0.8,
      cacheDefaultTtlSeconds: 3600,
      cacheTtlSeconds: { 'brief-type-1': 3600, 'brief-type-3': 3600, 'brief-type-4': 86400 },
      cacheMaximumSize: 1000,
      todayUsedTokens,
      updatedAt: '2026-09-22T01:00:00Z',
      effectiveModes: {
        timeoutSeconds: LIVE,
        retry: LIVE,
        dailyTokenBudgetPerUser: LIVE,
        budgetWarnRatio: LIVE,
        cacheDefaultTtlSeconds: LIVE,
        cacheTtlSeconds: LIVE,
        cacheMaximumSize: RESTART,
      },
    },
    providers: [
      {
        name: 'deepseek',
        model: 'deepseek-flash',
        enabled: true,
        isDefault: true,
        fallback: 'glm',
        baseUrl: 'https://api.deepseek.com',
        baseUrlEffective: 'RESTART',
        inputPricePerMillion: 1.0,
        outputPricePerMillion: 4.0,
        apiKey: { status: 'CONFIGURED', source: 'ENV', last4: 'abcd' },
        updatedAt: '2026-09-22T01:00:00Z',
        effectiveModes: {
          model: LIVE,
          enabled: LIVE,
          isDefault: LIVE,
          fallback: LIVE,
          inputPricePerMillion: LIVE,
          outputPricePerMillion: LIVE,
          baseUrl: RESTART,
          apiKey: LIVE,
        },
      },
      {
        name: 'glm',
        model: 'glm-4-flash-250414',
        enabled: true,
        isDefault: false,
        fallback: 'deepseek',
        baseUrl: 'https://open.bigmodel.cn/api/paas/v4',
        baseUrlEffective: 'RESTART',
        inputPricePerMillion: 0,
        outputPricePerMillion: 0,
        apiKey: { status: 'NOT_SET', source: null, last4: null },
        updatedAt: '2026-09-22T01:00:00Z',
        effectiveModes: { model: LIVE, enabled: LIVE, baseUrl: RESTART, apiKey: LIVE },
      },
      {
        name: 'qwen',
        model: 'qwen-plus',
        enabled: false,
        isDefault: false,
        fallback: 'deepseek',
        baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
        baseUrlEffective: 'RESTART',
        inputPricePerMillion: 0,
        outputPricePerMillion: 0,
        apiKey: { status: 'NOT_SET', source: null, last4: null },
        updatedAt: '2026-09-22T01:00:00Z',
        effectiveModes: { model: LIVE, enabled: LIVE, baseUrl: RESTART, apiKey: LIVE },
      },
      {
        name: 'kimi',
        model: 'moonshot-v1-8k',
        enabled: false,
        isDefault: false,
        fallback: 'deepseek',
        baseUrl: 'https://api.moonshot.cn/v1',
        baseUrlEffective: 'RESTART',
        inputPricePerMillion: 0,
        outputPricePerMillion: 0,
        apiKey: { status: 'NOT_SET', source: null, last4: null },
        updatedAt: '2026-09-22T01:00:00Z',
        effectiveModes: { model: LIVE, enabled: LIVE, baseUrl: RESTART, apiKey: LIVE },
      },
    ],
    apiKeyWriteEnabled: true,
  };
}

interface StoreOpts {
  view?: LlmConfigView;
  conflictOnGlobalPatch?: boolean;
  failFirstGet?: boolean;
}

/** 状态化 fetch mock：写操作真实变更内存视图（供设默认互斥/key 回显等断言）。 */
function makeStore(opts: StoreOpts = {}) {
  const state = { view: structuredClone(opts.view ?? fullView()) };
  let getCalls = 0;
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const path = String(url);
    const method = init?.method ?? 'GET';
    const body = init?.body ? (JSON.parse(String(init.body)) as Record<string, unknown>) : {};
    const providerMatch = path.match(/\/llm-config\/providers\/([\w-]+)(\/api-key|\/connectivity-test)?$/);

    if (method === 'GET' && /\/llm-config$/.test(path)) {
      getCalls++;
      if (opts.failFirstGet && getCalls === 1) return fail(500, 50000, '服务异常');
      // 每次返回新克隆：模拟网络响应的独立对象，避免与组件持有引用共享可变态
      return ok(structuredClone(state.view));
    }
    if (method === 'PATCH' && path.endsWith('/llm-config/global')) {
      if (opts.conflictOnGlobalPatch) return fail(409, 30065, '配置已被并发修改，请刷新后重试');
      Object.assign(state.view.global, body);
      state.view.global.updatedAt = '2026-09-22T02:00:00Z';
      return ok(structuredClone(state.view.global));
    }
    if (providerMatch && method === 'PUT' && providerMatch[2] === '/api-key') {
      const provider = state.view.providers.find((p) => p.name === providerMatch[1])!;
      provider.apiKey = {
        status: 'CONFIGURED',
        source: 'DB',
        last4: String(body.apiKey).slice(-4),
      };
      provider.updatedAt = '2026-09-22T02:00:00Z';
      return ok(structuredClone(provider));
    }
    if (providerMatch && method === 'PUT') {
      const provider = state.view.providers.find((p) => p.name === providerMatch[1])!;
      Object.assign(provider, body);
      if (body.isDefault === true) {
        for (const p of state.view.providers) {
          if (p.name !== provider.name) p.isDefault = false;
        }
      }
      provider.updatedAt = '2026-09-22T02:00:00Z';
      return ok(structuredClone(provider));
    }
    if (providerMatch && method === 'POST' && providerMatch[2] === '/connectivity-test') {
      return ok({ ok: true, latencyMillis: 812, model: 'deepseek-flash', error: null });
    }
    return fail(500, 50000, `未模拟的请求: ${method} ${path}`);
  });
  return { fetch, state };
}

function lastCall(fetchMock: ReturnType<typeof makeStore>['fetch'], method: string) {
  const call = fetchMock.mock.calls
    .filter(([url, init]) => (init?.method ?? 'GET') === method && String(url).includes('/llm-config'))
    .at(-1);
  return call
    ? { url: String(call[0]), body: call[1]?.body ? JSON.parse(String(call[1].body)) : {} }
    : null;
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  localStorage.clear();
  window.location.hash = '';
});

describe('LlmConfig 模型配置页（T39）', () => {
  it('主路径：全局卡 + 4 provider 卡渲染，key 脱敏、默认徽章、EffectBadge 按 effectiveMode', async () => {
    vi.stubGlobal('fetch', makeStore().fetch);
    render(<LlmConfig />);

    // 全局参数：字段值来自接口，缓存上限只读 + 重启生效徽章（缓存上限为 RESTART）
    expect(await screen.findByTestId('llm-config-global-card')).toBeInTheDocument();
    expect(screen.getByTestId('llm-config-budget-input')).toHaveValue('20000');
    expect(screen.getByTestId('llm-config-ttl-brief-type-4')).toHaveValue('86400');
    expect(screen.getByTestId('llm-config-timeout-input')).toHaveValue('30');
    expect(screen.getAllByTestId('effect-badge-restart').length).toBeGreaterThanOrEqual(2);

    // provider：deepseek 默认徽章 + key 脱敏（ENV 尾 4 位）；glm 未配置
    expect(screen.getByTestId('llm-config-default-badge-deepseek')).toBeInTheDocument();
    expect(screen.getByTestId('llm-config-key-badge-deepseek')).toHaveTextContent('****abcd');
    expect(screen.getByTestId('llm-config-key-badge-glm')).toHaveTextContent('未配置');
    expect(screen.getByTestId('llm-config-provider-qwen')).toBeInTheDocument();
    // 停用 provider 连通性测试置灰（qwen/kimi 停用）
    expect(screen.getByTestId('llm-config-connect-qwen')).toBeDisabled();
  });

  it('保存全局参数：仅提交变更字段 + expectedUpdatedAt，反馈条枚举即时生效范围', async () => {
    const store = makeStore();
    vi.stubGlobal('fetch', store.fetch);
    render(<LlmConfig />);
    await screen.findByTestId('llm-config-global-card');

    const user = userEvent.setup();
    await user.clear(screen.getByTestId('llm-config-budget-input'));
    await user.type(screen.getByTestId('llm-config-budget-input'), '30000');
    await user.click(screen.getByTestId('llm-config-global-save'));

    const call = lastCall(store.fetch, 'PATCH');
    expect(call?.url).toContain('/llm-config/global');
    expect(call?.body).toEqual({
      dailyTokenBudgetPerUser: 30000,
      expectedUpdatedAt: '2026-09-22T01:00:00Z',
    });
    const feedback = await screen.findByTestId('llm-config-global-feedback');
    expect(feedback).toHaveTextContent('已保存');
    expect(feedback).toHaveTextContent('即时生效：日预算');
  });

  it('边界校验：告警阈值越界不提交，字段级报错且原值保留', async () => {
    const store = makeStore();
    vi.stubGlobal('fetch', store.fetch);
    render(<LlmConfig />);
    await screen.findByTestId('llm-config-global-card');

    const user = userEvent.setup();
    await user.clear(screen.getByTestId('llm-config-ratio-input'));
    await user.type(screen.getByTestId('llm-config-ratio-input'), '1.5');
    await user.click(screen.getByTestId('llm-config-global-save'));

    expect(await screen.findByText('告警阈值取值 (0,1]，如 0.8')).toBeInTheDocument();
    expect(screen.getByTestId('llm-config-ratio-input')).toHaveValue('1.5'); // 原值保留
    expect(lastCall(store.fetch, 'PATCH')).toBeNull(); // 提交前拦截不发请求
  });

  it('预算二次确认：新预算低于今日已用先弹 Dialog，确认后才提交', async () => {
    const store = makeStore(); // todayUsedTokens=8400
    vi.stubGlobal('fetch', store.fetch);
    render(<LlmConfig />);
    await screen.findByTestId('llm-config-global-card');

    const user = userEvent.setup();
    await user.clear(screen.getByTestId('llm-config-budget-input'));
    await user.type(screen.getByTestId('llm-config-budget-input'), '5000');
    await user.click(screen.getByTestId('llm-config-global-save'));

    expect(await screen.findByTestId('dialog')).toHaveTextContent('低于今日已用');
    expect(lastCall(store.fetch, 'PATCH')).toBeNull();

    await user.click(screen.getByTestId('llm-config-budget-confirm'));
    const call = await waitFor(() => lastCall(store.fetch, 'PATCH'));
    expect(call?.body.dailyTokenBudgetPerUser).toBe(5000);
  });

  it('provider 卡保存：改输入单价提交 PUT，反馈枚举即时生效', async () => {
    const store = makeStore();
    vi.stubGlobal('fetch', store.fetch);
    render(<LlmConfig />);
    await screen.findByTestId('llm-config-provider-deepseek');

    const user = userEvent.setup();
    await user.clear(screen.getByTestId('llm-config-input-price-deepseek'));
    await user.type(screen.getByTestId('llm-config-input-price-deepseek'), '2');
    await user.click(screen.getByTestId('llm-config-save-deepseek'));

    const call = lastCall(store.fetch, 'PUT');
    expect(call?.url).toContain('/llm-config/providers/deepseek');
    expect(call?.body.inputPricePerMillion).toBe(2);
    expect(call?.body.expectedUpdatedAt).toBe('2026-09-22T01:00:00Z');
    const feedback = await screen.findByTestId('llm-config-feedback-deepseek');
    expect(feedback).toHaveTextContent('即时生效：输入单价');
  });

  it('设为默认：PUT isDefault=true 后整页刷新，旧默认徽章互斥消失', async () => {
    const store = makeStore();
    vi.stubGlobal('fetch', store.fetch);
    render(<LlmConfig />);
    await screen.findByTestId('llm-config-set-default-glm');

    const user = userEvent.setup();
    await user.click(screen.getByTestId('llm-config-set-default-glm'));

    const call = await waitFor(() => lastCall(store.fetch, 'PUT'));
    expect(call?.body.isDefault).toBe(true);
    // 保存成功后重取全量：glm 成为默认，deepseek 徽章消失
    await waitFor(() => expect(screen.getByTestId('llm-config-default-badge-glm')).toBeInTheDocument());
    await waitFor(() => expect(screen.queryByTestId('llm-config-default-badge-deepseek')).toBeNull());
  });

  it('API key 录入：只写不回显，保存后仅展示脱敏态（DB 来源 + 尾 4 位）', async () => {
    const store = makeStore();
    vi.stubGlobal('fetch', store.fetch);
    render(<LlmConfig />);
    await screen.findByTestId('llm-config-key-button-glm');

    const user = userEvent.setup();
    await user.click(screen.getByTestId('llm-config-key-button-glm'));
    const input = screen.getByTestId('llm-config-key-input-glm');
    expect(input).toHaveAttribute('type', 'password');
    expect(input).toHaveAttribute('autocomplete', 'new-password');
    await user.type(input, 'sk-live-9876');
    await user.click(screen.getByTestId('llm-config-key-save-glm'));

    const call = await waitFor(() =>
      store.fetch.mock.calls
        .filter(([url, init]) => init?.method === 'PUT' && String(url).includes('/api-key'))
        .at(-1),
    );
    expect(JSON.parse(String((call?.[1] as RequestInit | undefined)?.body)).apiKey).toBe(
      'sk-live-9876',
    );
    // 保存成功回到脱敏态；明文永不回显
    expect(await screen.findByTestId('llm-config-key-badge-glm')).toHaveTextContent('****9876');
    expect(screen.queryByText(/sk-live-9876/)).toBeNull();
  });

  it('CONFIG_SECRET 未配置：key 录入按钮禁用并明示原因与配置方法', async () => {
    const view = fullView();
    view.apiKeyWriteEnabled = false;
    vi.stubGlobal('fetch', makeStore({ view }).fetch);
    render(<LlmConfig />);
    await screen.findByTestId('llm-config-provider-deepseek');

    expect(screen.getByTestId('llm-config-key-button-deepseek')).toBeDisabled();
    // 每 provider 卡均带降级提示（明示原因与配置方法）
    expect(screen.getAllByText(/未配置 CONFIG_SECRET/).length).toBe(4);
  });

  it('连通性测试：内联展示成功耗时（emerald 文案）', async () => {
    const store = makeStore();
    vi.stubGlobal('fetch', store.fetch);
    render(<LlmConfig />);
    await screen.findByTestId('llm-config-connect-deepseek');

    await userEvent.click(screen.getByTestId('llm-config-connect-deepseek'));

    const result = await screen.findByTestId('llm-config-connect-result-deepseek');
    expect(result).toHaveTextContent('连接成功 · 812ms');
  });

  it('并发冲突（30065）：反馈条展示后端错误文案，原值保留', async () => {
    vi.stubGlobal('fetch', makeStore({ conflictOnGlobalPatch: true }).fetch);
    render(<LlmConfig />);
    await screen.findByTestId('llm-config-global-card');

    const user = userEvent.setup();
    await user.clear(screen.getByTestId('llm-config-budget-input'));
    await user.type(screen.getByTestId('llm-config-budget-input'), '21000');
    await user.click(screen.getByTestId('llm-config-global-save'));

    const feedback = await screen.findByTestId('llm-config-global-feedback');
    expect(feedback).toHaveTextContent('配置已被并发修改');
    expect(screen.getByTestId('llm-config-budget-input')).toHaveValue('21000'); // 失败原值不变
  });

  it('异常路径：整页加载失败展示错误 + 重试恢复', async () => {
    const store = makeStore({ failFirstGet: true });
    vi.stubGlobal('fetch', store.fetch);
    render(<LlmConfig />);

    expect(await screen.findByTestId('llm-config-error')).toHaveTextContent('服务异常');
    await userEvent.click(screen.getByTestId('llm-config-retry'));
    expect(await screen.findByTestId('llm-config-global-card')).toBeInTheDocument();
  });

  it('停用默认 provider：需二次确认后才提交 enabled=false', async () => {
    const store = makeStore();
    vi.stubGlobal('fetch', store.fetch);
    render(<LlmConfig />);
    await screen.findByTestId('llm-config-enabled-deepseek');

    await userEvent.click(screen.getByTestId('llm-config-enabled-deepseek'));

    expect(await screen.findByTestId('dialog')).toHaveTextContent('停用默认 provider');
    expect(lastCall(store.fetch, 'PUT')).toBeNull();
    await userEvent.click(screen.getByTestId('llm-config-disable-confirm-deepseek'));

    const call = await waitFor(() => lastCall(store.fetch, 'PUT'));
    expect(call?.body.enabled).toBe(false);
  });
});
