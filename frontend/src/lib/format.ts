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

/** ISO 时间 → 'YYYY-MM-DD HH:mm'（本地时区，通知时间等轻量展示）；空/非法回 '--' */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '--';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '--';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
