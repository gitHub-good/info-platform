import { SectionCard } from './SectionCard';
import type { NewsItem, SourceStatus } from '@/types/subject-detail';

interface NewsSectionProps {
  data: NewsItem[] | null | undefined;
  status: SourceStatus;
}

/** 新闻分区：标题 / 时间 / 摘要 / 来源 / URL 列表 */
export function NewsSection({ data, status }: NewsSectionProps) {
  const items = data ?? [];
  return (
    <SectionCard title="新闻" status={status}>
      {items.length > 0 ? (
        <ul className="flex flex-col gap-3" data-testid="news-list">
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
              {item.summary ? (
                <p className="text-xs text-muted-foreground line-clamp-2">{item.summary}</p>
              ) : null}
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
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
