// 关键词高亮切分逻辑（T43）：按关键词集合构造「捕获分组 + 大小写不敏感」正则切分文本。
// 纯函数置 lib（组件文件仅导出组件，避免 only-export-components 告警）；
// 安全性：切分后的命中段由 React 以文本节点渲染（自动转义），无 innerHTML 拼接。

/** 正则元字符转义（关键词作为字面量参与匹配）。 */
function escapeRegExp(raw: string): string {
  return raw.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** 规整关键词：去空白、去重、按长度降序（多词重叠时优先长词命中）。 */
function normalizeKeywords(keywords: string[]): string[] {
  const trimmed = keywords
    .map((k) => k.trim())
    .filter((k) => k.length > 0);
  return Array.from(new Set(trimmed)).sort((a, b) => b.length - a.length);
}

/** 切分结果段（hit = 命中关键词段）。 */
export interface TextPart {
  hit: boolean;
  value: string;
}

/**
 * 按关键词切分文本（大小写不敏感，关键词按字面量匹配）。
 * 无有效关键词或无命中时返回单段原文。
 */
export function splitByKeywords(text: string, keywords: string[]): TextPart[] {
  const normalized = normalizeKeywords(keywords);
  if (normalized.length === 0) return [{ hit: false, value: text }];
  const pattern = new RegExp(`(${normalized.map(escapeRegExp).join('|')})`, 'gi');
  const parts: TextPart[] = [];
  let lastIndex = 0;
  for (const match of text.matchAll(pattern)) {
    const index = match.index ?? 0;
    if (index > lastIndex) parts.push({ hit: false, value: text.slice(lastIndex, index) });
    parts.push({ hit: true, value: match[0] });
    lastIndex = index + match[0].length;
  }
  if (lastIndex < text.length) parts.push({ hit: false, value: text.slice(lastIndex) });
  return parts;
}
