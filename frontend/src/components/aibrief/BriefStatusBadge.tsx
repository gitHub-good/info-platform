import { Badge } from '@/components/ui/badge';
import type { BriefStatusCode } from '@/types/aibrief';

interface BriefStatusBadgeProps {
  status: BriefStatusCode;
}

/**
 * AI 简报状态徽章（§4.1.4 status 可视化）。
 * - 0 处理中（secondary）
 * - 1 完成（default）
 * - 2 失败（destructive）
 * - 3 待核实（outline）—— 幻觉校验降级，不作已确认结论
 */
const STATUS_META: Record<
  BriefStatusCode,
  { label: string; variant: 'default' | 'secondary' | 'destructive' | 'outline' }
> = {
  0: { label: '处理中', variant: 'secondary' },
  1: { label: '已完成', variant: 'default' },
  2: { label: '生成失败', variant: 'destructive' },
  3: { label: '待核实', variant: 'outline' },
};

export function BriefStatusBadge({ status }: BriefStatusBadgeProps) {
  const { label, variant } = STATUS_META[status];
  return (
    <Badge variant={variant} data-testid={`brief-status-${status}`}>
      {label}
    </Badge>
  );
}
