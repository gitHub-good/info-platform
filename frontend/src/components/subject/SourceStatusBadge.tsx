import { Badge } from '@/components/ui/badge';
import type { SourceStatus } from '@/types/subject-detail';

interface SourceStatusBadgeProps {
  status: SourceStatus;
}

/**
 * 分区数据源状态徽章（sourceStatus 三态可视化）。
 * - ok：数据正常（secondary）
 * - missing：暂无数据（outline）—— 不阻断其他分区
 * - failed：获取失败（destructive）
 * - timeout：响应超时（destructive）
 */
const STATUS_META: Record<
  SourceStatus,
  { label: string; variant: 'default' | 'secondary' | 'destructive' | 'outline' }
> = {
  ok: { label: '数据正常', variant: 'secondary' },
  missing: { label: '暂无数据', variant: 'outline' },
  failed: { label: '获取失败', variant: 'destructive' },
  timeout: { label: '响应超时', variant: 'destructive' },
};

export function SourceStatusBadge({ status }: SourceStatusBadgeProps) {
  const { label, variant } = STATUS_META[status];
  return (
    <Badge variant={variant} data-testid={`status-badge-${status}`}>
      {label}
    </Badge>
  );
}
