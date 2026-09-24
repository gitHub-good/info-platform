import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Watchlist } from '@/pages/Watchlist';
import type { WatchlistView } from '@/types/watchlist';

// —— mock fetch store：按 URL + 方法路由，维持可变状态，支持错误分支 —— #

const HTTP_BY_CODE: Record<number, number> = {
  30001: 404,
  30010: 404,
  30011: 409,
  30012: 403,
  50000: 500,
};
const MSG_BY_CODE: Record<number, string> = {
  30001: '标的不存在',
  30010: '自选清单不存在',
  30011: '标的已在清单中',
  30012: '无权操作该清单',
  50000: '服务异常',
};

interface StoreOpts {
  /** 强制删标的返回该错误码（模拟越权 30012）。 */
  deleteItemCode?: number;
}

function cloneWl(w: WatchlistView): WatchlistView {
  return { ...w, items: w.items.map((i) => ({ ...i })) };
}

/** 构造一个状态化 fetch mock：GET 列表/单查、POST 创建/加标的、DELETE/PATCH 清单项。 */
function makeStore(opts: StoreOpts = {}) {
  const wl1: WatchlistView = {
    id: 1,
    name: '核心持仓',
    remark: null,
    status: 1,
    items: [{ id: 10, subjectId: 100, anomalyThreshold: 3, status: 1 }],
  };
  const wl2: WatchlistView = { id: 2, name: '观察池', remark: null, status: 1, items: [] };
  const watchlists: WatchlistView[] = [wl1, wl2];
  let nextItemId = 1000;
  let nextWlId = 100;

  const ok = (data: unknown) => ({
    ok: true,
    status: 200,
    json: async () => ({ code: 0, msg: 'ok', data, traceId: 't' }),
  });
  const fail = (code: number) => ({
    ok: false,
    status: HTTP_BY_CODE[code] ?? 500,
    json: async () => ({ code, msg: MSG_BY_CODE[code] ?? '服务异常', data: null, traceId: 't' }),
  });
  const find = (id: number) => watchlists.find((w) => w.id === id);

  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET';
    const path = String(url);

    if (method === 'GET' && /\/watchlists$/.test(path)) {
      return ok(watchlists.map(cloneWl));
    }
    let m = path.match(/\/watchlists\/(\d+)$/);
    if (method === 'GET' && m) {
      const w = find(Number(m[1]));
      return w ? ok(cloneWl(w)) : fail(30010);
    }
    if (method === 'POST' && /\/watchlists$/.test(path)) {
      const body = JSON.parse(init?.body as string) as { name: string };
      if (watchlists.some((w) => w.name === body.name)) return fail(30011);
      const wl: WatchlistView = { id: ++nextWlId, name: body.name, remark: null, status: 1, items: [] };
      watchlists.push(wl);
      return ok(cloneWl(wl));
    }
    m = path.match(/\/watchlists\/(\d+)\/items$/);
    if (method === 'POST' && m) {
      const id = Number(m[1]);
      const w = find(id);
      if (!w) return fail(30010);
      const body = JSON.parse(init?.body as string) as { subjectId: number; anomalyThreshold?: number };
      if (body.subjectId === 999) return fail(30001); // 模拟标的不存在
      if (w.items.some((i) => i.subjectId === body.subjectId)) return fail(30011); // 已在清单
      const item = {
        id: ++nextItemId,
        subjectId: body.subjectId,
        anomalyThreshold: body.anomalyThreshold ?? 3,
        status: 1,
      };
      w.items.push(item);
      return ok({ ...item });
    }
    m = path.match(/\/watchlists\/(\d+)\/items\/(\d+)$/);
    if (method === 'DELETE' && m) {
      if (opts.deleteItemCode) return fail(opts.deleteItemCode);
      const w = find(Number(m[1]));
      if (w) w.items = w.items.filter((i) => i.id !== Number(m[2]));
      return ok(null);
    }
    if (method === 'PATCH' && m) {
      const w = find(Number(m[1]));
      if (!w) return fail(30010);
      const item = w.items.find((i) => i.id === Number(m[2]));
      if (!item) return fail(30010);
      const body = JSON.parse(init?.body as string) as { anomalyThreshold: number };
      item.anomalyThreshold = body.anomalyThreshold;
      return ok({ ...item });
    }
    return fail(50000);
  });

  return { fetch };
}

afterEach(() => {
  vi.unstubAllGlobals();
  cleanup();
  localStorage.clear();
  window.location.hash = '';
});

/** 渲染并等待首屏：清单列表加载 + 自动选中第一个 + 详情加载完成。 */
async function renderReady(store: ReturnType<typeof makeStore>) {
  vi.stubGlobal('fetch', store.fetch);
  render(<Watchlist />);
  await screen.findByTestId('watchlist-item-row-10'); // wl1 的标的加载完成
  return store.fetch;
}

describe('Watchlist 管理页', () => {
  it('渲染清单列表（名称 / 标的数）与默认选中清单的标的明细', async () => {
    const store = makeStore();
    const fetchMock = await renderReady(store);

    // 列表：两张卡片 + 各自标的数徽章（详情标题复用清单名，按 testid 精确定位）
    expect(screen.getByTestId('watchlist-card-1')).toBeInTheDocument();
    expect(screen.getByTestId('watchlist-card-2')).toBeInTheDocument();
    expect(screen.getByTestId('watchlist-item-count-1')).toHaveTextContent('1 标的');
    expect(screen.getByTestId('watchlist-item-count-2')).toHaveTextContent('0 标的');
    // 详情：默认选中 wl1，标题与标的 100 / 阈值 3.00
    expect(screen.getByTestId('watchlist-detail-name')).toHaveTextContent('核心持仓');
    expect(screen.getByTestId('watchlist-item-subjectId-10')).toHaveTextContent('100');
    expect(screen.getByTestId('watchlist-item-threshold-10')).toHaveTextContent('3.00');
    // 已带 Bearer 受保护请求（此处未登录无 token，仅断言命中 /watchlists）
    expect(String(fetchMock.mock.calls[0][0])).toContain('/watchlists');
  });

  it('创建清单成功后列表刷新出现新卡片', async () => {
    const store = makeStore();
    const user = userEvent.setup();
    await renderReady(store);

    await user.type(screen.getByTestId('watchlist-create-name'), '新清单');
    await user.click(screen.getByTestId('watchlist-create-submit'));

    // 新建后选中该清单（详情标题=新清单），列表增至 3 张卡片
    await waitFor(() =>
      expect(screen.getByTestId('watchlist-detail-name')).toHaveTextContent('新清单'),
    );
    expect(screen.getAllByTestId(/watchlist-card-\d+/)).toHaveLength(3);
    // POST /watchlists 被调用
    const createCall = store.fetch.mock.calls.find(
      (c) => (c[1] as RequestInit).method === 'POST' && /\/watchlists$/.test(String(c[0])),
    );
    expect(createCall).toBeDefined();
  });

  it('创建清单 30011(409) 同名：展示同名错误', async () => {
    const store = makeStore();
    const user = userEvent.setup();
    await renderReady(store);

    await user.type(screen.getByTestId('watchlist-create-name'), '核心持仓');
    await user.click(screen.getByTestId('watchlist-create-submit'));

    expect(await screen.findByTestId('watchlist-create-error')).toHaveTextContent(
      '同名清单已存在',
    );
  });

  it('加标的成功后详情刷新出现新标的', async () => {
    const store = makeStore();
    const user = userEvent.setup();
    await renderReady(store);

    await user.click(screen.getByTestId('watchlist-add-item'));
    await user.type(screen.getByTestId('watchlist-add-subjectId'), '200');
    await user.clear(screen.getByTestId('watchlist-add-threshold'));
    await user.type(screen.getByTestId('watchlist-add-threshold'), '5');
    await user.click(screen.getByTestId('watchlist-add-submit'));

    // 新标的 200 / 阈值 5.00 出现，清单标的数变 2
    await screen.findByText('200');
    expect(screen.getByText('5.00')).toBeInTheDocument();
    expect(screen.getByTestId('watchlist-item-count-1')).toHaveTextContent('2 标的');
  });

  it('加标的 30011(409) 已在清单：展示已在清单错误', async () => {
    const store = makeStore();
    const user = userEvent.setup();
    await renderReady(store);

    await user.click(screen.getByTestId('watchlist-add-item'));
    await user.type(screen.getByTestId('watchlist-add-subjectId'), '100'); // wl1 已含 100
    await user.click(screen.getByTestId('watchlist-add-submit'));

    expect(await screen.findByTestId('watchlist-add-error')).toHaveTextContent(
      '该标的已在清单中',
    );
  });

  it('加标的 30001(404) 标的不存在：展示标的不存在错误', async () => {
    const store = makeStore();
    const user = userEvent.setup();
    await renderReady(store);

    await user.click(screen.getByTestId('watchlist-add-item'));
    await user.type(screen.getByTestId('watchlist-add-subjectId'), '999');
    await user.click(screen.getByTestId('watchlist-add-submit'));

    expect(await screen.findByTestId('watchlist-add-error')).toHaveTextContent('标的不存在');
  });

  it('删标的需二次确认：先弹确认 Dialog，取消不动、确认才删', async () => {
    const store = makeStore();
    const user = userEvent.setup();
    await renderReady(store);

    // 点击移除：先出确认 Dialog，未发 DELETE
    await user.click(screen.getByTestId('watchlist-remove-item-10'));
    expect(await screen.findByText('确认移除该标的？')).toBeInTheDocument();
    expect(screen.getByTestId('watchlist-remove-confirm-ok')).toBeInTheDocument();

    // 取消：Dialog 关闭、标的行不动、无 DELETE 请求
    await user.click(screen.getByTestId('watchlist-remove-confirm-cancel'));
    await waitFor(() => expect(screen.queryByText('确认移除该标的？')).toBeNull());
    expect(screen.getByTestId('watchlist-item-row-10')).toBeInTheDocument();
    expect(
      store.fetch.mock.calls.some(
        (c) => (c[1] as RequestInit).method === 'DELETE' && /\/items\/\d+$/.test(String(c[0])),
      ),
    ).toBe(false);

    // 再次移除并确认：DELETE 发出、标的行消失
    await user.click(screen.getByTestId('watchlist-remove-item-10'));
    await user.click(screen.getByTestId('watchlist-remove-confirm-ok'));
    await waitFor(() => {
      expect(screen.queryByTestId('watchlist-item-row-10')).toBeNull();
    });
    expect(
      store.fetch.mock.calls.some(
        (c) => (c[1] as RequestInit).method === 'DELETE' && /\/items\/\d+$/.test(String(c[0])),
      ),
    ).toBe(true);
  });

  it('删标的成功后详情刷新、标的行消失', async () => {
    const store = makeStore();
    const user = userEvent.setup();
    await renderReady(store);

    await user.click(screen.getByTestId('watchlist-remove-item-10'));
    await user.click(screen.getByTestId('watchlist-remove-confirm-ok'));

    await waitFor(() => {
      expect(screen.queryByTestId('watchlist-item-row-10')).toBeNull();
    });
    expect(screen.getByTestId('watchlist-item-count-1')).toHaveTextContent('0 标的');
    expect(screen.getByTestId('watchlist-detail-no-items')).toBeInTheDocument();
  });

  it('删标的 30012(403) 越权：展示无权操作错误', async () => {
    const store = makeStore({ deleteItemCode: 30012 });
    const user = userEvent.setup();
    await renderReady(store);

    await user.click(screen.getByTestId('watchlist-remove-item-10'));
    await user.click(screen.getByTestId('watchlist-remove-confirm-ok'));

    expect(await screen.findByTestId('watchlist-action-error')).toHaveTextContent(
      '无权操作该清单',
    );
    // 错误未刷新，标的行仍在
    expect(screen.getByTestId('watchlist-item-row-10')).toBeInTheDocument();
  });

  it('改阈值成功后详情刷新为新阈值', async () => {
    const store = makeStore();
    const user = userEvent.setup();
    await renderReady(store);

    await user.click(screen.getByTestId('watchlist-edit-threshold-10'));
    await user.clear(screen.getByTestId('watchlist-edit-threshold'));
    await user.type(screen.getByTestId('watchlist-edit-threshold'), '5');
    await user.click(screen.getByTestId('watchlist-edit-submit'));

    await waitFor(() => {
      expect(screen.getByTestId('watchlist-item-threshold-10')).toHaveTextContent('5.00');
    });
  });
});
