// 订阅管理页测试（体检 P1-3）：列表渲染（类型徽章/标的解析/状态）/ 新建（类型表单+校验+判重）/ 退订确认 /
// 重新订阅 / M26 T227 页码分页（M9 语义：翻页在途保留/失败重试/空页回退/游标形态退役反向断言）/
// 三态（骨架、空态 CTA、错误重试）。
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Subscriptions } from '@/pages/Subscriptions';
import type { SubscriptionView } from '@/types/subscription';

// —— fetch mock：按 URL 路由 /subscriptions CRUD 与 /subjects/quotes ——

const ok = (data: unknown) => ({
  ok: true,
  status: 200,
  json: async () => ({ code: 0, msg: 'ok', data, traceId: 't' }),
});
const fail = (code = 50000, msg = '服务异常') => ({
  ok: false,
  status: 500,
  json: async () => ({ code, msg, data: null, traceId: 't' }),
});

function subOf(overrides: Partial<SubscriptionView> = {}): SubscriptionView {
  return { id: 1, subType: 1, subKey: '人工智能', channel: 1, status: 1, ...overrides };
}

/** 覆盖四类型的订阅条目（标的订阅 subKey=数字主键 7，待 quotes 解析）。 */
function fourTypeSubs(): SubscriptionView[] {
  return [
    subOf({ id: 11, subType: 1, subKey: '半导体国产替代' }),
    subOf({ id: 12, subType: 2, subKey: '7' }),
    subOf({ id: 13, subType: 3, subKey: '公告' }),
    subOf({ id: 14, subType: 4, subKey: '货币政策', status: 0 }),
  ];
}

const QUOTE_ROW = {
  id: 7,
  subjectCode: 'SH600519',
  name: '贵州茅台',
  market: 'SH',
  type: 1,
  industry: '白酒',
  quote: null,
};

interface StoreOpts {
  /** 首屏订阅列表（单页形态：total = subs.length）；'FAIL' 强制失败。 */
  subs?: SubscriptionView[] | 'FAIL';
  /** 页码模式分页数据（按请求 page 索引；未列出的页返回空 items + 首页 total）。 */
  pages?: Record<number, { items: SubscriptionView[]; total: number }>;
  /** 指定页码首次请求失败（重试/回退路径用；后续请求恢复）。 */
  failFirstOnPage?: number;
  /** POST /subscriptions 响应工厂；'FAIL' 强制失败。 */
  onCreate?: (() => ReturnType<typeof ok>) | 'FAIL';
  /** DELETE 响应工厂；'FAIL' 强制失败。 */
  onDelete?: (() => ReturnType<typeof ok>) | 'FAIL';
  /** /subjects/quotes 响应工厂；'FAIL' 强制失败。 */
  onQuotes?: (() => ReturnType<typeof ok>) | 'FAIL';
}

function makeStore(opts: StoreOpts = {}) {
  const listUrls: string[] = [];
  const failedPages = new Set<number>();
  const created: Array<{ subType: number; subKey: string }> = [];
  const deleted: number[] = [];
  const fetchMock = vi.fn(async (url: unknown, init?: RequestInit) => {
    const path = String(url);
    const method = init?.method ?? 'GET';
    if (path.includes('/subjects/search')) {
      return ok([QUOTE_ROW]);
    }
    if (path.includes('/subjects/quotes')) {
      if (opts.onQuotes === 'FAIL') return fail();
      return ok([QUOTE_ROW]);
    }
    if (/\/subscriptions\/\d+$/.test(path) && method === 'DELETE') {
      deleted.push(Number(path.split('/').pop()));
      if (opts.onDelete === 'FAIL') return fail();
      return ok(null);
    }
    if (path.includes('/subscriptions') && method === 'POST') {
      created.push(JSON.parse(String(init?.body)) as { subType: number; subKey: string });
      if (opts.onCreate === 'FAIL') return fail(2001, 'subType 取值 1~4');
      return ok(subOf({ id: 99 }));
    }
    if (/\/subscriptions\?/.test(path) && method === 'GET') {
      // 页码模式：按请求 page 返回（total 与 items 分离以构造空页/收缩场景）
      const query = new URLSearchParams(path.split('?')[1]);
      const page = Number(query.get('page') ?? '1');
      const size = Number(query.get('size') ?? '20');
      listUrls.push(path);
      if (opts.subs === 'FAIL') return fail();
      if (opts.failFirstOnPage === page && !failedPages.has(page)) {
        failedPages.add(page);
        return fail();
      }
      const preset = opts.pages?.[page];
      if (preset) return ok({ ...preset, page, size });
      if (opts.pages) {
        const firstTotal = opts.pages[1]?.total ?? 0;
        return ok({ total: firstTotal, items: [], page, size });
      }
      const subs = opts.subs ?? [];
      return ok({ total: subs.length, items: subs, page, size });
    }
    return fail();
  });
  return { fetchMock, created, deleted, listUrls };
}

afterEach(() => {
  vi.unstubAllGlobals();
  cleanup();
  localStorage.clear();
  window.location.hash = '';
});

describe('Subscriptions 订阅管理页（体检 P1-3）', () => {
  it('主路径：四类型订阅渲染（类型徽章/标的经 quotes 解析为代码+名称/已退订状态徽章与重新订阅按钮）', async () => {
    vi.stubGlobal('fetch', makeStore({ subs: fourTypeSubs() }).fetchMock);

    render(<Subscriptions />);

    expect(await screen.findByTestId('sub-item-11')).toBeInTheDocument();
    expect(screen.getByTestId('sub-type-11')).toHaveTextContent('主题');
    expect(screen.getByTestId('sub-content-11')).toHaveTextContent('半导体国产替代');
    // 标的订阅：subKey=7 → SH600519 贵州茅台（quotes 批量解析）
    await waitFor(() =>
      expect(screen.getByTestId('sub-content-12')).toHaveTextContent('SH600519 贵州茅台'),
    );
    expect(screen.getByTestId('sub-type-12')).toHaveTextContent('标的');
    expect(screen.getByTestId('sub-type-13')).toHaveTextContent('事件类型');
    expect(screen.getByTestId('sub-content-13')).toHaveTextContent('公告');
    expect(screen.getByTestId('sub-type-14')).toHaveTextContent('政策主题');
    expect(screen.getByTestId('sub-status-11')).toHaveTextContent('订阅中');
    expect(screen.getByTestId('sub-status-14')).toHaveTextContent('已退订');
    // 订阅中→退订按钮；已退订→重新订阅按钮
    expect(screen.getByTestId('sub-remove-11')).toBeInTheDocument();
    expect(screen.getByTestId('sub-reactivate-14')).toBeInTheDocument();
    expect(screen.queryByTestId('sub-remove-14')).toBeNull();
  });

  it('边界：标的解析失败回退「标的 #id」占位，列表不报错', async () => {
    vi.stubGlobal('fetch', makeStore({ subs: fourTypeSubs(), onQuotes: 'FAIL' }).fetchMock);

    render(<Subscriptions />);

    expect(await screen.findByTestId('sub-item-11')).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getByTestId('sub-content-12')).toHaveTextContent('标的 #7'),
    );
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('新建·主题：输入关键词提交 → POST 契约体正确 → 列表重拉', async () => {
    const store = makeStore({ subs: [] });
    vi.stubGlobal('fetch', store.fetchMock);

    render(<Subscriptions />);
    await screen.findByTestId('subs-empty');
    await userEvent.click(screen.getByTestId('subs-create-open'));

    await userEvent.type(screen.getByTestId('subs-create-keyword'), '半导体国产替代');
    await userEvent.click(screen.getByTestId('subs-create-submit'));

    await waitFor(() => expect(store.created).toHaveLength(1));
    expect(store.created[0]).toEqual({ subType: 1, subKey: '半导体国产替代' });
    // 成功后对话框关闭
    await waitFor(() => expect(screen.queryByTestId('dialog')).toBeNull());
  });

  it('新建·标的：未选标的提交给校验；经 SubjectPicker 选中后以数字主键为 subKey 提交', async () => {
    const store = makeStore({ subs: [] });
    vi.stubGlobal('fetch', store.fetchMock);

    render(<Subscriptions />);
    await screen.findByTestId('subs-empty');
    await userEvent.click(screen.getByTestId('subs-create-open'));
    await userEvent.click(screen.getByTestId('subs-create-type-2'));

    await userEvent.click(screen.getByTestId('subs-create-submit'));
    expect(screen.getByTestId('subs-create-validation')).toHaveTextContent('请先搜索并选择标的');
    expect(store.created).toHaveLength(0);

    // SubjectPicker 联想：输入 → 防抖后出选项 → 选中
    await userEvent.type(screen.getByTestId('subs-create-subject-input'), '茅台');
    const option = await screen.findByTestId('subs-create-subject-option-7');
    await userEvent.click(option);
    expect(screen.getByTestId('subs-create-subject-selected')).toHaveTextContent('SH600519');

    await userEvent.click(screen.getByTestId('subs-create-submit'));
    await waitFor(() => expect(store.created).toHaveLength(1));
    expect(store.created[0]).toEqual({ subType: 2, subKey: '7' });
  });

  it('新建·事件类型与政策主题：下拉选项与主题词输入各自校验后提交', async () => {
    const store = makeStore({ subs: [] });
    vi.stubGlobal('fetch', store.fetchMock);

    render(<Subscriptions />);
    await screen.findByTestId('subs-empty');
    await userEvent.click(screen.getByTestId('subs-create-open'));

    // 事件类型：未选提示 → 选「公告」提交
    await userEvent.click(screen.getByTestId('subs-create-type-3'));
    await userEvent.click(screen.getByTestId('subs-create-submit'));
    expect(screen.getByTestId('subs-create-validation')).toHaveTextContent('请选择事件类型');
    await userEvent.click(screen.getByTestId('subs-create-event-公告'));
    await userEvent.click(screen.getByTestId('subs-create-submit'));
    await waitFor(() => expect(store.created).toHaveLength(1));
    expect(store.created[0]).toEqual({ subType: 3, subKey: '公告' });

    // 政策主题：切换类型后输入主题词提交
    await userEvent.click(screen.getByTestId('subs-create-open'));
    await userEvent.click(screen.getByTestId('subs-create-type-4'));
    await userEvent.type(screen.getByTestId('subs-create-keyword'), '货币政策');
    await userEvent.click(screen.getByTestId('subs-create-submit'));
    await waitFor(() => expect(store.created).toHaveLength(2));
    expect(store.created[1]).toEqual({ subType: 4, subKey: '货币政策' });
  });

  it('新建·判重：已订阅同键给本地友好文案，不发请求', async () => {
    const store = makeStore({ subs: [subOf({ id: 11, subType: 1, subKey: '人工智能' })] });
    vi.stubGlobal('fetch', store.fetchMock);

    render(<Subscriptions />);
    await screen.findByTestId('sub-item-11');
    await userEvent.click(screen.getByTestId('subs-create-open'));
    await userEvent.type(screen.getByTestId('subs-create-keyword'), '人工智能');
    await userEvent.click(screen.getByTestId('subs-create-submit'));

    expect(screen.getByTestId('subs-create-validation')).toHaveTextContent('已在订阅列表中');
    expect(store.created).toHaveLength(0);
  });

  it('新建·后端错误：2001 错误文案透出到对话框（不关闭）', async () => {
    const store = makeStore({ subs: [], onCreate: 'FAIL' });
    vi.stubGlobal('fetch', store.fetchMock);

    render(<Subscriptions />);
    await screen.findByTestId('subs-empty');
    await userEvent.click(screen.getByTestId('subs-create-open'));
    await userEvent.type(screen.getByTestId('subs-create-keyword'), '人工智能');
    await userEvent.click(screen.getByTestId('subs-create-submit'));

    await waitFor(() =>
      expect(screen.getByTestId('subs-create-error')).toHaveTextContent('subType 取值 1~4'),
    );
    expect(screen.getByTestId('dialog')).toBeInTheDocument();
  });

  it('退订：二次确认 Dialog → DELETE 契约路径 → 列表重拉；取消不触发', async () => {
    const store = makeStore({ subs: [subOf({ id: 11 })] });
    vi.stubGlobal('fetch', store.fetchMock);

    render(<Subscriptions />);
    await screen.findByTestId('sub-item-11');

    await userEvent.click(screen.getByTestId('sub-remove-11'));
    expect(screen.getByTestId('dialog')).toHaveTextContent('确认退订该订阅？');
    expect(screen.getByTestId('dialog')).toHaveTextContent('人工智能');

    // 取消：不发 DELETE
    await userEvent.click(screen.getByTestId('subs-remove-confirm-cancel'));
    expect(store.deleted).toHaveLength(0);

    // 确认：DELETE /subscriptions/11
    await userEvent.click(screen.getByTestId('sub-remove-11'));
    await userEvent.click(screen.getByTestId('subs-remove-confirm-ok'));
    await waitFor(() => expect(store.deleted).toEqual([11]));
  });

  it('重新订阅：已退订条目一键 POST 同键（幂等激活）', async () => {
    const store = makeStore({ subs: [subOf({ id: 14, subType: 4, subKey: '货币政策', status: 0 })] });
    vi.stubGlobal('fetch', store.fetchMock);

    render(<Subscriptions />);
    await screen.findByTestId('sub-item-14');
    await userEvent.click(screen.getByTestId('sub-reactivate-14'));

    await waitFor(() => expect(store.created).toHaveLength(1));
    expect(store.created[0]).toEqual({ subType: 4, subKey: '货币政策' });
  });

  // ---- M26 T227：M9 页码分页语义（游标「加载更多」退役）----

  it('分页·主路径：默认请求 page=1&size=20，「共 N 条」与分页 total 一致；游标形态退役（反向断言）', async () => {
    const store = makeStore({
      pages: { 1: { items: [subOf({ id: 1, subKey: '第一页' })], total: 30 }, 2: { items: [subOf({ id: 21, subKey: '第二页' })], total: 30 } },
    });
    vi.stubGlobal('fetch', store.fetchMock);

    render(<Subscriptions />);
    expect(await screen.findByTestId('sub-item-1')).toBeInTheDocument();

    // 页码模式契约：请求带 page=1&size=20
    expect(store.listUrls[0]).toContain('page=1');
    expect(store.listUrls[0]).toContain('size=20');
    // 页头「共 N 条」与分页条 total 同源一致
    expect(screen.getByTestId('subs-total')).toHaveTextContent('共 30 条');
    expect(screen.getByTestId('subs-pagination-total')).toHaveTextContent('30');
    expect(screen.getByTestId('subs-pagination-page-indicator')).toHaveTextContent('1 / 2');
    // 游标形态清零：无「加载更多」按钮/文案、无 cursor 参数
    expect(screen.queryByTestId('subs-load-more')).toBeNull();
    expect(screen.queryByText('加载更多')).toBeNull();
    expect(store.listUrls.join(' ')).not.toContain('cursor=');
  });

  it('分页·翻页：第 2 页请求 page=2，条目整页替换不追加', async () => {
    const store = makeStore({
      pages: {
        1: { items: [subOf({ id: 1, subKey: '第一页' })], total: 30 },
        2: { items: [subOf({ id: 21, subKey: '第二页' })], total: 30 },
      },
    });
    vi.stubGlobal('fetch', store.fetchMock);

    render(<Subscriptions />);
    await screen.findByTestId('sub-item-1');

    await userEvent.click(screen.getByTestId('subs-pagination-page-2'));
    expect(await screen.findByTestId('sub-item-21')).toBeInTheDocument();
    expect(screen.queryByTestId('sub-item-1')).toBeNull(); // 替换不追加
    expect(screen.getByTestId('subs-pagination-page-indicator')).toHaveTextContent('2 / 2');
    expect(store.listUrls.at(-1)).toContain('page=2');
  });

  it('分页·条数切换：选每页 10 条 → 回第 1 页以 size=10 重查', async () => {
    const store = makeStore({
      pages: {
        1: { items: [subOf({ id: 1 })], total: 30 },
        2: { items: [subOf({ id: 21 })], total: 30 },
      },
    });
    vi.stubGlobal('fetch', store.fetchMock);

    render(<Subscriptions />);
    await screen.findByTestId('sub-item-1');
    await userEvent.click(screen.getByTestId('subs-pagination-page-2'));
    await screen.findByTestId('sub-item-21');

    await userEvent.selectOptions(screen.getByTestId('subs-pagination-size'), '10');
    expect(store.listUrls.at(-1)).toContain('page=1');
    expect(store.listUrls.at(-1)).toContain('size=10');
    expect(screen.getByTestId('subs-pagination-page-indicator')).toHaveTextContent('1 /');
  });

  it('分页·失败与重试：第 2 页失败保留第 1 页数据，重试恢复到第 2 页', async () => {
    const store = makeStore({
      pages: {
        1: { items: [subOf({ id: 1, subKey: '第一页' })], total: 30 },
        2: { items: [subOf({ id: 21, subKey: '第二页' })], total: 30 },
      },
      failFirstOnPage: 2,
    });
    vi.stubGlobal('fetch', store.fetchMock);

    render(<Subscriptions />);
    await screen.findByTestId('sub-item-1');

    await userEvent.click(screen.getByTestId('subs-pagination-page-2'));
    expect(await screen.findByTestId('subs-pagination-error')).toHaveTextContent('加载第 2 页失败');
    // 失败保留列表数据（第 1 页条目仍在）
    expect(screen.getByTestId('sub-item-1')).toBeInTheDocument();

    await userEvent.click(screen.getByTestId('subs-pagination-retry'));
    expect(await screen.findByTestId('sub-item-21')).toBeInTheDocument();
    expect(screen.getByTestId('subs-pagination-page-indicator')).toHaveTextContent('2 / 2');
    expect(screen.queryByTestId('subs-pagination-error')).toBeNull();
  });

  it('分页·空页回退：末页收缩后越界页静默落回新末页（M9 §5.3）', async () => {
    // total 45 条 size=20 → 3 页；点击第 3 页时服务端 total 收缩为 40（第 3 页已不存在）
    const store = makeStore({
      pages: {
        1: { items: [subOf({ id: 1 }), subOf({ id: 2 })], total: 45 },
        2: { items: [subOf({ id: 3 }), subOf({ id: 4 })], total: 40 },
        3: { items: [], total: 40 },
      },
    });
    vi.stubGlobal('fetch', store.fetchMock);

    render(<Subscriptions />);
    await screen.findByTestId('sub-item-1');
    expect(screen.getByTestId('subs-pagination-page-indicator')).toHaveTextContent('1 / 3');

    await userEvent.click(screen.getByTestId('subs-pagination-page-3'));
    // 空页防御：回退重发新末页（page=2），指示收敛 2/2，无报错条
    expect(await screen.findByTestId('sub-item-3')).toBeInTheDocument();
    expect(screen.getByTestId('subs-pagination-page-indicator')).toHaveTextContent('2 / 2');
    expect(screen.queryByTestId('subs-pagination-error')).toBeNull();
    expect(store.listUrls.at(-1)).toContain('page=2');
  });

  it('分页·行操作保持页码：第 2 页退订成功后以 page=2 重拉（不回第 1 页）', async () => {
    const store = makeStore({
      pages: {
        1: { items: [subOf({ id: 1 }), subOf({ id: 2 })], total: 40 },
        2: { items: [subOf({ id: 3, subKey: '第二页甲' }), subOf({ id: 4 })], total: 40 },
      },
    });
    vi.stubGlobal('fetch', store.fetchMock);

    render(<Subscriptions />);
    await screen.findByTestId('sub-item-1');
    await userEvent.click(screen.getByTestId('subs-pagination-page-2'));
    await screen.findByTestId('sub-item-3');

    await userEvent.click(screen.getByTestId('sub-remove-3'));
    await userEvent.click(screen.getByTestId('subs-remove-confirm-ok'));
    await waitFor(() => expect(store.deleted).toEqual([3]));
    // DELETE 后刷新保持当前页码
    await waitFor(() => expect(store.listUrls.at(-1)).toContain('page=2'));
  });

  it('分页·新建成功回第 1 页：第 2 页新建订阅 → page=1 骨架重查', async () => {
    const store = makeStore({
      pages: {
        1: { items: [subOf({ id: 1 })], total: 40 },
        2: { items: [subOf({ id: 3 })], total: 40 },
      },
    });
    vi.stubGlobal('fetch', store.fetchMock);

    render(<Subscriptions />);
    await screen.findByTestId('sub-item-1');
    await userEvent.click(screen.getByTestId('subs-pagination-page-2'));
    await screen.findByTestId('sub-item-3');

    await userEvent.click(screen.getByTestId('subs-create-open'));
    await userEvent.type(screen.getByTestId('subs-create-keyword'), '新主题');
    await userEvent.click(screen.getByTestId('subs-create-submit'));
    await waitFor(() => expect(store.created).toHaveLength(1));
    await waitFor(() => expect(store.listUrls.at(-1)).toContain('page=1'));
  });

  it('三态：首屏骨架 → 空态（文案 + CTA 新建订阅；分页条不渲染）', async () => {
    vi.stubGlobal('fetch', makeStore({ subs: [] }).fetchMock);

    render(<Subscriptions />);

    expect(screen.getByTestId('subs-loading')).toBeInTheDocument();
    expect(await screen.findByTestId('subs-empty')).toHaveTextContent('还没有订阅');
    expect(screen.getByTestId('subs-empty-cta')).toHaveTextContent('新建订阅');
    // total=0 分页条整体不渲染；「共 0 条」如实展示
    expect(screen.queryByTestId('subs-pagination-root')).toBeNull();
    expect(screen.getByTestId('subs-total')).toHaveTextContent('共 0 条');

    // 空态 CTA 直达新建对话框
    await userEvent.click(screen.getByTestId('subs-empty-cta'));
    expect(screen.getByTestId('dialog')).toHaveTextContent('新建订阅');
  });

  it('三态：首屏错误 + 重试恢复', async () => {
    let failFirst = true;
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        failFirst
          ? (failFirst = false, fail())
          : ok({ total: 1, items: [subOf({ id: 11 })], page: 1, size: 20 }),
      ),
    );

    render(<Subscriptions />);

    expect(await screen.findByTestId('subs-error')).toHaveTextContent('服务异常');
    // 重试恢复
    await userEvent.click(screen.getByTestId('subs-retry'));
    await waitFor(() => expect(screen.getByTestId('sub-item-11')).toBeInTheDocument());
  });
});
