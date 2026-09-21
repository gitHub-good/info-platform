import { SectionCard } from './SectionCard';
import type { PolicyItem, SourceStatus } from '@/types/subject-detail';

interface PolicySectionProps {
  data: PolicyItem[] | null | undefined;
  status: SourceStatus;
}

/** 政策分区：标题 / 发文单位 / 时间 / 来源 / URL 列表 */
export function PolicySection({ data, status }: PolicySectionProps) {
  const items = data ?? [];
  return (
    <SectionCard title="政策" status={status}>
      {items.length > 0 ? (
        <ul className="flex flex-col gap-3" data-testid="policy-list">
          {items.map((item) => (
            <li
              key={item.id ?? item.title}
              className="flex flex-col gap-1 border-b border-border pb-3 last:border-0 last:pb-0"
            >
              <a
                href={item.url ?? '#'}
                target="_blank"
                rel="noopener noreferrer"
                className="text-sm font-medium text-foreground hover:underline"
              >
                {item.title}
              </a>
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
                {item.publisher ? <span>发文单位：{item.publisher}</span> : null}
                <span>{item.publishedAt}</span>
                {item.source ? <span>来源：{item.source}</span> : null}
              </div>
            </li>
          ))}
        </ul>
      ) : null}
    </SectionCard>
  );
}
