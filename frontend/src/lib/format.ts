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
  return changePct > 0 ? 'text-red-500' : 'text-green-500';
}
