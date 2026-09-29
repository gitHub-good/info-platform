// 金融数值展示格式化（统一口径，避免散落魔法逻辑）

/** 金额（元）→ 亿 / 万 展示 */
export function formatMoney(yuan: number | null | undefined): string {
  if (yuan == null || Number.isNaN(yuan)) return '--';
  const abs = Math.abs(yuan);
  if (abs >= 1e8) return `${(yuan / 1e8).toFixed(2)}亿`;
  if (abs >= 1e4) return `${(yuan / 1e4).toFixed(2)}万`;
  return yuan.toFixed(0);
}

/** 成交量（股）→ 亿股 / 万股 展示 */
export function formatVolume(shares: number | null | undefined): string {
  if (shares == null || Number.isNaN(shares)) return '--';
  const abs = Math.abs(shares);
  if (abs >= 1e8) return `${(shares / 1e8).toFixed(2)}亿股`;
  if (abs >= 1e4) return `${(shares / 1e4).toFixed(2)}万股`;
  return `${shares}股`;
}

/** 百分比展示，默认两位小数 */
export function formatPct(value: number | null | undefined, digits = 2): string {
  if (value == null || Number.isNaN(value)) return '--';
  return `${value.toFixed(digits)}%`;
}

/** 价格展示，默认两位小数 */
export function formatPrice(value: number | null | undefined, digits = 2): string {
  if (value == null || Number.isNaN(value)) return '--';
  return value.toFixed(digits);
}

// —— 市场原币符号（M29 T257，REQ-20260929-23 拍板六：原币计价不折算，仅展示符号不换汇） ——

/** 市场原币符号：A ¥ / 港 HK$ / 美 $（入参兼容 market 键与 currency 码两线格式）。 */
export function currencySymbolOf(marketOrCurrency: string | null | undefined): string {
  const value = marketOrCurrency ?? '';
  if (value === 'HK' || value === 'HKD') return 'HK$';
  if (value === 'US' || value === 'USD') return '$';
  return '¥';
}

/** 原币金额展示（符号前缀 + 亿/万；不换汇，币种随数据来源市场）。 */
export function formatMoneyInCurrency(
  value: number | null | undefined,
  marketOrCurrency: string | null | undefined,
): string {
  if (value == null || Number.isNaN(value)) return '--';
  return `${currencySymbolOf(marketOrCurrency)}${formatMoney(value)}`;
}

/** 通用数值展示 */
export function formatNumber(value: number | null | undefined, digits = 2): string {
  if (value == null || Number.isNaN(value)) return '--';
  return value.toFixed(digits);
}

/** 涨跌幅配色（A 股惯例：涨红跌绿） */
export function changeColorClass(changePct: number | null | undefined): string {
  if (changePct == null || changePct === 0) return 'text-foreground';
  return directionTextClass(changePct > 0 ? 'up' : 'down');
}

// —— 语义色两轨收口（M26 T229，UI V3.0 方案 §4 项 2 / §5：lib/format.ts 单点导出）——
//
// 两轨分层命名，页面/组件禁止散落裸写双轨色值类名（red/green 裸写由 colorGovernance.test 清零断言）：
// - 市场方向轨（红涨绿跌，red/green 系）：价格/涨跌幅/多空方向等市场语义——changeColorClass /
//   directionTextClass / directionToneClass；
// - 系统状态轨（emerald/amber/rose）：成功/警告/失败等系统运行状态——statusToneClass / statusTextClass。
// 类型徽章多色（sky/violet 等装饰性分类色）与榜单名次变动色（amber/rose，防与价格涨跌混淆）
// 不属于任一轨，不在收口范围。

/** 市场方向（涨/跌/平）——方向轨入参。 */
export type MarketDirection = 'up' | 'down' | 'flat';

/** 市场方向纯文本色（A 股惯例红涨绿跌；平 = 前景灰）。 */
export function directionTextClass(direction: MarketDirection): string {
  if (direction === 'up') return 'text-red-500';
  if (direction === 'down') return 'text-green-500';
  return 'text-foreground';
}

/** 市场方向徽章 tone（低饱和底 + 亮字；平 = 灰底）。 */
export function directionToneClass(direction: MarketDirection): string {
  if (direction === 'up') return 'bg-red-500/15 text-red-500';
  if (direction === 'down') return 'bg-green-500/15 text-green-500';
  return 'bg-muted text-muted-foreground';
}

/** 系统状态语义（成功/警告/失败/中性）——状态轨入参。 */
export type SemanticStatus = 'success' | 'warning' | 'failure' | 'neutral';

/** 系统状态徽章 tone（emerald/amber/rose/灰，低饱和底 + 亮字）。 */
export function statusToneClass(status: SemanticStatus): string {
  switch (status) {
    case 'success':
      return 'bg-emerald-500/15 text-emerald-400';
    case 'warning':
      return 'bg-amber-500/15 text-amber-400';
    case 'failure':
      return 'bg-rose-500/15 text-rose-400';
    default:
      return 'bg-muted text-muted-foreground';
  }
}

/** 系统状态纯文本色（无底色场景：连通性标注/保存成功提示/错误提示）。 */
export function statusTextClass(status: SemanticStatus): string {
  switch (status) {
    case 'success':
      return 'text-emerald-400';
    case 'warning':
      return 'text-amber-400';
    case 'failure':
      return 'text-rose-400';
    default:
      return 'text-muted-foreground';
  }
}

// —— 行业热力图色阶（M27 T245，方案 §4.6 ①：色深 = |pctDay| 线性映射 ±5% 封顶，方向轨单点导出） ——

/** 色阶封顶幅度（|pctDay| ≥ 5% 即最深档，图例两端）。 */
export const HEAT_SCALE_MAX_PCT = 5;

/** 方向轨基色（与 directionTextClass 的 red-500 / green-500 同源——#ef4444 / #22c55e）。 */
const HEAT_UP_RGB: readonly [number, number, number] = [239, 68, 68];
const HEAT_DOWN_RGB: readonly [number, number, number] = [34, 197, 94];
/** 暗色页背景合成基色（index.css .dark --background oklch(0.13) ≈ #0d0d0d）。 */
const HEAT_PAGE_BASE_RGB: readonly [number, number, number] = [13, 13, 13];
/** 色深线性区间：|pct| ∈ (0,5] → alpha ∈ (0.12,0.9]（零轴透明 = 无方向）。 */
const HEAT_ALPHA_MIN = 0.12;
const HEAT_ALPHA_MAX = 0.9;
/** 文字深浅切换阈值：合成底色相对亮度高于此值用深字，否则白字。 */
const HEAT_TEXT_LUMA_THRESHOLD = 0.45;

/** 热力图格色阶（背景色 + 文字色）。 */
export interface HeatCellShade {
  /** 格子背景色（CSS color 串；零轴 transparent）。 */
  backgroundColor: string;
  /** 格子文字色（深底白字 / 浅底深字自动切换）。 */
  color: string;
}

/** 相对亮度（W3C sRGB 公式，分量 0~255）。 */
function relativeLuma(rgb: readonly [number, number, number]): number {
  const lin = (c: number) => {
    const s = c / 255;
    return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
  };
  return 0.2126 * lin(rgb[0]) + 0.7152 * lin(rgb[1]) + 0.0722 * lin(rgb[2]);
}

/** 热力图格色阶：红涨绿跌（方向轨同源基色）+ 色深随 |pctDay| 线性（±HEAT_SCALE_MAX_PCT 封顶）+ 文字深浅切换。 */
export function heatCellShade(pctDay: number | null | undefined): HeatCellShade {
  if (pctDay == null || Number.isNaN(pctDay) || pctDay === 0) {
    return { backgroundColor: 'transparent', color: 'inherit' };
  }
  const clamped = Math.max(-HEAT_SCALE_MAX_PCT, Math.min(HEAT_SCALE_MAX_PCT, pctDay));
  const intensity = Math.abs(clamped) / HEAT_SCALE_MAX_PCT;
  const alpha = HEAT_ALPHA_MIN + (HEAT_ALPHA_MAX - HEAT_ALPHA_MIN) * intensity;
  const tint = clamped > 0 ? HEAT_UP_RGB : HEAT_DOWN_RGB;
  // 文字色按「基色叠加暗底」的合成亮度切换（jsdom 无计算样式，纯函数可测）
  const blended = tint.map((c, i) => c * alpha + HEAT_PAGE_BASE_RGB[i] * (1 - alpha)) as [
    number,
    number,
    number,
  ];
  const color = relativeLuma(blended) > HEAT_TEXT_LUMA_THRESHOLD ? '#0a0a0a' : '#ffffff';
  return { backgroundColor: `rgba(${tint.join(', ')}, ${alpha})`, color };
}

/** 色阶图例渐变条（-5% 绿 → 0 透明 → +5% 红；纯 CSS linear-gradient，零图表库依赖）。 */
export function heatScaleGradientCss(): string {
  const down = HEAT_DOWN_RGB.join(', ');
  const up = HEAT_UP_RGB.join(', ');
  return `linear-gradient(to right, rgba(${down}, ${HEAT_ALPHA_MAX}), rgba(${down}, 0) 50%, rgba(${up}, 0) 50%, rgba(${up}, ${HEAT_ALPHA_MAX}))`;
}

/** ISO 时间 → 'YYYY-MM-DD HH:mm'（本地时区，通知时间等轻量展示）；空/非法回 '--' */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '--';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '--';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
