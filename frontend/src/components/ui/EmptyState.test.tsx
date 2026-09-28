// EmptyState 空态统一容器测试（M26 T228，UI V3.0 §4 项 3）：槽位渲染（图标/标题/描述/动作）+
// 两档尺寸（standard py-10 页面级 / compact py-6 区块级）+ 缺省省略（无图标不渲染图标槽）。
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { EmptyState } from '@/components/ui/EmptyState';

afterEach(() => cleanup());

describe('EmptyState 空态统一容器（T228）', () => {
  it('主路径：图标 + 标题 + 描述 + 动作槽齐备，动作可交互', async () => {
    const onCta = vi.fn();
    render(
      <EmptyState
        icon={<span data-testid="empty-icon">◆</span>}
        title="还没有订阅"
        description="创建订阅后，相关的公告、新闻与政策将进入你的信息流。"
        action={
          <button type="button" onClick={onCta} data-testid="empty-cta">
            新建订阅
          </button>
        }
        testId="page-empty"
      />,
    );

    expect(screen.getByTestId('page-empty')).toBeInTheDocument();
    expect(screen.getByTestId('empty-state-title')).toHaveTextContent('还没有订阅');
    expect(screen.getByTestId('empty-state-description')).toHaveTextContent('进入你的信息流');
    expect(screen.getByTestId('empty-icon')).toBeInTheDocument();
    await userEvent.click(screen.getByTestId('empty-cta'));
    expect(onCta).toHaveBeenCalledOnce();
  });

  it('结构：标准档居中 py-10（页面级默认档）', () => {
    render(<EmptyState title="暂无数据" testId="std-empty" />);

    expect(screen.getByTestId('std-empty')).toHaveClass('py-10');
    expect(screen.getByTestId('std-empty')).toHaveClass('flex-col');
    expect(screen.getByTestId('std-empty')).toHaveClass('items-center');
    expect(screen.getByTestId('std-empty')).toHaveClass('text-center');
    // 无描述不渲染描述槽
    expect(screen.queryByTestId('empty-state-description')).toBeNull();
  });

  it('结构：紧凑档 py-6（区块/卡片内嵌档）', () => {
    render(<EmptyState title="该场景暂无模板版本" size="compact" testId="compact-empty" />);

    expect(screen.getByTestId('compact-empty')).toHaveClass('py-6');
    expect(screen.getByTestId('compact-empty')).not.toHaveClass('py-10');
  });

  it('省略：无图标不渲染图标槽；无动作不渲染动作区', () => {
    render(<EmptyState title="暂无事件" description="可稍后刷新" />);

    // 图标槽以圆底 span 呈现，无 icon 时不出现
    const root = screen.getByTestId('empty-state-title').parentElement;
    expect(root?.querySelector('[aria-hidden="true"]')).toBeNull();
    expect(screen.queryByRole('button')).toBeNull();
  });
});
