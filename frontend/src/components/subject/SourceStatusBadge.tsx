import { Badge } from '@/components/ui/badge';
import type { SourceStatus } from '@/types/subject-detail';

interface SourceStatusBadgeProps {
  status: SourceStatus;
}

/**
 * 分区数据源状态徽章（sourceStatus 三态可视化）。
 * 语义色统一（体检 P2，对齐 JobLog STATUS_META 的 className 方案）：
 * - ok：数据正常（emerald）
 * - missing：暂无数据（muted）—— 不阻断其他分区
 * - failed：获取失败（rose）
 * - timeout：响应超时（rose）
 */
const STATUS_META: Record<SourceStatus, { label: string; className: string }> = {
  ok: { label: '数据正常', className: 'bg-emerald-500/15 text-emerald-400' },
  missing: { label: '暂无数据', className: 'bg-muted text-muted-foreground' },
  failed: { label: '获取失败', className: 'bg-rose-500/15 text-rose-400' },
  timeout: { label: '响应超时', className: 'bg-rose-500/15 text-rose-400' },
};

export function SourceStatusBadge({ status }: SourceStatusBadgeProps) {
  const { label, className } = STATUS_META[status];
  return (
    <Badge variant="ghost" className={className} data-testid={`status-badge-${status}`}>
      {label}
    </Badge>
  );
}
