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
});
