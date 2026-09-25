// M12 T95：政策分区接入——不翻页 + 底部右对齐「更多政策 →」站内深检索出口。

import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PolicySection } from '@/components/subject/PolicySection';
import type { PolicyItem } from '@/types/subject-detail';

function policyItem(n: number): PolicyItem {
  return {
    title: `政策 ${n}`,
    publishedAt: '2026-09-20',
    publisher: '国务院',
    url: `https://www.gov.cn/zhengce/p${n}`,
  };
}

function renderSection(
  data: PolicyItem[] | null | undefined,
  status: Parameters<typeof PolicySection>[0]['status'] = 'ok',
) {
  return render(<PolicySection data={data} status={status} />);
}

afterEach(() => {
  cleanup();
  window.location.hash = '';
});

describe('PolicySection 政策分区出口（M12 T95）', () => {
  it('ok 且有命中：无任何翻页控件 + 底部「更多政策 →」右对齐入口', () => {
    renderSection([policyItem(1)]);

    const list = screen.getByTestId('policy-list');
    expect(within(list).getAllByRole('listitem')).toHaveLength(1);
    expect(screen.queryByTestId('pagination-root')).toBeNull();
    const link = screen.getByTestId('policy-more-link');
    expect(link).toHaveTextContent('更多政策 →');
    expect(link.closest('div')).toHaveClass('text-right');
    // 站内跳转用 button（hash 路由），不渲染外链 <a>
    expect(link.tagName).toBe('BUTTON');
  });

  it('点击「更多政策」跳转 /policies（hash 路由站内跳转）', async () => {
    const user = userEvent.setup();
    renderSection([policyItem(1)]);

    await user.click(screen.getByTestId('policy-more-link'));

    await vi.waitFor(() => expect(window.location.hash).toBe('#/policies'));
  });

  it('missing 分区：兜底文案，不渲染入口（PRD 场景 4.3）', () => {
    renderSection(null, 'missing');

    expect(screen.getByTestId('fallback-missing')).toBeInTheDocument();
    expect(screen.queryByTestId('policy-more-link')).toBeNull();
  });

  it('ok 但无命中：不渲染入口（单页全量语义，空态无出口）', () => {
    renderSection([]);

    expect(screen.queryByTestId('policy-list')).toBeNull();
    expect(screen.queryByTestId('policy-more-link')).toBeNull();
  });
});
