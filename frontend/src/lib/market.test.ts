import { act, cleanup, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { useMarketParam } from '@/hooks/useMarketParam';
import {
  marketOf,
  marketOfRoute,
  MARKET_BADGE_CLASS,
  MARKET_KEYS,
  MARKET_LABELS,
  setMarketParam,
} from '@/lib/market';

// 三市场维度单点单测（M29 T257）：线格式解析（非法回 A 股）、URL ?market= 读写、
// useMarketParam 挂载读初值 + setMarket 写回 hash + 外部 hashchange 同步。

afterEach(() => {
  cleanup();
  window.location.hash = '';
});

describe('market 线格式与常量（lib/market）', () => {
  it('marketOf：A_SHARE/HK/US 直通，非法/缺省回 A 股（URL 手输容错）', () => {
    expect(marketOf('A_SHARE')).toBe('A_SHARE');
    expect(marketOf('HK')).toBe('HK');
    expect(marketOf('US')).toBe('US');
    expect(marketOf('hk')).toBe('A_SHARE');
    expect(marketOf('INDEX')).toBe('A_SHARE');
    expect(marketOf(null)).toBe('A_SHARE');
    expect(marketOf(undefined)).toBe('A_SHARE');
  });

  it('marketOfRoute：#/xxx?market=HK 解析；无参数缺省 A 股', () => {
    expect(marketOfRoute('/industry-heat?market=HK')).toBe('HK');
    expect(marketOfRoute('/events?focus=9&market=US')).toBe('US');
    expect(marketOfRoute('/industry-mainline')).toBe('A_SHARE');
    expect(marketOfRoute('/market-top?market=XX')).toBe('A_SHARE');
  });

  it('常量：三键展示序 + 展示名 + 装饰性分类徽章色（A股 amber / 港股 sky / 美股 violet）', () => {
    expect(MARKET_KEYS).toEqual(['A_SHARE', 'HK', 'US']);
    expect(MARKET_LABELS).toEqual({ A_SHARE: 'A股', HK: '港股', US: '美股' });
    expect(MARKET_BADGE_CLASS.A_SHARE).toContain('amber');
    expect(MARKET_BADGE_CLASS.HK).toContain('sky');
    expect(MARKET_BADGE_CLASS.US).toContain('violet');
  });
});

describe('setMarketParam：URL ?market= 持久化', () => {
  it('写回 hash 且保留其余查询参数（industry/focus 等）', () => {
    window.location.hash = `#/industry-heat?industry=${encodeURIComponent('电子')}`;
    setMarketParam('HK');
    expect(window.location.hash).toBe(
      `#/industry-heat?industry=${encodeURIComponent('电子')}&market=HK`,
    );
  });

  it('同值不写（免抖动）；无查询串路由直接追加', () => {
    window.location.hash = '#/events?market=HK';
    setMarketParam('HK');
    expect(window.location.hash).toBe('#/events?market=HK');

    window.location.hash = '#/market-top';
    setMarketParam('US');
    expect(window.location.hash).toBe('#/market-top?market=US');
  });
});

describe('useMarketParam：页面级 market 状态（五页统一）', () => {
  it('挂载读 ?market= 初值；setMarket 同步写回 hash', async () => {
    window.location.hash = '#/industry-mainline?market=US';
    const { result } = renderHook(() => useMarketParam());
    expect(result.current[0]).toBe('US');

    act(() => result.current[1]('HK'));
    expect(result.current[0]).toBe('HK');
    expect(window.location.hash).toBe('#/industry-mainline?market=HK');
  });

  it('无参数挂载缺省 A 股（既有行为零回归）', () => {
    window.location.hash = '#/news-pulse';
    const { result } = renderHook(() => useMarketParam());
    expect(result.current[0]).toBe('A_SHARE');
  });
});
