import { Badge } from '@/components/ui/badge';
import { statusToneClass } from '@/lib/format';
import type { BriefStatusCode } from '@/types/aibrief';

interface BriefStatusBadgeProps {
  status: BriefStatusCode;
}

/**
 * AI 简报状态徽章（§4.1.4 status 可视化）。
 * 语义色统一（体检 P2，对齐 JobLog STATUS_META 的 className 方案）：
 * - 0 处理中（amber） / 1 完成（emerald） / 2 失败（rose） / 3 待核实（muted，幻觉校验降级不作已确认结论）
 */
const STATUS_META: Record<BriefStatusCode, { label: string; className: string }> = {
  0: { label: '处理中', className: statusToneClass('warning') },
  1: { label: '已完成', className: statusToneClass('success') },
  2: { label: '生成失败', className: statusToneClass('failure') },
  3: { label: '待核实', className: statusToneClass('neutral') },
};

export function BriefStatusBadge({ status }: BriefStatusBadgeProps) {
  const { label, className } = STATUS_META[status];
  return (
    <Badge variant="ghost" className={className} data-testid={`brief-status-${status}`}>
      {label}
    </Badge>
  );
}
