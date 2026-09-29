import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
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
  /** 强制 GET /watchlists/{id}/items 返回 500（分页行加载失败，表格应显示错误+重试）。 */
  itemsFail?: boolean;
  /** 清单列表响应；缺省 [wl1, wl2]，传 [] 构造页面级空态。 */
  emptyList?: boolean;
  /** wl1 扩容为 12 项（100/200/300 循环，id 10~21）——分页/排序用例数据面。 */
  richList?: boolean;
}

function cloneWl(w: WatchlistView): WatchlistView {
  return { ...w, items: w.items.map((i) => ({ ...i })) };
}

/** 标的主数据池（体检 P1-2：搜索选择器 + 行情列的数据源）。 */
const SUBJECT_POOL = [
  { id: 100, subjectCode: 'SZ000858', name: '五粮液', market: 'A_SHARE', type: 1, industry: '白酒' },
  { id: 200, subjectCode: 'SH600036', name: '招商银行', market: 'A_SHARE', type: 1, industry: '银行' },
  { id: 300, subjectCode: 'SH600519', name: '贵州茅台', market: 'A_SHARE', type: 1, industry: '白酒' },
  { id: 999, subjectCode: 'SH999999', name: '不存在的标的', market: 'A_SHARE', type: 1, industry: '测试' },
];

const QUOTE_BY_ID: Record<number, { price: number; changePct: number }> = {
  100: { price: 128.5, changePct: -1.25 },
  200: { price: 38.2, changePct: 0.86 },
  // 300 无行情：price/changePct=null 行（行情缺失沉底 + 「—」回退用例）
};

/** 标的详情弹框（交互优化）：by-code 解析 + 聚合 detail 的最小视图（全分区 ok）。 */
function subjectDetailOf(id: number) {
  const base = SUBJECT_POOL.find((s) => s.id === id)!;
  const range = [1, 2, 3, 4];
  return {
    subject: { ...base },
    quote: {
      price: QUOTE_BY_ID[id]?.price ?? 10,
      changePct: QUOTE_BY_ID[id]?.changePct ?? 0,
      high: 130,
      low: 127,
      volume: 21000000,
      source: '行情源(eastmoney)',
      updatedAt: '2026-09-22 10:30:00',
    },
    finance: null,
    valuation: null,
    announcements: range.map((i) => ({
      title: `${base.name}公告${i}`,
      publishedAt: '2026-09-20',
      category: '其他',
      url: `https://example.com/a${i}`,
    })),
    news: range.map((i) => ({
      id: `n${i}`,
      title: `${base.name}新闻${i}`,
      publishedAt: '2026-09-21',
      url: `https://example.com/n${i}`,
      source: '新浪财经',
    })),
    policies: {
      items: range.map((i) => ({
        id: i,
        title: `${base.name}关联政策${i}`,
        url: `https://example.com/p${i}`,
        publishedAt: '2026-09-19',
        sourceName: '中国政府网',
        matchType: 'SUBJECT',
      })),
      fallback: null,
      basis: 'policy-scope-v1',
    },
    events: range.map((i) => ({
      anomalyType: 'PRICE_CHANGE',
      changePct: 3.25,
      triggerTime: `2026-09-21T02:0${i}:00Z`,
      detail: `${base.name}异动${i}`,
    })),
    sourceStatus: {
      quote: 'ok',
      finance: 'missing',
      valuation: 'missing',
      announce: 'ok',
      news: 'ok',
      policy: 'ok',
      event: 'ok',
    },
  };
}

/** 构造一个状态化 fetch mock：GET 列表/单查/search/清单项分页、POST 创建/加标的、DELETE/PATCH 清单项。 */
function makeStore(opts: StoreOpts = {}) {
  const wl1: WatchlistView = {
    id: 1,
    name: '核心持仓',
    remark: null,
    status: 1,
    items: opts.richList
      ? Array.from({ length: 12 }, (_, i) => ({
          id: 10 + i,
          subjectId: [100, 200, 300][i % 3],
          anomalyThreshold: 3,
          status: 1,
        }))
      : [{ id: 10, subjectId: 100, anomalyThreshold: 3, status: 1 }],
  };
  const wl2: WatchlistView = { id: 2, name: '观察池', remark: null, status: 1, items: [] };
  const watchlists: WatchlistView[] = [wl1, wl2];
  let nextItemId = opts.richList ? 22 : 10; // 新增清单项 id 顺延（断言按 testid 精确定位）
  let nextWlId = 100;
  let itemsFail = opts.itemsFail ?? false;

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

    if (method === 'GET' && /\/subjects\/search/.test(path)) {
      const q = new URL(path, 'http://localhost').searchParams.get('q') ?? '';
      const matched = SUBJECT_POOL.filter(
        (s) => s.name.includes(q) || s.subjectCode.includes(q.toUpperCase()),
      );
      return ok(matched);
    }
    // 标的详情弹框两跳：by-code 解析数字主键 → 聚合 detail（交互优化）
    if (method === 'GET' && /\/subjects\/by-code\//.test(path)) {
      const code = path.split('/by-code/')[1];
      const found = SUBJECT_POOL.find((s) => s.subjectCode === code);
      return found ? ok(found) : fail(30001);
    }
    let dm = path.match(/\/subjects\/(\d+)\/detail$/);
    if (method === 'GET' && dm) {
      const found = SUBJECT_POOL.find((s) => s.id === Number(dm[1]));
      return found ? ok(subjectDetailOf(found.id)) : fail(30001);
    }
    if (method === 'GET' && /\/watchlists$/.test(path)) {
      return ok(opts.emptyList ? [] : watchlists.map(cloneWl));
    }
    // 清单项分页+排序（GET /watchlists/{id}/items?page&size&sort&dir）：行情内联 + null 沉底
    let pm = path.match(/\/watchlists\/(\d+)\/items/);
    if (method === 'GET' && pm) {
      if (itemsFail) return fail(50000);
      const w = find(Number(pm[1]));
      if (!w) return fail(30010);
      const params = new URL(path, 'http://localhost').searchParams;
      const page = Number(params.get('page') ?? 1);
      const size = Number(params.get('size') ?? 20);
      const sort = params.get('sort') ?? 'addedAt';
      const dir = params.get('dir') ?? 'asc';
      const rows = w.items.map((it) => {
        const base = SUBJECT_POOL.find((s) => s.id === it.subjectId);
        const quote = base ? (QUOTE_BY_ID[base.id] ?? null) : null;
        return {
          id: it.id,
          subjectId: it.subjectId,
          anomalyThreshold: it.anomalyThreshold,
          subjectCode: base?.subjectCode ?? null,
          name: base?.name ?? null,
          market: base?.market ?? null,
          industry: base?.industry ?? null,
          price: quote?.price ?? null,
          changePct: quote?.changePct ?? null,
        };
      });
      const sortValue = (r: (typeof rows)[number]) =>
        sort === 'price' ? r.price : sort === 'changePct' ? r.changePct : r.id;
      rows.sort((a, b) => {
        const va = sortValue(a);
        const vb = sortValue(b);
        if (va == null && vb == null) return 0;
        if (va == null) return 1; // 行情缺失恒沉底
        if (vb == null) return -1;
        return dir === 'desc' ? vb - va : va - vb;
      });
      const from = (page - 1) * size;
      return ok({
        total: rows.length,
        items: rows.slice(from, from + size),
        page,
        size,
        sort,
        dir,
      });
    }
    let m = path.match(/\/watchlists\/(\d+)$/);
    if (method === 'GET' && m) {
      const w = find(Number(m[1]));
      return w ? ok(cloneWl(w)) : fail(30010);
    }
    if (method === 'PATCH' && m) {
      const w = find(Number(m[1]));
      if (!w) return fail(30010);
      const body = JSON.parse(init?.body as string) as { name: string };
      if (watchlists.some((o) => o.id !== w.id && o.name === body.name)) return fail(30011);
      w.name = body.name;
      return ok(cloneWl(w));
    }
    if (method === 'DELETE' && m) {
      const deleteId = Number(m[1]);
      const idx = watchlists.findIndex((w) => w.id === deleteId);
      if (idx < 0) return fail(30010);
      watchlists.splice(idx, 1);
      return ok(null);
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

  return {
    fetch,
    /** 切换清单项分页请求失败态（错误+重试用例）。 */
    setItemsFail(value: boolean) {
      itemsFail = value;
    },
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
  cleanup();
  localStorage.clear();
  window.location.hash = '';
});

/** 渲染并等待首屏：清单列表加载 + 自动选中第一个 + 详情（含行情）加载完成。 */
async function renderReady(store: ReturnType<typeof makeStore>) {
  vi.stubGlobal('fetch', store.fetch);
  render(<Watchlist />);
  await screen.findByTestId('watchlist-item-row-10'); // wl1 的标的加载完成
  return store.fetch;
}

/** 添加标的（体检 P1-2 搜索选择器）：输入关键字 → 联想下拉 → 选中 → （可选）填阈值。 */
async function pickSubject(
  user: ReturnType<typeof userEvent.setup>,
  query: string,
  optionId: number,
) {
  await user.type(screen.getByTestId('watchlist-add-subject-input'), query);
  await user.click(await screen.findByTestId(`watchlist-add-subject-option-${optionId}`));
}

describe('Watchlist 管理页', () => {
  it('空态：无清单时统一空态组件（标题 + 引导描述，T228 抽查）', async () => {
    vi.stubGlobal('fetch', makeStore({ emptyList: true }).fetch);
    render(<Watchlist />);

    expect(await screen.findByTestId('watchlist-list-empty')).toBeInTheDocument();
    // T230 抽查：统一页头 + 副标题补齐（原缺副标题页）
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('自选清单');
    expect(screen.getByTestId('page-header-subtitle')).toHaveTextContent('分组管理关注标的');
    expect(screen.getByTestId('empty-state-title')).toHaveTextContent('暂无清单');
    expect(screen.getByTestId('empty-state-description')).toHaveTextContent('创建清单');
  });

  it('渲染清单列表（名称 / 标的数）与默认选中清单的标的明细（代码/名称/行业/行情）', async () => {
    const store = makeStore();
    const fetchMock = await renderReady(store);

    // 列表：两张卡片 + 各自标的数徽章（详情标题复用清单名，按 testid 精确定位）
    expect(screen.getByTestId('watchlist-card-1')).toBeInTheDocument();
    expect(screen.getByTestId('watchlist-card-2')).toBeInTheDocument();
    expect(screen.getByTestId('watchlist-item-count-1')).toHaveTextContent('1 标的');
    expect(screen.getByTestId('watchlist-item-count-2')).toHaveTextContent('0 标的');
    // 详情：默认选中 wl1，标的列不再是裸数字 ID——代码+名称+行业徽章（体检 P1-2）
    expect(screen.getByTestId('watchlist-detail-name')).toHaveTextContent('核心持仓');
    // 行情为异步批量拉取：等待最新价就位后再断言其余列
    await waitFor(() =>
      expect(screen.getByTestId('watchlist-item-price-10')).toHaveTextContent('128.50'),
    );
    expect(screen.getByTestId('watchlist-item-subject-10')).toHaveTextContent('SZ000858');
    expect(screen.getByTestId('watchlist-item-subject-10')).toHaveTextContent('五粮液');
    expect(screen.getByTestId('watchlist-item-subject-10')).toHaveTextContent('白酒');
    // 行情列：涨跌幅（A 股惯例：跌绿）；阈值不变
    expect(screen.getByTestId('watchlist-item-changepct-10')).toHaveTextContent('-1.25%');
    expect(screen.getByTestId('watchlist-item-changepct-10')).toHaveClass('text-green-500');
    expect(screen.getByTestId('watchlist-item-threshold-10')).toHaveTextContent('3.00');
    // 已带 Bearer 受保护请求（此处未登录无 token，仅断言命中 /watchlists）
    expect(String(fetchMock.mock.calls[0][0])).toContain('/watchlists');
  });

  it('标的行操作：查看详情打开完整弹框（7 分区，无完整详情链接），AI 简报带参跳转', async () => {
    const store = makeStore();
    const user = userEvent.setup();
    await renderReady(store);

    // 查看详情：按钮打开弹框（完整版弹框承载全部内容；行情/摘要到达后渲染）
    await user.click(await screen.findByTestId('watchlist-item-detail-10'));
    const dialog = await screen.findByTestId('subject-dialog-content');
    // 头部（与独立页同源 SubjectHeader）：标的名 / 代码 / 市场 / 行业
    expect(within(dialog).getByText('五粮液')).toBeInTheDocument();
    expect(within(dialog).getByText(/SZ000858/)).toBeInTheDocument();
    expect(within(dialog).getByText('A 股')).toBeInTheDocument();
    expect(within(dialog).getByText(/行业：白酒/)).toBeInTheDocument();
    // 弹框内查看不离开清单页（hash 未跳走）
    expect(window.location.hash).toBe('');
    // 弹框即终点：无「查看完整详情」链接（完整内容已在弹框内）
    expect(screen.queryByTestId('subject-dialog-full-detail')).toBeNull();
    expect(screen.queryByText(/查看完整详情/)).toBeNull();

    // 关闭：弹框消失、清单明细仍在
    await user.click(screen.getByTestId('dialog-close'));
    await waitFor(() => expect(screen.queryByTestId('subject-dialog-content')).toBeNull());
    expect(screen.getByTestId('watchlist-item-row-10')).toBeInTheDocument();

    // AI 简报：带 subjectId 跳生成页
    await user.click(screen.getByTestId('watchlist-item-brief-10'));
    await waitFor(() =>
      expect(window.location.hash).toBe('#/ai-brief?subjectId=100'),
    );
  });

  it('行情缺失行：标的摘要仍在、行情列「—」且按最新价排序沉底，不阻断清单', async () => {
    // richList 含 subjectId=300（QUOTE_BY_ID 无行情）→ 该行 price/changePct=null
    vi.stubGlobal('fetch', makeStore({ richList: true }).fetch);
    render(<Watchlist />);
    await screen.findByTestId('watchlist-item-row-12'); // 第 3 行（subjectId=300）

    expect(screen.getByTestId('watchlist-item-subject-12')).toHaveTextContent('SH600519');
    expect(screen.getByTestId('watchlist-item-price-12')).toHaveTextContent('—');
    expect(screen.getByTestId('watchlist-item-changepct-12')).toHaveTextContent('—');
    expect(screen.getByTestId('watchlist-item-threshold-12')).toHaveTextContent('3.00');

    // 按最新价降序：行情缺失行（12/15/18/21，subjectId=300）沉底不随 desc 浮顶
    const user = userEvent.setup();
    await user.click(screen.getByTestId('watchlist-sort-price'));
    await waitFor(() =>
      expect(screen.getByTestId('watchlist-sort-price')).toHaveTextContent('▼'),
    );
    const rowIds = screen
      .getAllByTestId(/watchlist-item-row-\d+/)
      .map((el) => el.getAttribute('data-testid'));
    expect(rowIds.slice(-4)).toEqual([
      'watchlist-item-row-12',
      'watchlist-item-row-15',
      'watchlist-item-row-18',
      'watchlist-item-row-21',
    ]);
  });

  it('清单项分页请求失败：错误条 + 重试恢复（不再裸奔数字主键表）', async () => {
    const store = makeStore({ itemsFail: true });
    vi.stubGlobal('fetch', store.fetch);
    render(<Watchlist />);

    // ApiError 透传服务端 msg（50000 → 服务异常）
    expect(await screen.findByTestId('watchlist-items-error')).toHaveTextContent('服务异常');

    store.setItemsFail(false);
    const user = userEvent.setup();
    await user.click(within(screen.getByTestId('watchlist-items-error')).getByRole('button', { name: '重试' }));
    await screen.findByTestId('watchlist-item-row-10');
    expect(screen.getByTestId('watchlist-item-price-10')).toHaveTextContent('128.50');
  });

  it('清单项分页 + 排序：默认加入顺序 12 条一页，切 10 条/页翻第 2 页，最新价降序请求带 sort/dir', async () => {
    const store = makeStore({ richList: true });
    const fetchMock = store.fetch;
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    render(<Watchlist />);

    // 默认 addedAt asc：12 项单页全量（size=20），总数条 + 页码指示
    await screen.findByTestId('watchlist-item-row-21');
    expect(screen.getAllByTestId(/watchlist-item-row-\d+/)).toHaveLength(12);
    expect(screen.getByTestId('watchlist-items-total')).toHaveTextContent('12');
    expect(screen.getByTestId('watchlist-items-page-indicator')).toHaveTextContent('1 / 1');

    // 每页 10 条 → 第 1 页 10 行，翻第 2 页 2 行（原生 select，selectOptions 驱动）
    await user.selectOptions(screen.getByTestId('watchlist-items-size'), '10');
    await waitFor(() =>
      expect(screen.getAllByTestId(/watchlist-item-row-\d+/)).toHaveLength(10),
    );
    await user.click(screen.getByTestId('watchlist-items-next'));
    await waitFor(() =>
      expect(screen.getAllByTestId(/watchlist-item-row-\d+/)).toHaveLength(2),
    );

    // 点涨跌幅表头：切 changePct 降序并回第 1 页（请求参数对账）
    await user.click(screen.getByTestId('watchlist-sort-changePct'));
    await waitFor(() =>
      expect(screen.getByTestId('watchlist-sort-changePct')).toHaveTextContent('▼'),
    );
    const sortCall = fetchMock.mock.calls
      .map((c) => String(c[0]))
      .find((u) => u.includes('sort=changePct') && u.includes('dir=desc'));
    expect(sortCall).toBeDefined();
    expect(sortCall).toContain('page=1');
    expect(sortCall).toContain('size=10');
    // 再点一次 → 升序切换
    await user.click(screen.getByTestId('watchlist-sort-changePct'));
    await waitFor(() =>
      expect(screen.getByTestId('watchlist-sort-changePct')).toHaveTextContent('▲'),
    );
    expect(
      fetchMock.mock.calls.some((c) => String(c[0]).includes('sort=changePct') && String(c[0]).includes('dir=asc')),
    ).toBe(true);
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

  it('加标的：搜索选择器选中后提交，详情刷新出现新标的（不再手输数字 ID）', async () => {
    const store = makeStore();
    const user = userEvent.setup();
    await renderReady(store);

    await user.click(screen.getByTestId('watchlist-add-item'));
    await pickSubject(user, '600036', 200);
    await user.clear(screen.getByTestId('watchlist-add-threshold'));
    await user.type(screen.getByTestId('watchlist-add-threshold'), '5');
    await user.click(screen.getByTestId('watchlist-add-submit'));

    // 新标的代码/名称 + 阈值 5.00 出现，清单标的数变 2（摘要为批量异步拉取，等待就位）
    await waitFor(() =>
      expect(screen.getByTestId('watchlist-item-subject-11')).toHaveTextContent('SH600036'),
    );
    expect(screen.getByTestId('watchlist-item-threshold-11')).toHaveTextContent('5.00');
    expect(screen.getByTestId('watchlist-item-count-1')).toHaveTextContent('2 标的');
    // 数字 ID 输入框不复存在（体检 P1-2 移除）
    expect(screen.queryByTestId('watchlist-add-subjectId')).toBeNull();
    // POST body.subjectId 来自选中标的
    const addCall = store.fetch.mock.calls.find(
      (c) => (c[1] as RequestInit).method === 'POST' && /\/items$/.test(String(c[0])),
    );
    expect(addCall).toBeDefined();
    expect(JSON.parse((addCall![1] as RequestInit).body as string)).toMatchObject({
      subjectId: 200,
    });
  });

  it('加标的未选标的：提交拦截并提示先选择（不发 POST）', async () => {
    const store = makeStore();
    const user = userEvent.setup();
    await renderReady(store);

    await user.click(screen.getByTestId('watchlist-add-item'));
    await user.click(screen.getByTestId('watchlist-add-submit'));

    expect(await screen.findByTestId('watchlist-add-validation')).toHaveTextContent(
      '请先搜索并选择标的',
    );
    expect(
      store.fetch.mock.calls.some(
        (c) => (c[1] as RequestInit).method === 'POST' && /\/items$/.test(String(c[0])),
      ),
    ).toBe(false);
  });

  it('加标的 30011(409) 已在清单：展示已在清单错误', async () => {
    const store = makeStore();
    const user = userEvent.setup();
    await renderReady(store);

    await user.click(screen.getByTestId('watchlist-add-item'));
    await pickSubject(user, '五粮液', 100); // wl1 已含 subjectId=100
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
    await pickSubject(user, '999999', 999);
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

  it('清单列表失败：展示错误与重试，重试后恢复', async () => {
    const store = makeStore();
    // 首次清单列表请求失败（mount 即 GET /watchlists）
    store.fetch.mockImplementationOnce(async () => ({
      ok: false,
      status: 500,
      json: async () => ({ code: 50000, msg: '服务异常', data: null, traceId: 't' }),
    }));
    const user = userEvent.setup();
    vi.stubGlobal('fetch', store.fetch);
    render(<Watchlist />);

    expect(await screen.findByTestId('watchlist-list-error')).toHaveTextContent('服务异常');

    // 重试：列表恢复正常
    await user.click(screen.getByTestId('watchlist-list-retry'));
    expect(await screen.findByTestId('watchlist-card-1')).toBeInTheDocument();
    expect(screen.queryByTestId('watchlist-list-error')).toBeNull();
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

  // ---- 清单改名 / 删除 ----

  it('改名选中清单：预填当前名，提交后卡片与详情标题同步更新', async () => {
    const store = makeStore();
    const user = userEvent.setup();
    await renderReady(store);

    await user.click(screen.getByTestId('watchlist-rename-1'));
    const input = screen.getByTestId('watchlist-rename-input') as HTMLInputElement;
    expect(input.value).toBe('核心持仓'); // 预填当前名

    await user.clear(input);
    await user.type(input, '主力跟踪');
    await user.click(screen.getByTestId('watchlist-rename-submit'));

    await waitFor(() => expect(screen.getByTestId('watchlist-detail-name')).toHaveTextContent('主力跟踪'));
    expect(screen.getByTestId('watchlist-card-1')).toHaveTextContent('主力跟踪');
    const patchCall = store.fetch.mock.calls.find(
      (c) => (c[1] as RequestInit).method === 'PATCH' && /\/watchlists\/1$/.test(String(c[0])),
    );
    expect(patchCall).toBeDefined();
  });

  it('改名 30011(409) 与他清单同名：弹框内提示，卡片名不变', async () => {
    const store = makeStore();
    const user = userEvent.setup();
    await renderReady(store);

    await user.click(screen.getByTestId('watchlist-rename-1'));
    const input = screen.getByTestId('watchlist-rename-input');
    await user.clear(input);
    await user.type(input, '观察池'); // 与 wl2 同名
    await user.click(screen.getByTestId('watchlist-rename-submit'));

    await waitFor(() =>
      expect(screen.getByTestId('watchlist-rename-error')).toHaveTextContent('同名清单已存在'),
    );
    expect(screen.getByTestId('watchlist-card-1')).toHaveTextContent('核心持仓');
  });

  it('删除清单需二次确认：取消不动；确认后卡片消失、选中改选剩余第一张', async () => {
    const store = makeStore();
    const user = userEvent.setup();
    await renderReady(store);

    // 取消：不发 DELETE，卡片仍在
    await user.click(screen.getByTestId('watchlist-delete-1'));
    expect(screen.getByTestId('watchlist-delete-confirm-ok')).toBeInTheDocument();
    await user.click(screen.getByTestId('watchlist-delete-confirm-cancel'));
    expect(screen.getByTestId('watchlist-card-1')).toBeInTheDocument();
    expect(
      store.fetch.mock.calls.some(
        (c) => (c[1] as RequestInit).method === 'DELETE' && /\/watchlists\/1$/.test(String(c[0])),
      ),
    ).toBe(false);

    // 确认：删除选中的 wl1 → 改选 wl2，详情标题切到「观察池」
    await user.click(screen.getByTestId('watchlist-delete-1'));
    await user.click(screen.getByTestId('watchlist-delete-confirm-ok'));
    await waitFor(() =>
      expect(screen.queryByTestId('watchlist-card-1')).not.toBeInTheDocument(),
    );
    await waitFor(() =>
      expect(screen.getByTestId('watchlist-detail-name')).toHaveTextContent('观察池'),
    );
  });

  it('删除最后一张清单：卡片区空态 + 详情提示选择清单', async () => {
    const store = makeStore();
    const user = userEvent.setup();
    await renderReady(store);

    // 先删未选中的 wl2（不动选中态），再删选中的 wl1 → 全空
    await user.click(screen.getByTestId('watchlist-delete-2'));
    await user.click(screen.getByTestId('watchlist-delete-confirm-ok'));
    await waitFor(() =>
      expect(screen.queryByTestId('watchlist-card-2')).not.toBeInTheDocument(),
    );
    await user.click(screen.getByTestId('watchlist-delete-1'));
    await user.click(screen.getByTestId('watchlist-delete-confirm-ok'));
    await waitFor(() =>
      expect(screen.getByTestId('watchlist-list-empty')).toBeInTheDocument(),
    );
    await waitFor(() =>
      expect(screen.getByTestId('watchlist-detail-empty')).toBeInTheDocument(),
    );
  });
});
