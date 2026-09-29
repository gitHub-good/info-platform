import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MarketTabs } from '@/components/common/MarketTabs';

// MarketTabs 统一控件单测（M29 T257，方案 §2.2「统一 MarketTabs 控件」+ §5 各页前端范围）：
// 三市场三选一（受控 value+onChange）、缺省由调用方 state 决定、testid 沿 pulse-tab-* 同款、
// 市场色 = 装饰性分类色（A股 amber / 港股 sky / 美股 violet——非方向轨，colorGovernance 不收口）。

afterEach(() => {
  cleanup();
});

describe('MarketTabs 三市场切换统一控件（T257）', () => {
  it('三 Tab 齐备不隐藏（A股/港股/美股，testid 沿 pulse-tab-* 同款）', () => {
    render(<MarketTabs value="A_SHARE" onChange={() => undefined} />);

    expect(screen.getByTestId('market-tabs')).toBeInTheDocument();
    expect(screen.getByTestId('market-tab-A_SHARE')).toHaveTextContent('A股');
    expect(screen.getByTestId('market-tab-HK')).toHaveTextContent('港股');
    expect(screen.getByTestId('market-tab-US')).toHaveTextContent('美股');
  });

  it('受控：aria-selected 标注当前市场，点击回调对应 MarketKey', async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<MarketTabs value="HK" onChange={onChange} />);

    expect(screen.getByTestId('market-tab-HK')).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByTestId('market-tab-A_SHARE')).toHaveAttribute('aria-selected', 'false');
    expect(screen.getByTestId('market-tab-US')).toHaveAttribute('aria-selected', 'false');

    await user.click(screen.getByTestId('market-tab-US'));
    expect(onChange).toHaveBeenCalledWith('US');
    await user.click(screen.getByTestId('market-tab-A_SHARE'));
    expect(onChange).toHaveBeenCalledWith('A_SHARE');
  });

  it('非激活 Tab 携带市场装饰性分类色（A股 amber / 港股 sky / 美股 violet，与资讯脉搏市场徽章一致）', () => {
    render(<MarketTabs value="A_SHARE" onChange={() => undefined} />);

    expect(screen.getByTestId('market-tab-HK').className).toContain('text-sky-400');
    expect(screen.getByTestId('market-tab-US').className).toContain('text-violet-400');
    // 激活 Tab 走 default 实底，不带市场分类色；且无 red/green 方向轨裸写（colorGovernance 同口径）
    expect(screen.getByTestId('market-tab-A_SHARE').className).not.toContain('text-amber-500');
    for (const key of ['A_SHARE', 'HK', 'US'] as const) {
      expect(screen.getByTestId(`market-tab-${key}`).className).not.toMatch(/(?:text|bg|border)-(?:red|green)-/);
    }
  });
});
