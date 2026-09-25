// M12 T94：分区子端点取数层——URL 组装契约（方案 §4.1）。
// page 必带；公告/事件 size 可选（缺省不发）；新闻端点绝不带 size（契约出现即 400）。

import { afterEach, describe, expect, it, vi } from 'vitest';
import { API_BASE_URL } from '@/api/http';
import {
  fetchAnnouncementPage,
  fetchEventPage,
  fetchNewsPage,
} from '@/api/subjectSection';

/** 构造类 Response 的 mock（只暴露 request() 用到的 ok/status/json）。 */
function mockResponse(status: number, body: unknown) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as unknown as Response;
}

function lastUrl(fetchMock: ReturnType<typeof vi.fn>): string {
  return String(fetchMock.mock.calls.at(-1)?.[0]);
}

function stubFetch() {
  const fetchMock = vi.fn(async () =>
    mockResponse(200, {
      code: 0,
      msg: 'ok',
      data: { items: [], page: 1, size: 10, sourceStatus: 'ok' },
    }),
  );
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('subjectSection 分区子端点取数（M12 §4.1 契约）', () => {
  it('公告：page 必带；size 可选，缺省不发送（走运行时 announcePageSize）', async () => {
    const fetchMock = stubFetch();

    await fetchAnnouncementPage(1, 2);
    expect(lastUrl(fetchMock)).toBe(
      `${API_BASE_URL}/subjects/1/announcements?page=2`,
    );

    await fetchAnnouncementPage(1, 3, { size: 20 });
    expect(lastUrl(fetchMock)).toBe(
      `${API_BASE_URL}/subjects/1/announcements?page=3&size=20`,
    );
  });

  it('事件：page 必带；size 可选（缺省走端点缺省 10）', async () => {
    const fetchMock = stubFetch();

    await fetchEventPage(7, 4);
    expect(lastUrl(fetchMock)).toBe(`${API_BASE_URL}/subjects/7/events?page=4`);

    await fetchEventPage(7, 5, { size: 20 });
    expect(lastUrl(fetchMock)).toBe(`${API_BASE_URL}/subjects/7/events?page=5&size=20`);
  });

  it('新闻：page=源页码且绝不携带 size（端点契约拒 size）', async () => {
    const fetchMock = stubFetch();

    await fetchNewsPage(7, 2);
    expect(lastUrl(fetchMock)).toBe(`${API_BASE_URL}/subjects/7/news?page=2`);
    expect(lastUrl(fetchMock)).not.toContain('size');
  });
});
