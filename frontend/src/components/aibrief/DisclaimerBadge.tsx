import { Badge } from '@/components/ui/badge';

interface DisclaimerBadgeProps {
  /** 免责文案，默认「AI 生成，非投资建议」（§4.1.4 全部响应恒附）。 */
  text?: string;
}

/**
 * 免责声明徽章：醒目标注 AI 生成、非投资建议。
 * PRD 故事 2 场景 1 验收「页面显示 AI 生成，非投资建议免责」；§5 合规硬要求。
 */
export function DisclaimerBadge({ text = 'AI 生成，非投资建议' }: DisclaimerBadgeProps) {
  return (
    <Badge variant="outline" data-testid="brief-disclaimer">
      {text}
    </Badge>
  );
}
