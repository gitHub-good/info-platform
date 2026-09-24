import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { SourceStatusBadge } from '@/components/subject/SourceStatusBadge';

afterEach(() => {
  cleanup();
});

describe('SourceStatusBadge 分区状态徽章', () => {
  it.each([
    ['ok', '数据正常'],
    ['missing', '暂无数据'],
    ['failed', '获取失败'],
    ['timeout', '响应超时'],
  ] as const)('status=%s 渲染标签 %s', (status, label) => {
    render(<SourceStatusBadge status={status} />);
    expect(screen.getByText(label)).toBeInTheDocument();
    expect(screen.getByTestId(`status-badge-${status}`)).toBeInTheDocument();
  });

  // 语义色口径（体检 P2 统一）：正常=emerald / 无数据=muted / 失败与超时=rose，
  // 对齐 JobLog STATUS_META 的 className 方案（低饱和底 + 亮字）。
  it.each([
    ['ok', 'emerald'],
    ['missing', 'muted'],
    ['failed', 'rose'],
    ['timeout', 'rose'],
  ] as const)('status=%s 使用 %s 语义色', (status, color) => {
    render(<SourceStatusBadge status={status} />);
    expect(screen.getByTestId(`status-badge-${status}`).className).toContain(color);
  });
});
