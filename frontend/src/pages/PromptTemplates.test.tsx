import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PromptTemplates } from '@/pages/PromptTemplates';
import type { PromptListView } from '@/types/promptTemplate';

// —— fetch mock：prompt 组（列表 / 详情 / 注册表；状态化可变异，写操作由 T48 用例增补） ——

const ok = (data: unknown) => ({
  ok: true,
  status: 200,
  json: async () => ({ code: 0, msg: 'ok', data, traceId: 't' }),
});
const fail = (status: number, code: number, msg: string, data: unknown = null) => ({
  ok: false,
  status,
  json: async () => ({ code, msg, data, traceId: 't' }),
});

const TEMPLATE_V1_1 =
  '---SYSTEM---\n你是金融信息分析师，基于数据生成结构化 json 简报。\n---USER---\n标的：{{subjectName}}({{subjectCode}}) 行业：{{industry}}，输出形状 {summary, risks}';

function detailOf(id: number, briefType: number, version: string, status: 'ACTIVE' | 'RETIRED') {
  return {
    id,
    briefType,
    name: '个股简报',
    version,
    status,
    template: TEMPLATE_V1_1,
    sections: { system: '你是金融信息分析师，基于数据生成结构化 json 简报。', user: '标的：{{subjectName}}({{subjectCode}}) 行业：{{industry}}，输出形状 {summary, risks}' },
    placeholders: ['subjectName', 'subjectCode', 'industry'],
  };
}

const REGISTRY_1 = {
  briefType: 1,
  name: '个股简报',
  dormant: false,
  placeholders: [
    { key: 'subjectName', description: '标的名称' },
    { key: 'subjectCode', description: '标的代码' },
    { key: 'industry', description: '所属行业' },
  ],
};

const REGISTRY_2 = {
  briefType: 2,
  name: '事件归因',
  dormant: true,
  note: '该场景当前无生产触发入口；事件类占位符（eventTitle 等）暂无上下文来源，使用时将原样发给模型',
  placeholders: [
    { key: 'subjectName', description: '标的名称' },
    { key: 'eventTitle', description: '事件标题' },
  ],
};

function fullList(): PromptListView {
  return {
    groups: [
      {
        briefType: 1,
        name: '个股简报',
        activeVersionId: 3,
        activeCount: 1,
        versions: [
          { id: 3, version: 'v1.1', status: 'ACTIVE', placeholderCount: 3, createdAt: '2026-09-22T01:00:00Z', updatedAt: '2026-09-22T10:12:00Z' },
          { id: 1, version: 'v1.0', status: 'RETIRED', placeholderCount: 3, createdAt: '2026-09-21T00:00:00Z', updatedAt: '2026-09-21T08:00:00Z' },
        ],
      },
      {
        briefType: 2,
        name: '事件归因',
        activeVersionId: 2,
        activeCount: 1,
        versions: [
          { id: 2, version: 'v1.0', status: 'ACTIVE', placeholderCount: 7, createdAt: '2026-09-21T00:00:00Z', updatedAt: '2026-09-21T00:00:00Z' },
        ],
      },
      {
        briefType: 3,
        name: '政策解读',
        activeVersionId: 4,
        activeCount: 1,
        versions: [
          { id: 4, version: 'v1.0', status: 'ACTIVE', placeholderCount: 7, createdAt: '2026-09-21T00:00:00Z', updatedAt: '2026-09-21T00:00:00Z' },
        ],
      },
      {
        briefType: 4,
        name: '每日推荐',
        activeVersionId: 8,
        activeCount: 1,
        versions: [
          { id: 9, version: 'v1.1', status: 'RETIRED', placeholderCount: 6, createdAt: '2026-09-21T00:00:00Z', updatedAt: '2026-09-22T00:00:00Z' },
          { id: 8, version: 'v1.0', status: 'ACTIVE', placeholderCount: 6, createdAt: '2026-09-20T00:00:00Z', updatedAt: '2026-09-21T00:00:00Z' },
        ],
      },
    ],
  };
}

interface StoreOpts {
  list?: PromptListView;
  failFirstList?: boolean;
  failDetail?: boolean;
  failPlaceholdersOnce?: boolean;
  /** 首次 create 强制 409/30068（前端预检未判出移除时的后端兜底路径）。 */
  force30068Once?: { removed: Array<{ key: string; description: string | null }>; unknown: Array<{ key: string; description: null }> };
  /** create 一律 409/30070（版本冲突）。 */
  conflictOnCreate?: boolean;
  /** delete 一律 409/30069（激活守卫兜底）。 */
  deleteGuard?: boolean;
}

/** 模板中的占位符键（渲染正则同口径提取，去重）。 */
function keysOf(text: string): string[] {
  return [...new Set([...text.matchAll(/\{\{\s*(\w+)\s*\}\}/g)].map((m) => m[1]))];
}

/** 镜像后端版本号生成（MINOR/MAJOR，两段式取 max）。 */
function bumpVersion(versions: string[], strategy: string): string {
  const parsed = versions
    .map((v) => {
      const m = /^v(\d+)\.(\d+)$/.exec(v);
      return m ? [Number(m[1]), Number(m[2])] : null;
    })
    .filter((p): p is number[] => p !== null);
  if (parsed.length === 0) return 'v1.0';
  const max = parsed.reduce((a, c) => (c[0] !== a[0] ? (c[0] > a[0] ? c : a) : c[1] > a[1] ? c : a));
  return strategy === 'MAJOR' ? `v${max[0] + 1}.0` : `v${max[0]}.${max[1] + 1}`;
}

/**
 * 状态化 fetch mock（沿 LlmConfig.test 范式）：create/activate/delete 真实变更内存列表，
 * 保存分级错误按后端契约模拟（30067 硬校验 / 30068 待确认清单 / 30069 守卫 / 30070 冲突）。
 */
function makeStore(opts: StoreOpts = {}) {
  const state = { list: structuredClone(opts.list ?? fullList()) };
  let listCalls = 0;
  let placeholderCalls = 0;
  let forced30068Used = false;
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    const path = String(url);
    const method = init?.method ?? 'GET';
    const body = init?.body ? (JSON.parse(String(init.body)) as Record<string, unknown>) : {};

    if (method === 'GET' && /\/prompt-templates$/.test(path)) {
      listCalls++;
      if (opts.failFirstList && listCalls === 1) return fail(500, 50000, '服务异常');
      return ok(structuredClone(state.list));
    }
    const detailMatch = path.match(/\/prompt-templates\/(\d+)$/);
    if (method === 'GET' && detailMatch) {
      if (opts.failDetail) return fail(404, 30066, '模板版本不存在');
      return ok(detailOf(Number(detailMatch[1]), 1, 'v1.1', 'ACTIVE'));
    }
    if (method === 'GET' && path.includes('/prompt-placeholders')) {
      placeholderCalls++;
      const briefType = Number(path.split('briefType=')[1]);
      if (opts.failPlaceholdersOnce && placeholderCalls === 1) {
        return fail(500, 50000, '服务异常');
      }
      const registry = briefType === 2 ? REGISTRY_2 : { ...REGISTRY_1, briefType };
      return ok({ scenarios: [structuredClone(registry)] });
    }
    if (method === 'POST' && /\/prompt-templates$/.test(path)) {
      const template = String(body.template ?? '');
      // 30067 硬校验（分段标记 + json 字样，文案对齐后端）
      const hard: string[] = [];
      const sysPresent = template.includes('---SYSTEM---');
      const userPresent = template.includes('---USER---');
      if (template.trim() === '') hard.push('模板不能为空');
      if (!sysPresent) hard.push('缺少 ---SYSTEM--- 分段标记');
      if (!userPresent) hard.push('缺少 ---USER--- 分段标记');
      if (sysPresent && userPresent) {
        const sys = template.slice(
          template.indexOf('---SYSTEM---') + '---SYSTEM---'.length,
          template.indexOf('---USER---'),
        );
        if (!sys.toLowerCase().includes('json')) {
          hard.push('system 段须含 json 字样（JSON 输出模式前提）');
        }
      }
      if (hard.length > 0) return fail(400, 30067, hard.join('; '));
      // 30068 待确认移除（diff 基准 = 底稿全文；confirmedRemovedKeys 须全覆盖）
      const registryKeys = REGISTRY_1.placeholders.map((p) => p.key);
      const nextKeys = keysOf(template);
      const removed = keysOf(TEMPLATE_V1_1).filter((k) => !nextKeys.includes(k));
      const unknown = nextKeys.filter((k) => !registryKeys.includes(k));
      const confirmed = (body.confirmedRemovedKeys as string[] | undefined) ?? [];
      if (opts.force30068Once && !forced30068Used) {
        forced30068Used = true;
        return fail(409, 30068, '存在待确认的占位符移除，请逐项确认后重试', {
          removed: opts.force30068Once.removed,
          unknown: opts.force30068Once.unknown,
        });
      }
      if (removed.some((k) => !confirmed.includes(k))) {
        return fail(409, 30068, '存在待确认的占位符移除，请逐项确认后重试', {
          removed: removed.map((k) => ({
            key: k,
            description: REGISTRY_1.placeholders.find((p) => p.key === k)?.description ?? null,
          })),
          unknown: unknown.map((k) => ({ key: k, description: null })),
        });
      }
      if (opts.conflictOnCreate) return fail(409, 30070, '版本号冲突，请刷新列表后重试');
      // 201：事务——旧激活置废 + 新版本插入并激活
      const briefType = Number(body.briefType ?? 1);
      const group = state.list.groups.find((g) => g.briefType === briefType)!;
      const previousActive = group.versions.find((v) => v.status === 'ACTIVE') ?? null;
      const newId = Math.max(...state.list.groups.flatMap((g) => g.versions.map((v) => v.id))) + 1;
      const newVersion = bumpVersion(
        group.versions.map((v) => v.version),
        String(body.versionStrategy ?? 'MINOR'),
      );
      for (const v of group.versions) v.status = 'RETIRED';
      group.versions.unshift({
        id: newId,
        version: newVersion,
        status: 'ACTIVE',
        placeholderCount: keysOf(template).length,
        createdAt: '2026-09-22T02:00:00Z',
        updatedAt: '2026-09-22T02:00:00Z',
      });
      group.activeVersionId = newId;
      group.activeCount = 1;
      return {
        ok: true,
        status: 201,
        json: async () => ({
          code: 0,
          msg: 'ok',
          traceId: 't',
          data: {
            id: newId,
            briefType,
            version: newVersion,
            status: 'ACTIVE',
            deactivatedVersion: previousActive ? previousActive.version : null,
            placeholderCount: keysOf(template).length,
            warnings: unknown.map((k) => `占位符 ${k} 无上下文来源，渲染时将原样发给模型`),
          },
        }),
      };
    }
    const activateMatch = path.match(/\/prompt-templates\/(\d+)\/activate$/);
    if (method === 'POST' && activateMatch) {
      const id = Number(activateMatch[1]);
      const group = state.list.groups.find((g) => g.versions.some((v) => v.id === id))!;
      const target = group.versions.find((v) => v.id === id)!;
      const previousActive = group.versions.find((v) => v.status === 'ACTIVE') ?? null;
      for (const v of group.versions) v.status = 'RETIRED';
      target.status = 'ACTIVE';
      group.activeVersionId = id;
      group.activeCount = 1;
      return ok({
        id,
        briefType: group.briefType,
        version: target.version,
        status: 'ACTIVE',
        deactivatedVersion: previousActive && previousActive.id !== id ? previousActive.version : null,
      });
    }
    const deleteMatch = path.match(/\/prompt-templates\/(\d+)$/);
    if (method === 'DELETE' && deleteMatch) {
      const id = Number(deleteMatch[1]);
      const group = state.list.groups.find((g) => g.versions.some((v) => v.id === id))!;
      const target = group.versions.find((v) => v.id === id)!;
      if (opts.deleteGuard || target.status === 'ACTIVE') {
        return fail(409, 30069, '激活版本不可删除，请先切换激活到其他版本');
      }
      group.versions = group.versions.filter((v) => v.id !== id);
      return ok({ id, version: target.version, deleted: true });
    }
    return fail(500, 50000, `未模拟的请求: ${method} ${path}`);
  });
  return { fetchMock, state };
}

/** 取最近一次指定方法的 prompt 请求体。 */
function lastCall(
  fetchMock: ReturnType<typeof makeStore>['fetchMock'],
  method: string,
  pathPart = '/prompt-templates',
) {
  const call = fetchMock.mock.calls
    .filter(([url, init]) => (init?.method ?? 'GET') === method && String(url).includes(pathPart))
    .at(-1);
  return call
    ? { url: String(call[0]), body: call[1]?.body ? JSON.parse(String(call[1].body)) : {} }
    : null;
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  localStorage.clear();
  sessionStorage.clear();
  window.location.hash = '';
});

describe('PromptTemplates 提示词模板页（T47）', () => {
  it('主路径：4 场景分区 + 版本卡渲染，激活「使用中」/置废徽章/场景头「当前使用」/元信息', async () => {
    vi.stubGlobal('fetch', makeStore().fetchMock);
    render(<PromptTemplates />);

    expect(await screen.findByTestId('prompt-scene-1')).toHaveTextContent('个股简报');
    expect(screen.getByTestId('prompt-scene-2')).toHaveTextContent('事件归因');
    expect(screen.getByTestId('prompt-scene-3')).toHaveTextContent('政策解读');
    expect(screen.getByTestId('prompt-scene-4')).toHaveTextContent('每日推荐');

    // 场景 1：激活 v1.1 使用中徽章 + 置废 v1.0；场景头「当前使用 v1.1」
    expect(screen.getByTestId('prompt-card-1-v1.1')).toHaveTextContent('使用中');
    expect(screen.getByTestId('prompt-card-1-v1.0')).toHaveTextContent('已置废');
    expect(screen.getByTestId('prompt-scene-1')).toHaveTextContent('当前使用 v1.1');
    // 元信息：更新时间 MM-dd HH:mm（本地时区动态计算，避免环境时差）+ 占位符 N 项
    const updated = new Date('2026-09-22T10:12:00Z');
    const p = (n: number) => String(n).padStart(2, '0');
    const localTime = `${p(updated.getMonth() + 1)}-${p(updated.getDate())} ${p(updated.getHours())}:${p(updated.getMinutes())}`;
    expect(screen.getByTestId('prompt-card-1-v1.1')).toHaveTextContent(localTime);
    expect(screen.getByTestId('prompt-card-1-v1.1')).toHaveTextContent('占位符 3 项');
  });

  it('版本详情内联展开：SYSTEM/USER 分段 + 占位符着色 + 场景头注册数懒加载；再点收起', async () => {
    vi.stubGlobal('fetch', makeStore().fetchMock);
    render(<PromptTemplates />);
    await screen.findByTestId('prompt-card-1-v1.1');

    const user = userEvent.setup();
    await user.click(screen.getByTestId('prompt-detail-toggle-1-v1.1'));

    const detail = await screen.findByTestId('prompt-detail-1-v1.1');
    expect(detail).toHaveTextContent('SYSTEM');
    expect(detail).toHaveTextContent('USER');
    expect(detail).toHaveTextContent('你是金融信息分析师');
    expect(detail).toHaveTextContent('{{subjectName}}');
    // 注册表懒加载后场景头出现注册数
    await waitFor(() =>
      expect(screen.getByTestId('prompt-scene-1')).toHaveTextContent('占位符注册 3 项'),
    );
    // 占位符整体着色渲染（sky/amber 分类不参与 DOM 断言，断言命中段可见）
    expect(within(detail).getByText('{{subjectName}}')).toBeInTheDocument();

    // 收起
    await user.click(screen.getByTestId('prompt-detail-toggle-1-v1.1'));
    expect(screen.queryByTestId('prompt-detail-1-v1.1')).toBeNull();
  });

  it('激活卡置顶排序：回滚态（激活 v1.0 + 置废 v1.1）激活卡排在数值更高的置废卡之前', async () => {
    vi.stubGlobal('fetch', makeStore().fetchMock);
    render(<PromptTemplates />);
    const scene4 = await screen.findByTestId('prompt-scene-4');

    const cards = within(scene4).getAllByTestId(/^prompt-card-4-/);
    expect(cards[0]).toHaveAttribute('data-testid', 'prompt-card-4-v1.0');
    expect(cards[0]).toHaveTextContent('使用中');
    expect(cards[1]).toHaveAttribute('data-testid', 'prompt-card-4-v1.1');
    expect(cards[1]).toHaveTextContent('已置废');
  });

  it('activeCount=0 异常空态：rose 警示条不静默（场景无启用模板）', async () => {
    const list = fullList();
    list.groups[1] = {
      briefType: 2,
      name: '事件归因',
      activeVersionId: null,
      activeCount: 0,
      versions: [
        { id: 2, version: 'v1.0', status: 'RETIRED', placeholderCount: 7, createdAt: '2026-09-21T00:00:00Z', updatedAt: '2026-09-21T00:00:00Z' },
      ],
    };
    vi.stubGlobal('fetch', makeStore({ list }).fetchMock);
    render(<PromptTemplates />);

    expect(await screen.findByTestId('prompt-scene-warning-2')).toHaveTextContent(
      '该场景无启用模板，AI 生成将失败',
    );
    expect(screen.queryByTestId('prompt-scene-warning-1')).toBeNull();
  });

  it('activeCount>=2 双激活警示（手工改库迹象）', async () => {
    const list = fullList();
    list.groups[2].activeCount = 2;
    vi.stubGlobal('fetch', makeStore({ list }).fetchMock);
    render(<PromptTemplates />);

    expect(await screen.findByTestId('prompt-scene-warning-3')).toHaveTextContent(
      '检测到多个启用版本，生成将取其一，请重新切换激活修复',
    );
  });

  it('单场景完全无版本：muted 占位 + 警示条；4 场景全空走整页数据异常态（分两档验证）', async () => {
    const list = fullList();
    list.groups[1] = { briefType: 2, name: '事件归因', activeVersionId: null, activeCount: 0, versions: [] };
    vi.stubGlobal('fetch', makeStore({ list }).fetchMock);
    render(<PromptTemplates />);

    const scene2 = await screen.findByTestId('prompt-scene-2');
    expect(scene2).toHaveTextContent('该场景暂无模板版本（播种数据应保证至少 1 版，此态属数据异常）');
    expect(screen.getByTestId('prompt-scene-warning-2')).toHaveTextContent('该场景无启用模板');

    // 4 场景全空 → 整页异常态（重试可见，不再渲染分区）
    const allEmpty = fullList();
    for (const group of allEmpty.groups) {
      group.versions = [];
      group.activeCount = 0;
      group.activeVersionId = null;
    }
    cleanup();
    vi.stubGlobal('fetch', makeStore({ list: allEmpty }).fetchMock);
    render(<PromptTemplates />);
    expect(await screen.findByTestId('prompt-templates-error')).toHaveTextContent('4 个场景均无模板版本');
    expect(screen.queryByTestId('prompt-scene-1')).toBeNull();
  });

  it('异常路径：整页加载失败展示错误 + 重试恢复', async () => {
    vi.stubGlobal('fetch', makeStore({ failFirstList: true }).fetchMock);
    render(<PromptTemplates />);

    expect(await screen.findByTestId('prompt-templates-error')).toHaveTextContent('服务异常');
    await userEvent.click(screen.getByTestId('prompt-templates-retry'));
    expect(await screen.findByTestId('prompt-scene-1')).toBeInTheDocument();
  });

  it('加载骨架态：4 分区对位骨架（分区头 + 版本卡）', async () => {
    vi.stubGlobal('fetch', makeStore().fetchMock);
    render(<PromptTemplates />);
    expect(screen.getByTestId('prompt-templates-loading')).toBeInTheDocument();
    await screen.findByTestId('prompt-scene-1');
    expect(screen.queryByTestId('prompt-templates-loading')).toBeNull();
  });

  it('详情单查失败（404/30066）：展开区错误提示，页面不崩', async () => {
    vi.stubGlobal('fetch', makeStore({ failDetail: true }).fetchMock);
    render(<PromptTemplates />);
    await screen.findByTestId('prompt-card-1-v1.1');

    await userEvent.click(screen.getByTestId('prompt-detail-toggle-1-v1.1'));

    const detail = await screen.findByTestId('prompt-detail-1-v1.1');
    expect(within(detail).getByRole('alert')).toHaveTextContent('模板版本不存在');
  });
});

describe('PromptTemplates 编辑器与版本操作（T48）', () => {
  /** 打开场景 1 激活版（id 3 / v1.1）编辑器并等底稿预填。 */
  async function openScene1Editor(store: ReturnType<typeof makeStore>['fetchMock']) {
    vi.stubGlobal('fetch', store);
    render(<PromptTemplates />);
    await screen.findByTestId('prompt-card-1-v1.1');
    await userEvent.click(screen.getByTestId('prompt-edit-1-v1.1'));
    return await screen.findByTestId('prompt-editor-textarea');
  }

  it('编辑流转主路径：底稿预填 → 修改 → 保存即新版本并激活 → 反馈枚举 → 返回列表见新卡置顶', async () => {
    const store = makeStore();
    const textarea = await openScene1Editor(store.fetchMock);
    const user = userEvent.setup();

    expect(screen.getByTestId('prompt-editor-title')).toHaveTextContent('编辑 · 个股简报（基于 v1.1）');
    expect(textarea).toHaveValue(TEMPLATE_V1_1);
    // 版本策略预览号（场景 1 最大 v1.1 → 次版本 v1.2 / 主版本 v2.0）
    expect(screen.getByTestId('prompt-strategy-minor')).toHaveTextContent('次版本 v1.2');
    expect(screen.getByTestId('prompt-strategy-major')).toHaveTextContent('主版本 v2.0');
    // 未变更防误存
    expect(screen.getByTestId('prompt-editor-save')).toBeDisabled();

    await user.type(textarea, '{moveToEnd}追加一行结论。');
    expect(screen.getByTestId('prompt-editor-save')).toBeEnabled();
    await user.click(screen.getByTestId('prompt-editor-save'));

    const call = await waitFor(() => lastCall(store.fetchMock, 'POST'));
    expect(call?.url).toContain('/prompt-templates');
    expect(call?.body).toMatchObject({ briefType: 1, baseVersionId: 3, versionStrategy: 'MINOR' });
    expect(call?.body.template).toBe(`${TEMPLATE_V1_1}追加一行结论。`);
    expect(call?.body.confirmedRemovedKeys).toEqual([]);

    const feedback = await screen.findByTestId('prompt-editor-feedback');
    expect(feedback).toHaveTextContent('已保存 · 即时生效：个股简报 v1.2 已保存并激活');
    expect(feedback).toHaveTextContent('下一次生成即用新版本（旧缓存自动失效）');

    // 保存后 dirty 清零：直接返回列表（无确认弹窗），新卡置顶激活、原版置废
    await user.click(screen.getByTestId('prompt-editor-back'));
    expect(await screen.findByTestId('prompt-card-1-v1.2')).toHaveTextContent('使用中');
    expect(screen.getByTestId('prompt-card-1-v1.1')).toHaveTextContent('已置废');
  });

  it('硬校验一：删除 ---USER--- 标记 → 面板 rose 逐条且保存禁用（不发请求）', async () => {
    const store = makeStore();
    const textarea = await openScene1Editor(store.fetchMock);
    const user = userEvent.setup();

    await user.clear(textarea);
    await user.type(textarea, '---SYSTEM---{enter}你是分析师，输出 json。{enter}（用户段被误删）');

    expect(await screen.findByText('缺少 ---USER--- 分段标记', {}, { timeout: 2000 })).toBeInTheDocument();
    expect(screen.getByTestId('prompt-editor-save')).toBeDisabled();
    expect(lastCall(store.fetchMock, 'POST')).toBeNull(); // 提交前拦截不发请求
  });

  it('硬校验二：system 段不含 json 字样 → 面板 rose 且保存禁用；后端 400/30067 文案映射反馈条', async () => {
    const store = makeStore();
    const textarea = await openScene1Editor(store.fetchMock);
    const user = userEvent.setup();

    await user.clear(textarea);
    await user.type(textarea, '---SYSTEM---{enter}你是金融信息分析师。{enter}---USER---{enter}标的：{{{{subjectName}}');

    expect(await screen.findByText('system 段须含 json 字样（JSON 输出模式前提）', {}, { timeout: 2000 })).toBeInTheDocument();
    expect(screen.getByTestId('prompt-editor-save')).toBeDisabled();
  });

  it('移除占位符确认放行：面板提示 → Dialog 逐项勾选 → 未勾禁用 → 全勾重提交带 confirmedRemovedKeys', async () => {
    const store = makeStore();
    const textarea = await openScene1Editor(store.fetchMock);
    const user = userEvent.setup();

    // 移除 {{subjectCode}}（底稿 3 键保留其余 2 键）
    await user.clear(textarea);
    await user.type(
      textarea,
      '---SYSTEM---{enter}你是金融信息分析师，基于数据生成结构化 json 简报。{enter}---USER---{enter}标的：{{{{subjectName}} 行业：{{{{industry}}',
    );

    expect(
      await screen.findByText('移除占位符 1 项（保存时需确认）：{{subjectCode}}', {}, { timeout: 2000 }),
    ).toBeInTheDocument();
    await user.click(screen.getByTestId('prompt-editor-save'));

    // 保存确认 Dialog：勾选前「仍要保存」禁用
    expect(await screen.findByTestId('dialog')).toHaveTextContent('保存确认 · 个股简报');
    expect(screen.getByTestId('prompt-save-confirm')).toBeDisabled();
    expect(lastCall(store.fetchMock, 'POST')).toBeNull();

    // 勾选后放行：单次请求直接携带确认数组
    await user.click(screen.getByTestId('prompt-confirm-removed-subjectCode'));
    expect(screen.getByTestId('prompt-save-confirm')).toBeEnabled();
    expect(screen.getByTestId('dialog')).toHaveTextContent('确认移除 {{subjectCode}} —— 标的代码');
    await user.click(screen.getByTestId('prompt-save-confirm'));

    const call = await waitFor(() => lastCall(store.fetchMock, 'POST'));
    expect(call?.body.confirmedRemovedKeys).toEqual(['subjectCode']);
    expect(await screen.findByTestId('prompt-editor-feedback')).toHaveTextContent('个股简报 v1.2 已保存并激活');
  });

  it('仅未知占位符：面板与对照栏 amber 提醒，不弹 Dialog 直接保存', async () => {
    const store = makeStore();
    const textarea = await openScene1Editor(store.fetchMock);
    const user = userEvent.setup();

    await user.type(textarea, '{moveToEnd}额外注入：{{{{foo}}');
    expect(
      await screen.findByText('新增未知占位符 1 项（可保存，警告）：{{foo}}', {}, { timeout: 2000 }),
    ).toBeInTheDocument();
    // 对照栏底部未注册清单同步可见（编辑期即可见，不用等保存）
    expect(await screen.findByTestId('prompt-ph-unregistered')).toHaveTextContent('{{foo}}');

    await user.click(screen.getByTestId('prompt-editor-save'));
    expect(await screen.findByTestId('prompt-editor-feedback')).toHaveTextContent('已保存');
    expect(screen.queryByTestId('dialog')).toBeNull(); // 仅未知不弹层
  });

  it('后端 30068 兜底：前端未判出移除时按服务端清单弹 Dialog，勾选后带确认数组重提交', async () => {
    const store = makeStore({
      force30068Once: { removed: [{ key: 'industry', description: '所属行业' }], unknown: [] },
    });
    const textarea = await openScene1Editor(store.fetchMock);
    const user = userEvent.setup();

    // 纯措辞追加：前端 diff 无移除（不弹前端 Dialog），后端重算判出移除
    await user.type(textarea, '{moveToEnd}（措辞调整）');
    await user.click(screen.getByTestId('prompt-editor-save'));

    expect(await screen.findByTestId('dialog')).toHaveTextContent('确认移除 {{industry}} —— 所属行业');
    await user.click(screen.getByTestId('prompt-confirm-removed-industry'));
    await user.click(screen.getByTestId('prompt-save-confirm'));

    const call = await waitFor(() => lastCall(store.fetchMock, 'POST'));
    expect(call?.body.confirmedRemovedKeys).toEqual(['industry']);
    expect(await screen.findByTestId('prompt-editor-feedback')).toHaveTextContent('已保存');
  });

  it('版本策略切换主版本：POST versionStrategy=MAJOR → 落 v2.0 并激活', async () => {
    const store = makeStore();
    const textarea = await openScene1Editor(store.fetchMock);
    const user = userEvent.setup();

    await user.type(textarea, '{moveToEnd}大改一版。');
    await user.click(screen.getByTestId('prompt-strategy-major'));
    expect(screen.getByTestId('prompt-strategy')).toHaveTextContent('将保存为 v2.0 并激活');
    await user.click(screen.getByTestId('prompt-editor-save'));

    const call = await waitFor(() => lastCall(store.fetchMock, 'POST'));
    expect(call?.body.versionStrategy).toBe('MAJOR');
    expect(await screen.findByTestId('prompt-editor-feedback')).toHaveTextContent('个股简报 v2.0 已保存并激活');
  });

  it('版本冲突（30070）：反馈条提示已刷新请重试，正文保留 dirty', async () => {
    const store = makeStore({ conflictOnCreate: true });
    const textarea = await openScene1Editor(store.fetchMock);
    const user = userEvent.setup();

    await user.type(textarea, '{moveToEnd}冲突场景。');
    await user.click(screen.getByTestId('prompt-editor-save'));

    const feedback = await screen.findByTestId('prompt-editor-feedback');
    expect(feedback).toHaveTextContent('版本号冲突（可能列表已过期），已刷新，请重试');
    expect(textarea).toHaveValue(`${TEMPLATE_V1_1}冲突场景。`); // 失败正文保留
    // 冲突后自动重拉列表（预览号重算数据源更新）
    await waitFor(() =>
      expect(store.fetchMock.mock.calls.filter(([url, init]) => (init?.method ?? 'GET') === 'GET' && String(url).endsWith('/prompt-templates')).length).toBeGreaterThanOrEqual(2),
    );
  });

  it('激活切换：二次确认 Dialog → POST activate → 场景反馈条 + 列表重拉（原激活置废）', async () => {
    const store = makeStore();
    vi.stubGlobal('fetch', store.fetchMock);
    render(<PromptTemplates />);
    await screen.findByTestId('prompt-card-4-v1.1');
    const user = userEvent.setup();

    // 每日推荐：激活 v1.0、置废 v1.1 → 回滚切到 v1.1
    await user.click(screen.getByTestId('prompt-activate-4-v1.1'));
    expect(await screen.findByTestId('dialog')).toHaveTextContent('切换激活 · 每日推荐');
    expect(lastCall(store.fetchMock, 'POST', '/activate')).toBeNull();

    await user.click(screen.getByTestId('prompt-activate-confirm-4-v1.1'));
    const call = await waitFor(() => lastCall(store.fetchMock, 'POST', '/activate'));
    expect(call?.url).toContain('/prompt-templates/9/activate');

    const feedback = await screen.findByTestId('prompt-scene-feedback-4');
    expect(feedback).toHaveTextContent('已激活 v1.1 · 即时生效：下一次每日推荐生成即用该版本');
    // 重拉后：v1.1 使用中、v1.0 置废（以前端重拉的服务端状态为准）
    expect(await screen.findByTestId('prompt-card-4-v1.1')).toHaveTextContent('使用中');
    expect(screen.getByTestId('prompt-card-4-v1.0')).toHaveTextContent('已置废');
  });

  it('删除置废版本：rose 确认 Dialog → DELETE → 卡片消失 + 反馈；30069 守卫文案兜底', async () => {
    const store = makeStore();
    vi.stubGlobal('fetch', store.fetchMock);
    render(<PromptTemplates />);
    await screen.findByTestId('prompt-card-1-v1.0');
    const user = userEvent.setup();

    await user.click(screen.getByTestId('prompt-delete-1-v1.0'));
    expect(await screen.findByTestId('dialog')).toHaveTextContent('删除版本 · 个股简报 v1.0');
    expect(screen.getByTestId('dialog')).toHaveTextContent('物理删除不可恢复');
    await user.click(screen.getByTestId('prompt-delete-confirm-1-v1.0'));

    const call = await waitFor(() => lastCall(store.fetchMock, 'DELETE'));
    expect(call?.url).toContain('/prompt-templates/1');
    expect(await screen.findByTestId('prompt-scene-feedback-1')).toHaveTextContent('已删除 v1.0');
    await waitFor(() => expect(screen.queryByTestId('prompt-card-1-v1.0')).toBeNull());

    // 守卫兜底：后端 30069 文案透出到反馈条
    cleanup();
    const guardStore = makeStore({ deleteGuard: true });
    vi.stubGlobal('fetch', guardStore.fetchMock);
    render(<PromptTemplates />);
    await screen.findByTestId('prompt-card-1-v1.0');
    await userEvent.click(screen.getByTestId('prompt-delete-1-v1.0'));
    await userEvent.click((await screen.findByTestId('prompt-delete-confirm-1-v1.0')));
    expect(await screen.findByTestId('prompt-scene-feedback-1')).toHaveTextContent(
      '激活版本不可删除，请先切换激活到其他版本',
    );
  });

  it('未保存守卫 + 草稿兜底：dirty 返回需确认；草稿落 sessionStorage 可恢复', async () => {
    const store = makeStore();
    const textarea = await openScene1Editor(store.fetchMock);
    const user = userEvent.setup();

    await user.type(textarea, '{moveToEnd}未保存的调整');
    // 草稿防抖写入（key 含 briefType，存底稿版本号 + 正文）
    await waitFor(
      () => {
        const raw = sessionStorage.getItem('prompt-draft-1');
        expect(raw).not.toBeNull();
        expect(JSON.parse(raw as string)).toMatchObject({ baseVersionId: 3, strategy: 'MINOR' });
        expect(JSON.parse(raw as string).template).toContain('未保存的调整');
      },
      { timeout: 2000 },
    );

    // dirty 返回 → 确认 Dialog；确认后回列表
    await user.click(screen.getByTestId('prompt-editor-back'));
    expect(await screen.findByTestId('dialog')).toHaveTextContent('未保存的修改');
    await user.click(screen.getByTestId('prompt-editor-leave-confirm'));
    expect(await screen.findByTestId('prompt-templates-page')).toBeInTheDocument();

    // 重进编辑器：草稿提示恢复
    await user.click(screen.getByTestId('prompt-edit-1-v1.1'));
    const reopened = await screen.findByTestId('prompt-editor-textarea');
    expect(await screen.findByTestId('prompt-draft-notice')).toHaveTextContent('检测到未保存草稿');
    await user.click(screen.getByTestId('prompt-draft-restore'));
    expect(reopened).toHaveValue(`${TEMPLATE_V1_1}未保存的调整`);
  });

  it('占位符对照栏：点击插入光标处 + 实时使用次数（故事 4 场景 1/2）', async () => {
    const store = makeStore();
    const textarea = await openScene1Editor(store.fetchMock);
    const user = userEvent.setup();

    // 使用次数：industry 在底稿正文出现 1 次
    expect(await screen.findByTestId('prompt-ph-item-industry')).toHaveTextContent('×1');
    // 一键插入：光标在头部 → 插入串落在正文最前
    await user.click(screen.getByTestId('prompt-ph-item-industry'));
    expect(textarea).toHaveValue(`{{industry}}${TEMPLATE_V1_1}`);
    // 插入后使用次数实时 +1
    expect(screen.getByTestId('prompt-ph-item-industry')).toHaveTextContent('×2');
  });

  it('场景 2 休眠披露：对照栏如实展示 dormant note（amber）', async () => {
    const store = makeStore();
    vi.stubGlobal('fetch', store.fetchMock);
    render(<PromptTemplates />);
    await screen.findByTestId('prompt-scene-2');
    await userEvent.click(screen.getByTestId('prompt-new-2'));

    expect(await screen.findByTestId('prompt-editor')).toBeInTheDocument();
    const note = await screen.findByTestId('prompt-ph-dormant-note');
    expect(note).toHaveTextContent('该场景当前无生产触发入口');
    expect(note.className).toContain('amber');
  });

  it('对照栏加载失败不阻断编辑：栏内错误 + 重试恢复（保存校验降级仅硬校验）', async () => {
    const store = makeStore({ failPlaceholdersOnce: true });
    const textarea = await openScene1Editor(store.fetchMock);
    const user = userEvent.setup();

    expect(await screen.findByTestId('prompt-ph-retry')).toBeInTheDocument();
    expect(textarea).toBeInTheDocument(); // 编辑不被阻断
    await user.click(screen.getByTestId('prompt-ph-retry'));
    expect(await screen.findByTestId('prompt-ph-item-industry')).toBeInTheDocument();
  });
});
