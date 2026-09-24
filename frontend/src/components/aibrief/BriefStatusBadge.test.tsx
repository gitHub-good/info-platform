import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { BriefStatusBadge } from '@/components/aibrief/BriefStatusBadge';

afterEach(() => {
  cleanup();
});

// 语义色口径（体检 P2 统一）：成功=emerald / 进行中=amber / 失败=rose / 待核实=muted，
// 对齐 JobLog STATUS_META 的 className 方案（低饱和底 + 亮字）。
describe('BriefStatusBadge 简报状态徽章语义色', () => {
  it.each([
    [0, '处理中', 'amber'],
    [1, '已完成', 'emerald'],
    [2, '生成失败', 'rose'],
    [3, '待核实', 'muted'],
  ] as const)('status=%s 渲染 %s + %s 语义色', (status, label, color) => {
    render(<BriefStatusBadge status={status} />);
    const badge = screen.getByTestId(`brief-status-${status}`);
    expect(badge).toHaveTextContent(label);
    expect(badge.className).toContain(color);
  });
});
