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
}

/** 状态化 fetch mock（沿 LlmConfig.test 范式：返回新克隆避免与组件持有引用共享可变态）。 */
function makeStore(opts: StoreOpts = {}) {
  const state = { list: structuredClone(opts.list ?? fullList()) };
  let listCalls = 0;
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    const path = String(url);
    const method = init?.method ?? 'GET';
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
      const briefType = Number(path.split('briefType=')[1]);
      return ok({ scenarios: [structuredClone({ ...REGISTRY_1, briefType })] });
    }
    return fail(500, 50000, `未模拟的请求: ${method} ${path}`);
  });
  return { fetchMock, state };
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
