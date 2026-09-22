import { Badge } from '@/components/ui/badge';
import { cn } from 'cn';
import type { PolicyView } from '@/types/policy';

interface PolicyItemProps {
  policy: PolicyView;
  selected?: boolean;
  onSelect: (id: number) => void;
}

/**
 * 政策列表单条：标题 / 来源 / 时间 / 摘要 / 关联行业标签；点击展开详情。
 * 外层为 <button>（无障碍可点），内部用 <span> 保持 button 内容模型合法（短语内容）。
 */
export function PolicyItem({ policy, selected, onSelect }: PolicyItemProps) {
  return (
    <li>
      <button
        type="button"
        onClick={() => onSelect(policy.id)}
        aria-current={selected ? 'true' : undefined}
        data-testid={`policy-item-${policy.id}`}
        className={cn(
          'flex w-full flex-col gap-1 rounded-lg border p-3 text-left transition-colors',
          'hover:bg-muted/50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50',
          selected ? 'border-ring bg-muted/50' : 'border-border',
        )}
      >
        <span className="flex flex-wrap items-center gap-x-3 gap-y-1">
          <span className="text-sm font-medium text-foreground">{policy.title}</span>
          {policy.publishedAt ? (
            <span className="text-xs text-muted-foreground">{policy.publishedAt}</span>
          ) : null}
        </span>
        {policy.summary ? (
          <span className="block text-xs text-muted-foreground line-clamp-2">
            {policy.summary}
          </span>
        ) : null}
        <span className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
          {policy.source ? <span>来源：{policy.source}</span> : null}
          {policy.relatedIndustries.map((ind) => (
            <Badge
              key={ind}
              variant="secondary"
              data-testid={`policy-item-${policy.id}-industry-${ind}`}
            >
              {ind}
            </Badge>
          ))}
        </span>
      </button>
    </li>
  );
}
