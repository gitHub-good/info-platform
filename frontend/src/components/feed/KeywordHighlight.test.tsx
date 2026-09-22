// KeywordHighlight 单测（T43）：切分正确性 / 大小写不敏感 / 正则元字符 / XSS 安全 / 无命中原样渲染。
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { KeywordHighlight } from '@/components/feed/KeywordHighlight';
import { splitByKeywords } from '@/lib/highlight';

afterEach(cleanup);

describe('splitByKeywords 切分逻辑', () => {
  it('单关键词多次命中切分为多段', () => {
    expect(splitByKeywords('人工智能行动方案与人工智能立法', ['人工智能'])).toEqual([
      { hit: true, value: '人工智能' },
      { hit: false, value: '行动方案与' },
      { hit: true, value: '人工智能' },
      { hit: false, value: '立法' },
    ]);
  });

  it('大小写不敏感命中（AI 命中 ai/AI/ai）', () => {
    const parts = splitByKeywords('AI 与 ai 以及 Ai', ['AI']);
    expect(parts.filter((p) => p.hit).map((p) => p.value)).toEqual(['AI', 'ai', 'Ai']);
  });

  it('多关键词重叠时优先长词命中', () => {
    const parts = splitByKeywords('人工智能产业', ['人工', '人工智能']);
    expect(parts[0]).toEqual({ hit: true, value: '人工智能' });
  });

  it('关键词中的正则元字符按字面量匹配', () => {
    const parts = splitByKeywords('a.b+axb', ['a.b+', 'x']);
    expect(parts).toEqual([
      { hit: true, value: 'a.b+' },
      { hit: false, value: 'a' },
      { hit: true, value: 'x' },
      { hit: false, value: 'b' },
    ]);
  });

  it('空/空白关键词与无命中：返回单段原文', () => {
    expect(splitByKeywords('原文', ['', '  '])).toEqual([{ hit: false, value: '原文' }]);
    expect(splitByKeywords('原文', ['AI'])).toEqual([{ hit: false, value: '原文' }]);
  });

  it('关键词去重（同词重复传入只命中一次语义）', () => {
    const parts = splitByKeywords('AI 简报', ['AI', 'AI']);
    expect(parts.filter((p) => p.hit)).toHaveLength(1);
  });
});

describe('KeywordHighlight 渲染', () => {
  it('命中词渲染为 <mark>（amber 自定义样式），其余文本原样', () => {
    const { container } = render(
      <p>
        <KeywordHighlight text="关于人工智能的行动方案" keywords={['人工智能']} />
      </p>,
    );
    const mark = screen.getByText('人工智能');
    expect(mark.tagName).toBe('MARK');
    expect(mark.className).toContain('bg-amber-500/25');
    expect(mark.className).toContain('text-amber-200');
    expect(container.textContent).toBe('关于人工智能的行动方案');
  });

  it('XSS 安全：含 HTML 的文本与关键词均以文本渲染，不产生元素注入', () => {
    const { container } = render(
      <p>
        <KeywordHighlight
          text={'前缀<script>alert(1)</script>后缀'}
          keywords={['<script>alert(1)</script>']}
        />
      </p>,
    );
    // 命中段是 <mark> 内的纯文本，页面中不存在 script 元素
    expect(document.querySelector('script')).toBeNull();
    expect(container.textContent).toBe('前缀<script>alert(1)</script>后缀');
    const mark = document.querySelector('mark');
    expect(mark?.textContent).toBe('<script>alert(1)</script>');
  });

  it('无命中与空文本：原样渲染 / 渲染 null', () => {
    const { container, rerender } = render(
      <p>
        <KeywordHighlight text="无命中文本" keywords={['AI']} />
      </p>,
    );
    expect(container.querySelector('mark')).toBeNull();
    expect(container.textContent).toBe('无命中文本');
    rerender(
      <p>
        <KeywordHighlight text={null} keywords={['AI']} />
      </p>,
    );
    expect(container.textContent).toBe('');
  });
});
