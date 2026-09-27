import { Badge } from '@/components/ui/badge';
import { cn } from 'cn';
import { formatDateTime } from '@/lib/format';
import type { PolicyView } from '@/types/policy';

interface PolicyItemProps {
  policy: PolicyView;
  selected?: boolean;
  onSelect: (id: number) => void;
}

/**
 * 政策列表单条（V2.3-M23 T205 换面：news 背书数据面）：
 * 标题 / 时间 / 摘要 / 源展示名 + L1 归类徽章（mainCategory/subIndustry）+ 回联标的徽章。
 * 卡体为 <button>（无障碍可点），内部全短语内容（标的跳详情入口在详情面板，避免嵌套交互元素）。
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
          <span className="text-xs text-muted-foreground">
            {formatDateTime(policy.publishedAt)}
          </span>
        </span>
        {policy.summary ? (
          <span className="block text-xs text-muted-foreground line-clamp-2">
            {policy.summary}
          </span>
        ) : null}
        <span className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
          {policy.sourceName ? <span>来源：{policy.sourceName}</span> : null}
          {policy.mainCategory ? (
            <Badge
              variant="secondary"
              data-testid={`policy-item-${policy.id}-category-${policy.mainCategory}`}
            >
              {policy.mainCategory}
            </Badge>
          ) : null}
          {policy.subIndustry && policy.subIndustry !== policy.mainCategory ? (
            <Badge
              variant="outline"
              data-testid={`policy-item-${policy.id}-sub-${policy.subIndustry}`}
            >
              {policy.subIndustry}
            </Badge>
          ) : null}
          {policy.matchedSubjects.map((subject) => (
            <Badge
              key={subject.code}
              variant="outline"
              className="border-primary/40 text-primary"
              data-testid={`policy-item-${policy.id}-subject-${subject.code}`}
              title={
                subject.industry
                  ? `${subject.name ?? subject.code} · ${subject.industry}`
                  : (subject.name ?? subject.code)
              }
            >
              {subject.name ?? subject.code}
            </Badge>
          ))}
        </span>
      </button>
    </li>
  );
}
