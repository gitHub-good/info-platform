// 三市场切换统一控件（M29 T257，技术方案 §2.2「统一 MarketTabs 控件」+ §5 各页前端范围）：
// A股/港股/美股 三选一，受控（value + onChange，缺省市场由调用方 state 决定——A股缺省）。
// 形态沿资讯脉搏 pulse-window-tabs 同款 Button 组（role=tablist）；市场色 = 装饰性分类色
// （A股 amber / 港股 sky / 美股 violet，与资讯脉搏市场徽章一致——非方向轨，
// colorGovernance 全站断言只收口 red/green 方向色，不受影响）。五页复用，Tab 不隐藏（拍板三）。

import { Button } from '@/components/ui/button';
import { MARKET_BADGE_CLASS, MARKET_KEYS, MARKET_LABELS, type MarketKey } from '@/lib/market';

export interface MarketTabsProps {
  /** 当前市场（受控）。 */
  value: MarketKey;
  /** 切换回调（页面侧负责 URL 持久化与数据重查回第 1 态）。 */
  onChange: (market: MarketKey) => void;
}

/** 三市场 Tab（market-tab-{A_SHARE|HK|US}，testid 沿 pulse-tab-* 同款约定）。 */
export function MarketTabs({ value, onChange }: MarketTabsProps) {
  return (
    <div
      className="flex flex-wrap items-center gap-2"
      role="tablist"
      aria-label="市场"
      data-testid="market-tabs"
    >
      {MARKET_KEYS.map((key) => {
        const active = key === value;
        return (
          <Button
            key={key}
            type="button"
            role="tab"
            aria-selected={active}
            size="sm"
            variant={active ? 'default' : 'outline'}
            onClick={() => onChange(key)}
            data-testid={`market-tab-${key}`}
            className={active ? undefined : MARKET_BADGE_CLASS[key]}
          >
            {MARKET_LABELS[key]}
          </Button>
        );
      })}
    </div>
  );
}

export default MarketTabs;
