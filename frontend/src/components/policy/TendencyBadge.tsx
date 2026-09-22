import { Badge } from '@/components/ui/badge';
import type { AiTendencyCode } from '@/types/policy';

interface TendencyBadgeProps {
  tendency: AiTendencyCode;
  testId?: string;
}

/**
 * 政策 AI 倾向徽章（§4.1.5 aiTendency 4 态可视化）。
 * 配色沿用 T22 AI 简报页 A 股惯例（涨红跌绿）以保持产品内一致：
 * - 1 利好 → 红（rose）
 * - 2 利空 → 绿（emerald）
 * - 3 中性 → 灰（muted）
 * - 0 未判 → 灰（muted），标签「待判」（T24 全 0，T28 AI 填 1/2/3）
 * 文字标签明示倾向，消除纯色歧义。
 */
const TENDENCY_META: Record<AiTendencyCode, { label: string; className: string }> = {
  0: { label: '待判', className: 'bg-muted text-muted-foreground' },
  1: { label: '利好', className: 'bg-rose-500/15 text-rose-400' },
  2: { label: '利空', className: 'bg-emerald-500/15 text-emerald-400' },
  3: { label: '中性', className: 'bg-muted text-muted-foreground' },
};

export function TendencyBadge({ tendency, testId }: TendencyBadgeProps) {
  const { label, className } = TENDENCY_META[tendency];
  return (
    <Badge variant="ghost" className={className} data-testid={testId ?? `tendency-${tendency}`}>
      {label}
    </Badge>
  );
}
