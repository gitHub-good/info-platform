// 三市场维度单点（M29 T257，技术方案 §2.2/§5——五页 MarketTabs 共用）：
// market 线格式 = 后端 Market.name()（A_SHARE/HK/US，方案 §5 统一约定）；展示名、
// 装饰性分类徽章色与 URL ?market= 读写在此单点导出，页面不散落复制。
// 徽章色与资讯脉搏市场徽章同源（A股 amber / 港股 sky / 美股 violet）——装饰性分类色，
// 非方向轨，不在 M26 T229 colorGovernance 收口范围。

import { currentRoute, queryOf } from '@/lib/navigation';

/** 市场键（后端 Market.name() 线格式；方案 §5：market ∈ {A_SHARE, HK, US}）。 */
export type MarketKey = 'A_SHARE' | 'HK' | 'US';

/** 缺省市场（A 股——方案 §5 统一约定：缺省 = A_SHARE，既有调用零破坏）。 */
export const DEFAULT_MARKET: MarketKey = 'A_SHARE';

/** 三市场展示序（MarketTabs 顺序）。 */
export const MARKET_KEYS: readonly MarketKey[] = ['A_SHARE', 'HK', 'US'];

/** 展示名（与资讯脉搏四桶口径一致）。 */
export const MARKET_LABELS: Record<MarketKey, string> = {
  A_SHARE: 'A股',
  HK: '港股',
  US: '美股',
};

/** 装饰性分类徽章色（outline 形态——A股 amber / 港股 sky / 美股 violet，NewsPulse 市场徽章同款）。 */
export const MARKET_BADGE_CLASS: Record<MarketKey, string> = {
  A_SHARE: 'border-amber-500/40 text-amber-500',
  HK: 'border-sky-500/30 text-sky-400',
  US: 'border-violet-500/30 text-violet-400',
};

/** 线格式解析（非法/缺省回 A 股——URL 手输容错）。 */
export function marketOf(value: string | null | undefined): MarketKey {
  return value === 'HK' || value === 'US' ? value : 'A_SHARE';
}

/** 当前 hash 路由的 ?market= 参数（缺省 A 股）。 */
export function marketOfRoute(route: string): MarketKey {
  return marketOf(queryOf(route).get('market'));
}

/** market 写回 hash 查询串（保留其余参数；路径不变不触发页面重挂载；同值不写免抖动）。 */
export function setMarketParam(market: MarketKey): void {
  if (typeof window === 'undefined') return;
  const route = currentRoute();
  const query = queryOf(route);
  if (query.get('market') === market) return;
  query.set('market', market);
  const path = route.split('?')[0];
  window.location.hash = `#${path}?${query.toString()}`;
}
