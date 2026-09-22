import { Badge } from '@/components/ui/badge';
import { cn } from 'cn';
import type { EffectiveMode } from '@/types/llmConfig';

interface EffectBadgeProps {
  /** 生效方式（来自接口 effectiveMode）；缺失一律按重启生效渲染（宁多示不漏示，UI 方案 §4.2）。 */
  mode?: EffectiveMode;
  className?: string;
}

/**
 * 生效方式徽章（T39，UI 方案 §5.2）：RESTART 显示 amber「重启后生效」，LIVE 不渲染（降噪）。
 * 渲染依据后端返回的 effectiveMode，不前端硬编码（D3）。
 */
export function EffectBadge({ mode, className }: EffectBadgeProps) {
  if (mode === 'LIVE') {
    return null;
  }
  return (
    <Badge
      variant="outline"
      className={cn('bg-amber-500/15 text-amber-400', className)}
      data-testid="effect-badge-restart"
    >
      重启后生效
    </Badge>
  );
}
