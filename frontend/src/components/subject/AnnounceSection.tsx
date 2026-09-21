import { SectionCard } from './SectionCard';
import type { Announcement, SourceStatus } from '@/types/subject-detail';

interface AnnounceSectionProps {
  data: Announcement[] | null | undefined;
  status: SourceStatus;
}

/** 公告分区：标题 / 时间 / 分类 / 来源 / URL 列表 */
export function AnnounceSection({ data, status }: AnnounceSectionProps) {
  const items = data ?? [];
  return (
    <SectionCard title="公告" status={status}>
      {items.length > 0 ? (
        <ul className="flex flex-col gap-3" data-testid="announce-list">
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
                {item.category ? <span>{item.category}</span> : null}
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
