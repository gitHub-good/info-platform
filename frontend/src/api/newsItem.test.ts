import { afterEach, describe, expect, it, vi } from 'vitest';
import { listNewsLibraryPaged } from '@/api/newsItem';

// 资讯库 API 参数序列化契约（V2.4）：q/l0/l1/发布窗/入库窗 → URLSearchParams 逐参断言
// （publishedFrom/To 序列化缺位与 fetchedFrom/To 追加均在请求层单点覆盖——页面测试之外的直测锚）。

function lastQuery(fetchMock: ReturnType<typeof vi.fn>): string {
  const url = String(fetchMock.mock.calls.at(-1)?.[0] ?? '');
  const queryIndex = url.indexOf('?');
  return queryIndex >= 0 ? url.slice(queryIndex + 1) : '';
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('listNewsLibraryPaged 参数序列化', () => {
  it('基础页码模式：page/size/l0 逐参序列化，缺省过滤项不占位', async () => {
    const fetchMock = vi.fn(async () => ({
      ok: true,
      status: 200,
      json: async () => ({ code: 0, msg: 'ok', data: { items: [], total: 0, page: 2, size: 10 }, traceId: 't' }),
    }));
    vi.stubGlobal('fetch', fetchMock);

    await listNewsLibraryPaged({ l0: 'PASS', page: 2, size: 10 });

    const query = lastQuery(fetchMock);
    expect(query).toContain('l0=PASS');
    expect(query).toContain('page=2');
    expect(query).toContain('size=10');
    for (const absent of ['q=', 'l1=', 'sourceId=', 'publishedFrom=', 'fetchedFrom=']) {
      expect(query).not.toContain(absent);
    }
  });

  it('发布窗与入库窗并序列化（yyyy-MM-dd 直传，双窗可并存）', async () => {
    const fetchMock = vi.fn(async () => ({
      ok: true,
      status: 200,
      json: async () => ({ code: 0, msg: 'ok', data: { items: [], total: 0, page: 1, size: 20 }, traceId: 't' }),
    }));
    vi.stubGlobal('fetch', fetchMock);

    await listNewsLibraryPaged({
      l0: 'ALL',
      publishedFrom: '2026-09-21',
      publishedTo: '2026-09-22',
      fetchedFrom: '2026-09-22',
      fetchedTo: '2026-09-22',
      page: 1,
      size: 20,
    });

    const query = lastQuery(fetchMock);
    expect(query).toContain('publishedFrom=2026-09-21');
    expect(query).toContain('publishedTo=2026-09-22');
    expect(query).toContain('fetchedFrom=2026-09-22');
    expect(query).toContain('fetchedTo=2026-09-22');
  });

  it('组合过滤：sourceId/q trim/l1 同请求组合（AND 透传）', async () => {
    const fetchMock = vi.fn(async () => ({
      ok: true,
      status: 200,
      json: async () => ({ code: 0, msg: 'ok', data: { items: [], total: 0, page: 1, size: 20 }, traceId: 't' }),
    }));
    vi.stubGlobal('fetch', fetchMock);

    await listNewsLibraryPaged({
      sourceId: 7,
      q: '  降息  ',
      l0: 'PASS',
      l1: '银行',
      page: 1,
      size: 20,
    });

    const query = lastQuery(fetchMock);
    expect(query).toContain('sourceId=7');
    expect(query).toContain('q=');
    expect(decodeURIComponent(query)).toContain('q=降息');
    expect(decodeURIComponent(query)).toContain('l1=银行');
  });
});
