import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  getPaginationItems,
  Pagination,
} from '@/components/ui/pagination';

afterEach(() => cleanup());

// —— 折叠算法纯函数（UI 设计 §2.3 渲染用例表 6 组） —— #

describe('getPaginationItems 页码折叠纯函数', () => {
  it.each([
    { current: 2, totalPages: 3, expected: [1, 2, 3] },
    { current: 1, totalPages: 8, expected: [1, 2, 'ellipsis', 8] },
    { current: 3, totalPages: 8, expected: [1, 2, 3, 4, 'ellipsis', 8] },
    { current: 5, totalPages: 12, expected: [1, 'ellipsis', 4, 5, 6, 'ellipsis', 12] },
    { current: 11, totalPages: 12, expected: [1, 'ellipsis', 10, 11, 12] },
    { current: 1, totalPages: 100, expected: [1, 2, 'ellipsis', 100] },
  ] as const)('$current / $totalPages → $expected', ({ current, totalPages, expected }) => {
    expect(getPaginationItems(current, totalPages)).toEqual(expected);
  });

  it('任何输入下数字按钮 ≤7 个、省略号 ≤2 个（宽度恒定）', () => {
    for (let totalPages = 8; totalPages <= 60; totalPages++) {
      for (let current = 1; current <= totalPages; current++) {
        const items = getPaginationItems(current, totalPages);
        const numbers = items.filter((i): i is number => typeof i === 'number');
        const ellipses = items.filter((i) => i === 'ellipsis');
        expect(numbers.length).toBeLessThanOrEqual(7);
        expect(ellipses.length).toBeLessThanOrEqual(2);
        // 首末页恒显
        expect(numbers[0]).toBe(1);
        expect(numbers[numbers.length - 1]).toBe(totalPages);
      }
    }
  });
});

// —— 组件行为（受控 / 三态 / 回调 / 可达性） —— #

function setup(overrides: Partial<Parameters<typeof Pagination>[0]> = {}) {
  const onPageChange = vi.fn();
  const onPageSizeChange = vi.fn();
  const props = {
    page: 2,
    pageSize: 20,
    total: 45,
    onPageChange,
    onPageSizeChange,
    ...overrides,
  };
  render(<Pagination {...props} />);
  return { onPageChange, onPageSizeChange };
}

describe('Pagination 通用分页组件', () => {
  it('total=0：整条隐藏（return null）', () => {
    const { onPageChange } = setup({ total: 0, page: 1 });
    expect(screen.queryByTestId('pagination-root')).toBeNull();
    expect(onPageChange).not.toHaveBeenCalled();
  });

  it('多页态：总数文案 + 页码指示 + 页码序列 + 当前页 aria-current', () => {
    setup({ page: 2, pageSize: 20, total: 45 });
    expect(screen.getByTestId('pagination-total')).toHaveTextContent('共 45 条');
    expect(screen.getByTestId('pagination-page-indicator')).toHaveTextContent(
      '第 2 / 3 页',
    );
    expect(screen.getByTestId('pagination-page-1')).toBeInTheDocument();
    expect(screen.getByTestId('pagination-page-2')).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(screen.getByTestId('pagination-page-3')).not.toHaveAttribute(
      'aria-current',
    );
    expect(screen.queryByTestId('pagination-ellipsis')).toBeNull();
    expect(screen.getByTestId('pagination-prev')).toBeEnabled();
    expect(screen.getByTestId('pagination-next')).toBeEnabled();
  });

  it('单页精简态：保留总数/指示/条数，无翻页按钮（D2）', () => {
    setup({ page: 1, pageSize: 20, total: 5 });
    expect(screen.getByTestId('pagination-total')).toHaveTextContent('共 5 条');
    expect(screen.getByTestId('pagination-page-indicator')).toHaveTextContent(
      '第 1 / 1 页',
    );
    expect(screen.getByTestId('pagination-size')).toBeInTheDocument();
    expect(screen.queryByTestId('pagination-prev')).toBeNull();
    expect(screen.queryByTestId('pagination-next')).toBeNull();
    expect(screen.queryByTestId('pagination-page-1')).toBeNull();
  });

  it('首/末页边界：上一页/下一页原生禁用（不隐藏）', () => {
    setup({ page: 1, pageSize: 20, total: 45 });
    expect(screen.getByTestId('pagination-prev')).toBeDisabled();
    expect(screen.getByTestId('pagination-next')).toBeEnabled();

    cleanup();
    setup({ page: 3, pageSize: 20, total: 45 });
    expect(screen.getByTestId('pagination-prev')).toBeEnabled();
    expect(screen.getByTestId('pagination-next')).toBeDisabled();
  });

  it('页码折叠渲染：current=5 / 12 页 → 两侧省略号各一', () => {
    setup({ page: 5, pageSize: 20, total: 240 });
    expect(screen.getByTestId('pagination-page-1')).toBeInTheDocument();
    expect(screen.getByTestId('pagination-page-4')).toBeInTheDocument();
    expect(screen.getByTestId('pagination-page-5')).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(screen.getByTestId('pagination-page-6')).toBeInTheDocument();
    expect(screen.getByTestId('pagination-page-12')).toBeInTheDocument();
    expect(screen.getAllByTestId('pagination-ellipsis')).toHaveLength(2);
  });

  it('点击页码回调目标页；点击当前页不回调', async () => {
    const user = userEvent.setup();
    const { onPageChange } = setup({ page: 2, pageSize: 20, total: 45 });
    await user.click(screen.getByTestId('pagination-page-3'));
    expect(onPageChange).toHaveBeenCalledWith(3);
    await user.click(screen.getByTestId('pagination-page-2'));
    expect(onPageChange).toHaveBeenCalledTimes(1);
  });

  it('上一页/下一页回调 current±1', async () => {
    const user = userEvent.setup();
    const { onPageChange } = setup({ page: 2, pageSize: 20, total: 45 });
    await user.click(screen.getByTestId('pagination-prev'));
    expect(onPageChange).toHaveBeenCalledWith(1);
    await user.click(screen.getByTestId('pagination-next'));
    expect(onPageChange).toHaveBeenCalledWith(3);
  });

  it('每页条数切换回调新 size（受控显示当前值）', async () => {
    const user = userEvent.setup();
    const { onPageSizeChange } = setup({ page: 2, pageSize: 20, total: 45 });
    const select = screen.getByTestId('pagination-size');
    expect(select).toHaveValue('20');
    await user.selectOptions(select, '50');
    expect(onPageSizeChange).toHaveBeenCalledWith(50);
    expect(select).toHaveValue('20');
  });

  it('默认条数选项 10/20/50', () => {
    setup({ page: 1, pageSize: 10, total: 5 });
    const select = screen.getByTestId('pagination-size');
    const values = Array.from(select.querySelectorAll('option')).map(
      (o) => o.value,
    );
    expect(values).toEqual(['10', '20', '50']);
  });

  it('在途禁用：disabled 时全控件禁用', () => {
    setup({ page: 2, pageSize: 20, total: 45, disabled: true });
    expect(screen.getByTestId('pagination-prev')).toBeDisabled();
    expect(screen.getByTestId('pagination-next')).toBeDisabled();
    expect(screen.getByTestId('pagination-page-1')).toBeDisabled();
    expect(screen.getByTestId('pagination-page-3')).toBeDisabled();
    expect(screen.getByTestId('pagination-size')).toBeDisabled();
  });

  it('键盘可达：Tab 聚焦页码按钮后 Enter 触发回调', async () => {
    const user = userEvent.setup();
    const { onPageChange } = setup({ page: 2, pageSize: 20, total: 45 });
    await user.tab();
    await user.tab();
    await user.keyboard('{Enter}');
    // Tab 序内首个可触发页码即目标（1 页），回调一次即可证明键盘通道可用
    expect(onPageChange).toHaveBeenCalled();
  });

  it('显示层 clamp：page 越过总页数时指示与高亮收敛到末页', () => {
    setup({ page: 9, pageSize: 20, total: 100 });
    expect(screen.getByTestId('pagination-page-indicator')).toHaveTextContent(
      '第 5 / 5 页',
    );
    expect(screen.getByTestId('pagination-page-5')).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(screen.queryByTestId('pagination-page-9')).toBeNull();
    expect(screen.getByTestId('pagination-next')).toBeDisabled();
  });

  it('语义分区：容器为 nav 且带 aria-label（默认「分页」，可自定义）', () => {
    const { container } = render(
      <Pagination
        page={1}
        pageSize={20}
        total={45}
        onPageChange={vi.fn()}
        onPageSizeChange={vi.fn()}
      />,
    );
    const nav = container.querySelector('nav');
    expect(nav).toHaveAttribute('aria-label', '分页');

    cleanup();
    const { container: c2 } = render(
      <Pagination
        page={1}
        pageSize={20}
        total={45}
        label="政策列表分页"
        onPageChange={vi.fn()}
        onPageSizeChange={vi.fn()}
      />,
    );
    expect(c2.querySelector('nav')).toHaveAttribute('aria-label', '政策列表分页');
  });
});
