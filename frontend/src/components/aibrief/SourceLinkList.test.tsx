import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { SourceLinkList } from '@/components/aibrief/SourceLinkList';
import type { BriefFact } from '@/types/aibrief';

afterEach(() => {
  cleanup();
});

describe('SourceLinkList 事实表展示（内容质量：claim 兜底 + 内部措辞净化）', () => {
  it('claim 为 null：事实陈述兜底「{metric} 数值」不留空', () => {
    // Arrange：ai_brief id=13 实测形状——claim null、metric/value 有值
    const facts: BriefFact[] = [
      { claim: null, metric: 'price', value: 13.58, source: 'QUOTE', sourceUrl: '' },
    ];

    // Act
    render(<SourceLinkList facts={facts} />);

    // Assert：claim 兜底、数值列展示
    const row = screen.getByTestId('brief-fact-0');
    expect(within(row).getByText('price 数值')).toBeInTheDocument();
    expect(screen.getByTestId('brief-fact-value-0')).toHaveTextContent('13.58');
  });

  it('source 含内部措辞（用户提供聚合行情数据）：净化展示为「平台数据」', () => {
    // Arrange：模型自填内部措辞直接露给用户（id=13 实测）
    const facts: BriefFact[] = [
      {
        claim: '当前价13.58元',
        metric: 'price',
        value: 13.58,
        source: '用户提供聚合行情数据',
        sourceUrl: null,
      },
      {
        claim: '毛利率68.79%',
        metric: 'gross_margin',
        value: 68.79,
        source: '用户提供财务数据(报告期2026-06-30)',
        sourceUrl: null,
      },
    ];

    // Act
    render(<SourceLinkList facts={facts} />);

    // Assert：来源徽章净化为「平台数据」，内部措辞不出现在页面上
    expect(screen.getByTestId('brief-fact-source-0')).toHaveTextContent('平台数据');
    expect(screen.getByTestId('brief-fact-source-1')).toHaveTextContent('平台数据');
    expect(screen.queryByText(/用户提供/)).not.toBeInTheDocument();
    expect(screen.queryByText(/聚合/)).not.toBeInTheDocument();
  });

  it('source 为正常数据域标识（FINANCE）：原样展示来源徽章', () => {
    // Arrange
    const facts: BriefFact[] = [
      {
        claim: '归母净利润8.34亿元',
        metric: 'net_profit',
        value: 8.34,
        source: 'FINANCE',
        sourceUrl: 'https://example.com/f',
      },
    ];

    // Act
    render(<SourceLinkList facts={facts} />);

    // Assert：徽章原样 + claim 原样 + 原文链接
    expect(screen.getByTestId('brief-fact-source-0')).toHaveTextContent('FINANCE');
    expect(screen.getByText('归母净利润8.34亿元')).toBeInTheDocument();
    expect(screen.getByTestId('brief-fact-link-0')).toHaveAttribute(
      'href',
      'https://example.com/f',
    );
  });

  it('facts 为空：展示空态提示', () => {
    // Arrange / Act
    render(<SourceLinkList facts={[]} />);

    // Assert
    expect(screen.getByTestId('brief-sources-empty')).toBeInTheDocument();
  });
});
