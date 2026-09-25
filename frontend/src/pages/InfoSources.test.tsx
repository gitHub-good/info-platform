import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { InfoSources } from '@/pages/InfoSources';
import type { InfoSourceCardView, InfoSourcesView } from '@/types/infoSource';

// —— fetch mock：info-sources 组（GET 分组视图 / POST 新增 / PATCH 编辑 / DELETE 归档 /
//    restore / connectivity-test / poll 202·30074，状态化可变异） ——

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

function cardOf(overrides: Partial<InfoSourceCardView> = {}): InfoSourceCardView {
  return {
    id: 1,
    sourceCode: 't105mine',
    name: '我的RSS源',
    category: '自建',
    adapterType: 'rss',
    adapterRef: null,
    endpoint: 'https://example.com/feed.xml',
    config: {
      listPath: null,
      stripPrefix: null,
      stripSuffix: null,
      itemMapping: [],
      headers: {},
      maxItems: null,
      pageSize: null,
      cursorType: 'NONE',
      cursorField: null,
    },
    intervalMinutes: 15,
    enabled: true,
    preset: false,
    deleted: false,
    today: { pollCount: 12, failCount: 0, newCount: 5, dupCount: 1 },
    state: {
      lastAttemptAt: null,
      lastSuccessAt: null,
      nextDueAt: null,
      cursorValue: null,
      consecutiveFailures: 0,
      backoffUntil: null,
      lastDurationMillis: null,
      lastRoundDetail: null,
      lastError: null,
    },
    createdAt: '2026-09-22T01:00:00Z',
    updatedAt: '2026-09-22T01:00:00Z',
    ...overrides,
  };
}

const SUCCESS_DETAIL = 'new=3; dup=1; pages=1; backfill=none';

function fullView(): InfoSourcesView {
  const mine = cardOf();
  const jin10 = cardOf({
    id: 2,
    sourceCode: 'jin10_flash',
    name: '金十数据·快讯',
    category: '快讯',
    adapterType: 'json_api',
    preset: true,
    endpoint: 'https://www.jin10.com/flash_newest.js',
    intervalMinutes: 5,
    state: {
      lastAttemptAt: '2026-09-22T02:30:00Z',
      lastSuccessAt: '2026-09-22T02:30:00Z',
      nextDueAt: '2026-09-22T02:35:00Z',
      cursorValue: '202609221030',
      consecutiveFailures: 0,
      backoffUntil: null,
      lastDurationMillis: 412,
      lastRoundDetail: SUCCESS_DETAIL,
      lastError: null,
    },
  });
  const mw = cardOf({
    id: 3,
    sourceCode: 'mw_topstories',
    name: 'MarketWatch·头条',
    category: '国际',
    adapterType: 'rss',
    preset: true,
    endpoint: 'https://feeds.content.dowjones.io/public/rss/mw_topstories',
    intervalMinutes: 30,
    state: {
      lastAttemptAt: '2026-09-22T02:31:00Z',
      lastSuccessAt: '2026-09-22T01:00:00Z',
      nextDueAt: '2026-09-22T03:00:00Z',
      cursorValue: null,
      consecutiveFailures: 1,
      backoffUntil: null,
      lastDurationMillis: 5000,
      lastRoundDetail: null,
      lastError: 'feeds.content.dowjones.io 连接超时',
    },
  });
  const zhibo = cardOf({
    id: 4,
    sourceCode: 'sina_zhibo_7x24',
    name: '新浪财经·7×24',
    category: '快讯',
    adapterType: 'preset',
    adapterRef: 'sinaZhiboAdapter',
    preset: true,
    endpoint: 'https://zhibo.sina.com.cn/api/zhibo/feed?zhibo_id=152',
    intervalMinutes: 5,
    state: {
      lastAttemptAt: '2026-09-22T02:31:00Z',
      lastSuccessAt: '2026-09-22T02:31:00Z',
      nextDueAt: '2026-09-22T02:36:00Z',
      cursorValue: '1526789000',
      consecutiveFailures: 3,
      backoffUntil: '2999-01-01T00:00:00Z',
      lastDurationMillis: 300,
      lastRoundDetail: 'new=5; dup=0; pages=1; backfill=none',
      lastError: '上一轮失败摘要',
    },
  });
  const archived = cardOf({
    id: 5,
    sourceCode: 't105old',
    name: '旧测试源',
    deleted: true,
    enabled: false,
  });
  return {
    groups: [
      { category: '快讯', sources: [jin10, zhibo] },
      { category: '国际', sources: [mw] },
      { category: '自建', sources: [mine] },
    ],
    archived: [archived],
  };
}

interface StoreOpts {
  view?: InfoSourcesView;
  failGet?: boolean;
  pollStatus?: 'accepted' | 'inFlight';
}

/** 状态化 mock：写接口直接变异内部 view（PATCH 合并 / DELETE 软删 / restore 回停用）。 */
function makeStore({ view = fullView(), failGet = false, pollStatus = 'accepted' }: StoreOpts = {}) {
  const state = { view };
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    const path = String(url);
    const method = init?.method ?? 'GET';
    if (path.endsWith('/info-sources') && method === 'GET') {
      if (failGet) return fail(500, 50000, '服务异常');
      return ok(structuredClone(state.view));
    }
    if (path.endsWith('/info-sources') && method === 'POST') {
      const body = JSON.parse(String(init?.body));
      const created = cardOf({
        id: 9,
        sourceCode: 't105created',
        name: body.name,
        category: body.category,
        adapterType: body.adapterType,
        endpoint: body.endpoint,
        intervalMinutes: body.intervalMinutes,
      });
      return ok(created);
    }
    const idMatch = path.match(/\/info-sources\/(\d+)(?:\/(.*))?$/);
    if (idMatch) {
      const id = Number(idMatch[1]);
      const action = idMatch[2];
      const findIn = (): InfoSourceCardView => {
        for (const group of state.view.groups) {
          const hit = group.sources.find((s) => s.id === id);
          if (hit) return hit;
        }
        return state.view.archived.find((s) => s.id === id) ?? cardOf({ id });
      };
      if (action === 'connectivity-test') {
        return ok({
          reachable: true,
          robotsAllowed: true,
          latencyMillis: 412,
          parsedCount: 20,
          error: null,
          sampleItems: [],
        });
      }
      if (action === 'poll') {
        if (pollStatus === 'inFlight') return fail(409, 30074, '该源抓取正在进行中，请稍后重试');
        return accepted({ sourceId: id, sourceCode: 't105mine' });
      }
      if (action === 'restore') {
        const restored = findIn();
        restored.deleted = false;
        restored.enabled = false;
        return ok(structuredClone(restored));
      }
      if (method === 'DELETE') {
        const target = findIn();
        target.deleted = true;
        target.enabled = false;
        return ok(structuredClone(target));
      }
      if (method === 'PATCH') {
        const body = JSON.parse(String(init?.body));
        const target = findIn();
        Object.assign(target, {
          ...('name' in body ? { name: body.name } : {}),
          ...('endpoint' in body ? { endpoint: body.endpoint } : {}),
          ...('intervalMinutes' in body ? { intervalMinutes: body.intervalMinutes } : {}),
          ...('enabled' in body ? { enabled: body.enabled } : {}),
        });
        return ok(structuredClone(target));
      }
    }
    return fail(404, 50000, `未匹配的请求 ${method} ${path}`);
  });
  return { fetchMock, state };
}

function renderPage(store: ReturnType<typeof makeStore>) {
  vi.stubGlobal('fetch', store.fetchMock);
  render(<InfoSources />);
}

async function openAddDialog() {
  await userEvent.click(await screen.findByTestId('info-sources-add'));
  return screen.getByTestId('dialog');
}

describe('InfoSources 资讯源管理页（M13 T105）', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  it('渲染分组视图：分组标题 / 类型徽章 / 指标行（间隔·今日+新增）', async () => {
    renderPage(makeStore());

    expect(await screen.findByTestId('info-sources-page')).toBeInTheDocument();
    expect(screen.getByText('快讯')).toBeInTheDocument();
    expect(screen.getByText('国际')).toBeInTheDocument();
    expect(screen.getByText('自建')).toBeInTheDocument();
    expect(screen.getByTestId('info-source-type-jin10_flash')).toHaveTextContent('JSON');
    expect(screen.getByTestId('info-source-type-mw_topstories')).toHaveTextContent('RSS');
    expect(screen.getByTestId('info-source-type-sina_zhibo_7x24')).toHaveTextContent('预置');
    expect(
      screen.getByTestId('info-source-card-t105mine'),
    ).toHaveTextContent('每 15 分钟');
    expect(screen.getByTestId('info-source-card-t105mine')).toHaveTextContent('今日 +5');
    expect(screen.getByTestId('info-source-card-sina_zhibo_7x24')).toHaveTextContent(
      '预置适配 · adapter bean 只读（sinaZhiboAdapter）',
    );
  });

  it('加载中展示分组骨架（loading 态）', async () => {
    renderPage(makeStore());
    expect(await screen.findByTestId('info-sources-loading')).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.queryByTestId('info-sources-loading')).not.toBeInTheDocument(),
    );
  });

  it('整页失败：role=alert 错误 + 重试可恢复', async () => {
    const store = makeStore({ failGet: true });
    renderPage(store);

    expect(await screen.findByTestId('info-sources-error')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toBeInTheDocument();

    store.fetchMock.mockImplementation(async (url: string) => {
      if (String(url).endsWith('/info-sources')) return ok(fullView());
      return fail(404, 50000, 'x');
    });
    await userEvent.click(screen.getByTestId('info-sources-retry'));
    expect(await screen.findByTestId('info-source-card-t105mine')).toBeInTheDocument();
  });

  it('空列表属异常场景：显示种子检查空态', async () => {
    renderPage(makeStore({ view: { groups: [], archived: [] } }));
    expect(await screen.findByTestId('info-sources-empty')).toBeInTheDocument();
  });

  it('五态徽章矩阵：成功·新N / 失败摘要 / 退避中 / 暂未抓取', async () => {
    renderPage(makeStore());

    expect(await screen.findByTestId('info-source-status-jin10_flash')).toHaveTextContent('成功');
    expect(screen.getByTestId('info-source-status-jin10_flash')).toHaveTextContent('新3');
    const failedBadge = screen.getByTestId('info-source-status-mw_topstories');
    expect(failedBadge).toHaveTextContent(/^失败 · /);
    // title 悬停含完整诊断（UI §3.5）
    expect(failedBadge).toHaveAttribute('title', 'feeds.content.dowjones.io 连接超时');
    expect(screen.getByTestId('info-source-status-sina_zhibo_7x24')).toHaveTextContent(
      '连续失败 3 次',
    );
    expect(screen.getByTestId('info-source-status-t105mine')).toHaveTextContent('暂未抓取');
  });

  it('预置源卡无归档入口；通用源有「停用并归档」', async () => {
    renderPage(makeStore());

    await screen.findByTestId('info-source-card-t105mine');
    expect(screen.queryByTestId('info-source-archive-jin10_flash')).not.toBeInTheDocument();
    expect(screen.getByTestId('info-source-archive-t105mine')).toBeInTheDocument();
  });

  it('归档区默认隐藏：开关开启显示已归档源与恢复动作，空归档显示占位', async () => {
    renderPage(makeStore());

    await screen.findByTestId('info-source-card-t105mine');
    expect(screen.queryByTestId('info-source-card-t105old')).not.toBeInTheDocument();

    await userEvent.click(screen.getByTestId('info-sources-show-archived'));
    expect(screen.getByTestId('info-source-card-t105old')).toBeInTheDocument();
    expect(screen.getByTestId('info-source-restore-t105old')).toBeInTheDocument();

    // 空归档占位
    const emptyStore = makeStore({ view: { groups: fullView().groups, archived: [] } });
    cleanup();
    vi.unstubAllGlobals();
    renderPage(emptyStore);
    await screen.findByTestId('info-source-card-t105mine');
    await userEvent.click(screen.getByTestId('info-sources-show-archived'));
    expect(screen.getByTestId('info-sources-archived-empty')).toBeInTheDocument();
  });

  it('启停 Switch 即存即生效：PATCH enabled，卡内反馈', async () => {
    const store = makeStore();
    renderPage(store);

    await screen.findByTestId('info-source-card-t105mine');
    await userEvent.click(screen.getByTestId('info-source-enabled-t105mine'));

    await waitFor(() => {
      expect(
        store.fetchMock.mock.calls.some(
          ([url, init]) =>
            String(url).endsWith('/info-sources/1') &&
            init?.method === 'PATCH' &&
            String(init.body).includes('"enabled":false'),
        ),
      ).toBe(true);
    });
    await waitFor(() =>
      expect(screen.getByTestId('info-source-feedback-t105mine')).toHaveTextContent('已停用'),
    );
    expect(screen.getByTestId('info-source-card-t105mine')).toHaveClass('opacity-60');
  });

  it('新增源 Dialog：JSON 切换展开映射字段集，RSS 隐藏整区；校验拦截空名称', async () => {
    renderPage(makeStore());
    const dialog = await openAddDialog();

    expect(within(dialog).getByTestId('info-source-add-type-rss')).toBeEnabled();
    expect(within(dialog).queryByTestId('info-source-add-list-path')).not.toBeInTheDocument();

    await userEvent.click(within(dialog).getByTestId('info-source-add-type-json'));
    expect(within(dialog).getByTestId('info-source-add-list-path')).toBeInTheDocument();
    expect(within(dialog).getByTestId('info-source-add-mapping-title')).toBeInTheDocument();

    await userEvent.click(within(dialog).getByTestId('info-source-add-save'));
    expect(
      within(dialog).getAllByRole('alert').some((el) => el.textContent?.includes('名称不能为空')),
    ).toBe(true);
  });

  it('新增源：JSON 轻量映射序列化（transform 按目标字段缺省）+ 保存成功引导条不自动关', async () => {
    const store = makeStore();
    renderPage(store);
    const dialog = await openAddDialog();

    await userEvent.click(within(dialog).getByTestId('info-source-add-type-json'));
    await userEvent.type(within(dialog).getByTestId('info-source-add-name'), '金十镜像');
    await userEvent.selectOptions(within(dialog).getByTestId('info-source-add-category'), '快讯');
    await userEvent.type(
      within(dialog).getByTestId('info-source-add-url'),
      'https://mirror.example.com/flash.js',
    );
    await userEvent.clear(within(dialog).getByTestId('info-source-add-interval'));
    await userEvent.type(within(dialog).getByTestId('info-source-add-interval'), '5');
    await userEvent.type(within(dialog).getByTestId('info-source-add-list-path'), 'result.data');
    await userEvent.type(within(dialog).getByTestId('info-source-add-mapping-title'), 'title');
    await userEvent.type(within(dialog).getByTestId('info-source-add-mapping-time'), 'time');
    await userEvent.type(within(dialog).getByTestId('info-source-add-mapping-id'), 'id');
    await userEvent.click(within(dialog).getByTestId('info-source-add-save'));

    await waitFor(() => {
      const call = store.fetchMock.mock.calls.find(
        ([url, init]) => String(url).endsWith('/info-sources') && init?.method === 'POST',
      );
      expect(call).toBeTruthy();
      const body = JSON.parse(String(call?.[1]?.body));
      expect(body.adapterType).toBe('json_api');
      expect(body.intervalMinutes).toBe(5);
      expect(body.category).toBe('快讯');
      expect(body.config.listPath).toBe('result.data');
      expect(body.config.itemMapping).toEqual([
        { source: 'title', target: 'title', transform: 'to_string' },
        { source: 'time', target: 'publishedAt', transform: 'to_iso_datetime' },
        { source: 'id', target: 'externalId', transform: 'to_string' },
      ]);
    });
    // 保存成功不自动关：引导条 + [立即抓取][完成]（UI §4.3）
    expect(await within(dialog).findByTestId('info-source-add-guide')).toBeInTheDocument();
    expect(within(dialog).getByTestId('info-source-add-poll')).toBeInTheDocument();
    expect(within(dialog).getByTestId('info-source-add-done')).toBeInTheDocument();
  });

  it('新增源 RSS 类型不带映射键；完成关闭并刷新（新卡入分组）', async () => {
    const store = makeStore();
    renderPage(store);
    const dialog = await openAddDialog();

    await userEvent.type(within(dialog).getByTestId('info-source-add-name'), 'New Rss');
    await userEvent.type(
      within(dialog).getByTestId('info-source-add-url'),
      'https://example.com/n.xml',
    );
    await userEvent.click(within(dialog).getByTestId('info-source-add-save'));
    await within(dialog).findByTestId('info-source-add-guide');

    await userEvent.click(within(dialog).getByTestId('info-source-add-done'));
    await waitFor(() =>
      expect(screen.getByTestId('info-source-card-t105created')).toBeInTheDocument(),
    );
  });

  it('新增源 30072：后端文案内联回显，表单保持原值', async () => {
    const store = makeStore();
    store.fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      if (String(url).endsWith('/info-sources') && init?.method === 'POST') {
        return fail(400, 30072, 'sourceCode 生成冲突（newrss，同名源已存在），请修改名称');
      }
      if (String(url).endsWith('/info-sources')) return ok(fullView());
      return fail(404, 50000, 'x');
    });
    renderPage(store);
    const dialog = await openAddDialog();

    await userEvent.type(within(dialog).getByTestId('info-source-add-name'), 'New Rss');
    await userEvent.type(
      within(dialog).getByTestId('info-source-add-url'),
      'https://example.com/n.xml',
    );
    await userEvent.click(within(dialog).getByTestId('info-source-add-save'));

    expect(await within(dialog).findByText(/同名源已存在/)).toBeInTheDocument();
    expect(within(dialog).getByTestId('info-source-add-save')).toBeInTheDocument();
    expect(
      (within(dialog).getByTestId('info-source-add-name') as HTMLInputElement).value,
    ).toBe('New Rss');
  });

  it('编辑 Dialog：回填 + 类型分段禁用；保存 PATCH 热生效反馈', async () => {
    const store = makeStore();
    renderPage(store);

    await screen.findByTestId('info-source-card-t105mine');
    await userEvent.click(screen.getByTestId('info-source-edit-t105mine'));
    const dialog = screen.getByTestId('dialog');

    expect(
      (within(dialog).getByTestId('info-source-add-name') as HTMLInputElement).value,
    ).toBe('我的RSS源');
    expect(within(dialog).getByTestId('info-source-add-type-rss')).toBeDisabled();
    expect(within(dialog).getByTestId('info-source-add-type-json')).toBeDisabled();

    await userEvent.clear(within(dialog).getByTestId('info-source-add-name'));
    await userEvent.type(within(dialog).getByTestId('info-source-add-name'), '我的RSS源V2');
    await userEvent.clear(within(dialog).getByTestId('info-source-add-interval'));
    await userEvent.type(within(dialog).getByTestId('info-source-add-interval'), '30');
    await userEvent.click(within(dialog).getByTestId('info-source-add-save'));

    await waitFor(() => {
      const call = store.fetchMock.mock.calls.find(
        ([url, init]) => String(url).endsWith('/info-sources/1') && init?.method === 'PATCH',
      );
      expect(JSON.parse(String(call?.[1]?.body))).toMatchObject({
        name: '我的RSS源V2',
        intervalMinutes: 30,
      });
    });
    await waitFor(() =>
      expect(screen.queryByTestId('dialog')).not.toBeInTheDocument(),
    );
    expect(screen.getByTestId('info-source-card-t105mine')).toHaveTextContent('我的RSS源V2');
  });

  it('软删二次确认：确认后 DELETE，卡片移出分组并在归档区可见', async () => {
    const store = makeStore();
    renderPage(store);

    await screen.findByTestId('info-source-card-t105mine');
    await userEvent.click(screen.getByTestId('info-source-archive-t105mine'));

    const confirm = screen.getByTestId('dialog');
    expect(within(confirm).getByText(/已入库条目保留不删/)).toBeInTheDocument();
    expect(
      within(confirm).getByTestId('info-source-archive-confirm-t105mine'),
    ).toBeInTheDocument();

    await userEvent.click(within(confirm).getByTestId('info-source-archive-confirm-t105mine'));
    await waitFor(() =>
      expect(screen.queryByTestId('info-source-card-t105mine')).not.toBeInTheDocument(),
    );
    expect(
      store.fetchMock.mock.calls.some(
        ([url, init]) => String(url).endsWith('/info-sources/1') && init?.method === 'DELETE',
      ),
    ).toBe(true);

    await userEvent.click(screen.getByTestId('info-sources-show-archived'));
    expect(screen.getByTestId('info-source-card-t105mine')).toBeInTheDocument();
  });

  it('恢复归档源：POST restore → 回停用态（开关关闭）', async () => {
    const store = makeStore();
    renderPage(store);

    await screen.findByTestId('info-source-card-t105mine');
    await userEvent.click(screen.getByTestId('info-sources-show-archived'));
    await userEvent.click(screen.getByTestId('info-source-restore-t105old'));

    await waitFor(() => {
      expect(
        store.fetchMock.mock.calls.some(
          ([url, init]) =>
            String(url).endsWith('/info-sources/5/restore') && init?.method === 'POST',
        ),
      ).toBe(true);
    });
    // 恢复回停用态：归档区移除、以停用卡回到自建分组（开关 off）
    await waitFor(() =>
      expect(screen.getByTestId('info-source-enabled-t105old')).toHaveAttribute(
        'aria-checked',
        'false',
      ),
    );
    expect(screen.getByTestId('info-source-card-t105old')).toBeInTheDocument();
  });

  it('测试连通：成功内联结果行（延迟/条数/robots）；失败显示原因', async () => {
    const store = makeStore();
    store.fetchMock.mockImplementation(async (url: string) => {
      const path = String(url);
      if (path.endsWith('/info-sources/2/connectivity-test')) {
        return ok({
          reachable: true,
          robotsAllowed: true,
          latencyMillis: 412,
          parsedCount: 20,
          error: null,
          sampleItems: [],
        });
      }
      if (path.endsWith('/info-sources/1/connectivity-test')) {
        return ok({
          reachable: false,
          robotsAllowed: true,
          latencyMillis: 5000,
          parsedCount: 0,
          error: '连接超时',
          sampleItems: [],
        });
      }
      if (path.endsWith('/info-sources')) return ok(fullView());
      return fail(404, 50000, 'x');
    });
    renderPage(store);

    await screen.findByTestId('info-source-card-t105mine');
    await userEvent.click(screen.getByTestId('info-source-connect-jin10_flash'));
    expect(await screen.findByTestId('info-source-connect-result-jin10_flash')).toHaveTextContent(
      '连通 · 412ms · 解析 20 条（robots 允许）',
    );

    await userEvent.click(screen.getByTestId('info-source-connect-t105mine'));
    expect(await screen.findByTestId('info-source-connect-result-t105mine')).toHaveTextContent(
      '失败：连接超时',
    );
  });

  it('立即抓取：202 受理 → 「抓取中…」态；30074 在飞收敛不弹错', async () => {
    const store = makeStore();
    renderPage(store);

    await screen.findByTestId('info-source-card-t105mine');
    await userEvent.click(screen.getByTestId('info-source-poll-t105mine'));

    await waitFor(() =>
      expect(screen.getByTestId('info-source-status-t105mine')).toHaveTextContent('抓取中…'),
    );
    expect(
      store.fetchMock.mock.calls.some(
        ([url, init]) =>
          String(url).endsWith('/info-sources/1/poll') && init?.method === 'POST',
      ),
    ).toBe(true);
    expect(screen.getByTestId('info-source-poll-t105mine')).toBeDisabled();

    // 在飞（409/30074）：仍为抓取中态，不出现错误反馈
    const inFlightStore = makeStore({ pollStatus: 'inFlight' });
    cleanup();
    vi.unstubAllGlobals();
    renderPage(inFlightStore);
    await screen.findByTestId('info-source-card-t105mine');
    await userEvent.click(screen.getByTestId('info-source-poll-t105mine'));
    await waitFor(() =>
      expect(screen.getByTestId('info-source-status-t105mine')).toHaveTextContent('抓取中…'),
    );
    expect(screen.queryByTestId('info-source-feedback-t105mine')).not.toBeInTheDocument();
  });
});
