// 命中关键词高亮组件（T43，UI 方案 §5.2 / §3.5 交互 2）。
//
// 安全实现：不做任何 HTML 拼接（不用 dangerouslySetInnerHTML），切分逻辑在
// src/lib/highlight.ts（纯函数），命中段包 <mark>、其余段以 React 文本节点渲染——
// 自动转义天然免疫 XSS。样式：bg-amber-500/25 text-amber-200（去 mark 默认黄底黑字，
// UI 方案 §5.1 对暗底对比度 ≥10:1）。

import { Fragment } from 'react';
import { splitByKeywords } from '@/lib/highlight';

interface KeywordHighlightProps {
  /** 待高亮文本（空值渲染 null，由调用方控制占位）。 */
  text: string | null | undefined;
  /** 命中关键词集合（大小写不敏感；空串/空白项忽略）。 */
  keywords: string[];
}

/** 命中词渲染为自定义样式 <mark>，其余文本原样输出（安全拼接，无 innerHTML）。 */
export function KeywordHighlight({ text, keywords }: KeywordHighlightProps) {
  if (!text) return null;
  const parts = splitByKeywords(text, keywords);
  if (parts.length === 1 && !parts[0].hit) return <>{text}</>;
  return (
    <>
      {parts.map((part, index) =>
        part.hit ? (
          <mark key={index} className="rounded-sm bg-amber-500/25 px-0.5 text-amber-200">
            {part.value}
          </mark>
        ) : (
          <Fragment key={index}>{part.value}</Fragment>
        ),
      )}
    </>
  );
}
