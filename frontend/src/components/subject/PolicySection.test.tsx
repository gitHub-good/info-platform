// V2.3-M23 T206：政策分区对象契约呈现——SUBJECT/INDUSTRY 分级徽章 / 宏观兜底段（note 口径明示）/
// 「全部政策 →」/policies 出口 + basis 口径脚注。不翻页（单页全量语义沿 M12 Won't）。

import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PolicySection } from '@/components/subject/PolicySection';
import type { PolicySectionItem, PolicySectionView } from '@/types/subject-detail';

function policyItem(n: number, matchType?: PolicySectionItem['matchType']): PolicySectionItem {
  return {
    id: 4000 + n,
    title: `政策 ${n}`,
    url: `https://www.gov.cn/zhengce/p${n}`,
    publishedAt: '2026-09-20',
    sourceName: '中国政府网·政策',
    matchType,
  };
}

function sectionView(
  overrides: Partial<PolicySectionView> = {},
): PolicySectionView {
  return {
    items: [policyItem(1, 'SUBJECT'), policyItem(2, 'INDUSTRY')],
    fallback: null,
    basis: 'policy-scope-v1',
    ...overrides,
  };
}

function renderSection(
  data: PolicySectionView | null | undefined,
  status: Parameters<typeof PolicySection>[0]['status'] = 'ok',
) {
  return render(<PolicySection data={data} status={status} />);
}

afterEach(() => {
  cleanup();
  window.location.hash = '';
});

describe('PolicySection 政策分区（V2.3-M23 T206 分区对象契约）', () => {
  it('关联命中段：条目渲染 + SUBJECT=标的关联（emerald）/INDUSTRY=行业关联（sky）分级徽章', () => {
    renderSection(sectionView());

    const list = screen.getByTestId('policy-list');
    const rows = within(list).getAllByTestId('policy-row');
    expect(rows).toHaveLength(2);
    expect(within(list).getByText('政策 1')).toBeInTheDocument();

    const subjectBadge = screen.getByTestId('policy-match-4001');
    expect(subjectBadge).toHaveTextContent('标的关联');
    expect(subjectBadge.className).toContain('text-emerald-400');

    const industryBadge = screen.getByTestId('policy-match-4002');
    expect(industryBadge).toHaveTextContent('行业关联');
    expect(industryBadge.className).toContain('text-sky-400');

    // 无任何翻页控件（单页全量语义沿 M12 Won't）
    expect(screen.queryByTestId('pagination-root')).toBeNull();
  });

  it('宏观兜底段：muted「近期宏观政策」+ note 口径明示文案 + 条目无命中徽章', () => {
    renderSection(
      sectionView({
        items: [],
        fallback: {
          items: [policyItem(3)],
          note: '暂无与该标的行业直接相关的政策，以下为近期宏观政策',
        },
      }),
    );

    expect(screen.getByTestId('policy-fallback')).toBeInTheDocument();
    expect(screen.getByTestId('policy-fallback-note')).toHaveTextContent(
      '近期宏观政策 · 暂无与该标的行业直接相关的政策，以下为近期宏观政策',
    );
    const rows = within(screen.getByTestId('policy-fallback-list')).getAllByTestId('policy-row');
    expect(rows).toHaveLength(1);
    // 兜底段条目无 matchType：分级徽章不渲染（不冒充关联）
    expect(within(screen.getByTestId('policy-fallback')).queryByText('标的关联')).toBeNull();
    expect(within(screen.getByTestId('policy-fallback')).queryByText('行业关联')).toBeNull();
    expect(screen.queryByTestId('policy-list')).toBeNull();
  });

  it('底部出口（V2.4 T213 改指）：「全部政策 →」跳资讯库 L1=监管·政策 预填 + basis 口径脚注', async () => {
    const user = userEvent.setup();
    renderSection(sectionView());

    const link = screen.getByTestId('policy-more-link');
    expect(link).toHaveTextContent('全部政策 →');
    expect(link.closest('div')).toHaveClass('justify-end');
    expect(screen.getByTestId('policy-basis')).toHaveTextContent('口径 policy-scope-v1');
    // 站内跳转用 button（hash 路由），不渲染外链 <a>
    expect(link.tagName).toBe('BUTTON');

    await user.click(link);
    // hash 写入后非 ASCII 以百分号编码回读，解码断言（政策页裁撤，改指资讯库预填）
    await vi.waitFor(() =>
      expect(decodeURIComponent(window.location.hash)).toBe('#/news-library?l1=监管·政策'),
    );
  });

  it('双段同现防御：items 非空时关联段优先，兜底段不渲染（徽章可解释性不破坏）', () => {
    renderSection(
      sectionView({
        fallback: {
          items: [policyItem(9)],
          note: '不应出现的兜底段',
        },
      }),
    );

    expect(screen.getByTestId('policy-list')).toBeInTheDocument();
    expect(screen.queryByTestId('policy-fallback')).toBeNull();
    expect(screen.queryByText('不应出现的兜底段')).toBeNull();
  });

  it('双段皆空（异常防御形态）：无列表无出口不崩（库内查询恒有内容，此为兜底护栏）', () => {
    renderSection(sectionView({ items: [], fallback: null }));

    expect(screen.queryByTestId('policy-list')).toBeNull();
    expect(screen.queryByTestId('policy-fallback')).toBeNull();
    expect(screen.queryByTestId('policy-more-link')).toBeNull();
  });

  it('missing 分区：SectionCard 兜底文案，不渲染出口（沿 M12 三态语义）', () => {
    renderSection(null, 'missing');

    expect(screen.getByTestId('fallback-missing')).toBeInTheDocument();
    expect(screen.queryByTestId('policy-more-link')).toBeNull();
  });
});
