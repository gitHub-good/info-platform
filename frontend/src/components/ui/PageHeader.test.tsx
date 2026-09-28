// PageHeader 页头统一容器测试（M26 T230，UI V3.0 §4 项 5 / REQ #12）：
// 三槽渲染（title=h1 text-xl font-medium / subtitle / actions）+ 副标题缺省省略 + 操作区右置。
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { PageHeader } from '@/components/ui/PageHeader';

afterEach(() => cleanup());

describe('PageHeader 页头统一容器（T230）', () => {
  it('主路径：h1 标题 + 副标题 + 操作区三槽齐备（页面级 h1 保留——窄屏页签行隐藏时仍需页面内标识）', () => {
    render(
      <PageHeader
        title="订阅管理"
        subtitle="订阅主题 / 标的 / 事件类型 / 政策主题"
        actions={<button type="button" data-testid="header-action">新建订阅</button>}
        testId="page-header"
      />,
    );

    const heading = screen.getByRole('heading', { level: 1 });
    expect(heading).toHaveTextContent('订阅管理');
    expect(heading.className).toContain('text-xl');
    expect(heading.className).toContain('font-medium');
    expect(screen.getByTestId('page-header')).toBeInTheDocument();
    expect(screen.getByTestId('page-header-subtitle')).toHaveTextContent('订阅主题');
    expect(screen.getByTestId('header-action')).toBeInTheDocument();
  });

  it('省略：无副标题不渲染副标题槽；无操作区不渲染操作区', () => {
    render(<PageHeader title="AI 简报" />);

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('AI 简报');
    expect(screen.queryByTestId('page-header-subtitle')).toBeNull();
    expect(screen.queryByTestId('page-header-actions')).toBeNull();
  });

  it('结构：操作区右置（justify-between 布局），副标题 muted 小字', () => {
    render(
      <PageHeader
        title="自选清单"
        subtitle="分组管理关注标的"
        actions={<span data-testid="header-action-2">ops</span>}
      />,
    );

    expect(screen.getByRole('heading').parentElement?.parentElement).toHaveClass('justify-between');
    expect(screen.getByTestId('page-header-subtitle').className).toContain('text-muted-foreground');
  });
});
