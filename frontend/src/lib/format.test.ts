// 涨跌/状态色语义函数单测（M26 T229，UI V3.0 §4 项 2 两轨收口）：
// 市场方向色（A 股红涨绿跌 red/green 系）与系统状态色（emerald/amber/rose）两轨分层，
// lib/format.ts 单点导出——页面禁止裸写双轨色值类名（清零断言见 colorGovernance.test.ts）。
import { describe, expect, it } from 'vitest';
import {
  changeColorClass,
  currencySymbolOf,
  directionTextClass,
  directionToneClass,
  formatMoneyInCurrency,
  statusTextClass,
  statusToneClass,
} from '@/lib/format';

describe('市场方向色（红涨绿跌 red/green 系）', () => {
  it('directionTextClass：涨红 / 跌绿 / 平前景灰', () => {
    expect(directionTextClass('up')).toBe('text-red-500');
    expect(directionTextClass('down')).toBe('text-green-500');
    expect(directionTextClass('flat')).toBe('text-foreground');
  });

  it('directionToneClass：徽章低饱和底 + 亮字（涨红 / 跌绿 / 平灰）', () => {
    expect(directionToneClass('up')).toBe('bg-red-500/15 text-red-500');
    expect(directionToneClass('down')).toBe('bg-green-500/15 text-green-500');
    expect(directionToneClass('flat')).toBe('bg-muted text-muted-foreground');
  });

  it('changeColorClass：数值涨跌配色沿方向轨（涨红/跌绿/零与空前景灰）', () => {
    expect(changeColorClass(1.23)).toBe('text-red-500');
    expect(changeColorClass(-0.5)).toBe('text-green-500');
    expect(changeColorClass(0)).toBe('text-foreground');
    expect(changeColorClass(null)).toBe('text-foreground');
    expect(changeColorClass(undefined)).toBe('text-foreground');
  });
});

describe('系统状态色（emerald/amber/rose 系）', () => {
  it('statusToneClass：成功/警告/失败/中性四态徽章', () => {
    expect(statusToneClass('success')).toBe('bg-emerald-500/15 text-emerald-400');
    expect(statusToneClass('warning')).toBe('bg-amber-500/15 text-amber-400');
    expect(statusToneClass('failure')).toBe('bg-rose-500/15 text-rose-400');
    expect(statusToneClass('neutral')).toBe('bg-muted text-muted-foreground');
  });

  it('statusTextClass：纯文本色（无底色场景：连通性标注/保存提示/错误提示）', () => {
    expect(statusTextClass('success')).toBe('text-emerald-400');
    expect(statusTextClass('warning')).toBe('text-amber-400');
    expect(statusTextClass('failure')).toBe('text-rose-400');
    expect(statusTextClass('neutral')).toBe('text-muted-foreground');
  });
});

describe('市场原币符号（M29 T257 拍板六：原币计价不折算）', () => {
  it('currencySymbolOf：A ¥ / 港 HK$ / 美 $（market 键与 currency 码两线格式兼容）', () => {
    expect(currencySymbolOf('A_SHARE')).toBe('¥');
    expect(currencySymbolOf('CNY')).toBe('¥');
    expect(currencySymbolOf('HK')).toBe('HK$');
    expect(currencySymbolOf('HKD')).toBe('HK$');
    expect(currencySymbolOf('US')).toBe('$');
    expect(currencySymbolOf('USD')).toBe('$');
    expect(currencySymbolOf(null)).toBe('¥');
    expect(currencySymbolOf(undefined)).toBe('¥');
  });

  it('formatMoneyInCurrency：符号前缀 + 亿/万口径（不换汇）；空值回 --', () => {
    expect(formatMoneyInCurrency(2_500_000_000_000, 'CNY')).toBe('¥25000.00亿');
    expect(formatMoneyInCurrency(49_356_000_000, 'HKD')).toBe('HK$493.56亿');
    expect(formatMoneyInCurrency(1_234_000, 'US')).toBe('$123.40万');
    expect(formatMoneyInCurrency(null, 'USD')).toBe('--');
    expect(formatMoneyInCurrency(Number.NaN, 'HK')).toBe('--');
  });
});

describe('两轨分层（禁混用）', () => {
  it('方向轨不出现状态色板，状态轨不出现涨跌色板', () => {
    for (const cls of [
      directionTextClass('up'),
      directionTextClass('down'),
      directionToneClass('up'),
      directionToneClass('down'),
      changeColorClass(1),
      changeColorClass(-1),
    ]) {
      expect(cls).not.toMatch(/emerald|rose|amber/);
    }
    for (const cls of [
      statusToneClass('success'),
      statusToneClass('warning'),
      statusToneClass('failure'),
      statusTextClass('success'),
      statusTextClass('failure'),
    ]) {
      expect(cls).not.toMatch(/red-|green-/);
    }
  });
});
