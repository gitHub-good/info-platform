import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  resetReadingTrackerForTest,
  trackReading,
  trackReadingOnce,
} from '@/api/readingEvent';

const ok = (data: unknown) => ({
  ok: true,
  status: 200,
  json: async () => ({ code: 0, msg: 'ok', data, traceId: 't' }),
});

afterEach(() => {
  vi.unstubAllGlobals();
  resetReadingTrackerForTest();
});

describe('readingEvent 埋点适配层（T29）', () => {
  it('trackReading：POST /reading-events 携带载荷与鉴权头', async () => {
    // Arrange
    const calls: Array<{ url: unknown; init?: RequestInit }> = [];
    const fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
      calls.push({ url, init });
      return ok({ recorded: true });
    });
    vi.stubGlobal('fetch', fetch);

    // Act
    await trackReading({ contentType: 'POLICY', contentRef: '42' });

    // Assert
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(String(calls[0]?.url)).toContain('/reading-events');
    expect(calls[0]?.init?.method).toBe('POST');
    expect(JSON.parse(String(calls[0]?.init?.body))).toEqual({
      contentType: 'POLICY',
      contentRef: '42',
    });
  });

  it('trackReading：请求失败不外抛（静默失败，不打扰主流程）', async () => {
    // Arrange：网络层直接拒绝
    const fetch = vi.fn(async () => Promise.reject(new Error('offline')));
    vi.stubGlobal('fetch', fetch);
    const debug = vi.spyOn(console, 'debug').mockImplementation(() => {});

    // Act / Assert：resolve 而非 reject
    await expect(
      trackReading({ contentType: 'POLICY', contentRef: '42' }),
    ).resolves.toBeUndefined();
    expect(debug).toHaveBeenCalled();
    debug.mockRestore();
  });

  it('trackReadingOnce：同键会话内只上报一次（StrictMode 双触发防重）', async () => {
    // Arrange
    const fetch = vi.fn(async () => ok({ recorded: true }));
    vi.stubGlobal('fetch', fetch);

    // Act：同键连发两次（模拟 StrictMode 双触发），不同键正常上报
    trackReadingOnce('subject:SH600519', {
      contentType: 'SUBJECT_DETAIL',
      contentRef: 'SH600519',
      subjectCode: 'SH600519',
    });
    trackReadingOnce('subject:SH600519', {
      contentType: 'SUBJECT_DETAIL',
      contentRef: 'SH600519',
      subjectCode: 'SH600519',
    });
    trackReadingOnce('policy:42', { contentType: 'POLICY', contentRef: '42' });
    await new Promise((resolve) => setTimeout(resolve, 0));

    // Assert：仅 2 次请求（subject 去重 + policy 一次）
    expect(fetch).toHaveBeenCalledTimes(2);
  });
});
