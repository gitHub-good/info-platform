// 页面级 market 状态 + URL 持久化（M29 T257 五页统一：行业热度/行业主线/资讯脉搏/事件流/全市场推荐）。
// 挂载读一次 ?market=（#/xxx?market=HK 刷新保持）；setMarket 同步写回 hash（路径不变，
// App 按 path 渲染同页不重挂载）；hashchange 监听外部深链变更（同值 no-op）。

import { useCallback, useEffect, useState } from 'react';
import { currentRoute } from '@/lib/navigation';
import { marketOfRoute, setMarketParam, type MarketKey } from '@/lib/market';

/** market 状态与切换器：切换即写 URL 并由页面 effect 触发数据重查回第 1 态。 */
export function useMarketParam(): [MarketKey, (market: MarketKey) => void] {
  const [market, setMarketState] = useState<MarketKey>(() => marketOfRoute(currentRoute()));

  useEffect(() => {
    const onHashChange = () => setMarketState(marketOfRoute(currentRoute()));
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  const setMarket = useCallback((next: MarketKey) => {
    setMarketState(next);
    setMarketParam(next);
  }, []);

  return [market, setMarket];
}
