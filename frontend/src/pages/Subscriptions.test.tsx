// 订阅管理页测试（体检 P1-3）：列表渲染（类型徽章/标的解析/状态）/ 新建（类型表单+校验+判重）/ 退订确认 /
// 重新订阅 / 游标分页 / 三态（骨架、空态 CTA、错误重试）。
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
  /** 首屏订阅列表；'FAIL' 强制失败。 */
  subs?: SubscriptionView[] | 'FAIL';
  /** 翻页序列（cursor 请求按次序返回，末页重复）。 */
  morePages?: Array<{ items: SubscriptionView[]; nextCursor: number | null }>;
  /** POST /subscriptions 响应工厂；'FAIL' 强制失败。 */
  onCreate?: (() => ReturnType<typeof ok>) | 'FAIL';
  /** DELETE 响应工厂；'FAIL' 强制失败。 */
  onDelete?: (() => ReturnType<typeof ok>) | 'FAIL';
  /** /subjects/quotes 响应工厂；'FAIL' 强制失败。 */
  onQuotes?: (() => ReturnType<typeof ok>) | 'FAIL';
}

function makeStore(opts: StoreOpts = {}) {
  let listCalls = 0;
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
    if (/\/subscriptions(\?.*)?$/.test(path) && method === 'GET') {
      if (opts.subs === 'FAIL') return fail();
      // 首页固定返回 subs；提供 morePages 时首页带 nextCursor 触发「加载更多」
      if (listCalls === 0) {
        listCalls += 1;
        return ok({ items: opts.subs ?? [], nextCursor: opts.morePages ? 99 : null });
      }
      const pages = opts.morePages ?? [];
      const idx = Math.min(listCalls - 1, pages.length - 1);
      listCalls += 1;
      return ok(pages[idx]);
    }
    return fail();
  });
  return { fetchMock, created, deleted };
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

  it('游标分页：「加载更多」带 cursor 追加；末页无按钮', async () => {
    vi.stubGlobal(
      'fetch',
      makeStore({
        subs: [subOf({ id: 1, subKey: '第一页' })],
        morePages: [{ items: [subOf({ id: 2, subKey: '第二页' })], nextCursor: null }],
      }).fetchMock,
    );

    render(<Subscriptions />);
    await screen.findByTestId('sub-item-1');

    await userEvent.click(screen.getByTestId('subs-load-more'));
    await waitFor(() => expect(screen.getByTestId('sub-item-2')).toBeInTheDocument());
    expect(screen.getByTestId('sub-item-1')).toBeInTheDocument();
    // 末页（nextCursor=null）不再渲染加载更多
    await waitFor(() => expect(screen.queryByTestId('subs-load-more')).toBeNull());
  });

  it('三态：首屏骨架 → 空态（文案 + CTA 新建订阅）', async () => {
    vi.stubGlobal('fetch', makeStore({ subs: [] }).fetchMock);

    render(<Subscriptions />);

    expect(screen.getByTestId('subs-loading')).toBeInTheDocument();
    expect(await screen.findByTestId('subs-empty')).toHaveTextContent('还没有订阅');
    expect(screen.getByTestId('subs-empty-cta')).toHaveTextContent('新建订阅');

    // 空态 CTA 直达新建对话框
    await userEvent.click(screen.getByTestId('subs-empty-cta'));
    expect(screen.getByTestId('dialog')).toHaveTextContent('新建订阅');
  });

  it('三态：首屏错误 + 重试恢复', async () => {
    let failFirst = true;
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => (failFirst ? (failFirst = false, fail()) : ok({ items: [subOf({ id: 11 })], nextCursor: null }))),
    );

    render(<Subscriptions />);

    expect(await screen.findByTestId('subs-error')).toHaveTextContent('服务异常');
    // 重试恢复
    await userEvent.click(screen.getByTestId('subs-retry'));
    await waitFor(() => expect(screen.getByTestId('sub-item-11')).toBeInTheDocument());
  });
});
