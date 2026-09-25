import { useEffect, useRef } from 'react';
import { Button } from '@/components/ui/button';
import { SectionCard } from './SectionCard';
import { useNewsSectionPage } from '@/hooks/useSectionPage';
import type { NewsItem, SourceStatus } from '@/types/subject-detail';

/** 列表滚动容器样式（UI 设计 §4 高度策略：md 起内滚，按钮常驻滚动区外）。 */
const LIST_SCROLL_CLASS = 'md:max-h-[26rem] md:overflow-y-auto';

interface NewsSectionProps {
  data: NewsItem[] | null | undefined;
  status: SourceStatus;
  /** 数字主键（分区子端点寻址；未解析为 null 时加载入口静默） */
  subjectId: number | null;
}

/**
 * 新闻分区：标题 / 时间 / 摘要 / 来源 / URL 列表 + 「加载更多」两态（M12 T95，UI 设计 §3.2）。
 * 探页状态机（seenIds 去重/单点击 ≤2 源页/三路停止）在 useNewsSectionPage；
 * 追加成功后首条新增条目滚入卡内视口（内容区内滚，页面不滚）。
 */
export function NewsSection({ data, status, subjectId }: NewsSectionProps) {
  const news = useNewsSectionPage(subjectId, data ?? []);
  const listRef = useRef<HTMLUListElement | null>(null);

  // scrollRequest 指向追加批次首条：渲染落地后滚入卡内视口（§3.2 appended 态）
  useEffect(() => {
    if (news.scrollRequest == null) return;
    const target = listRef.current?.children[news.scrollRequest] as HTMLElement | undefined;
    if (target && typeof target.scrollIntoView === 'function') {
      target.scrollIntoView({ block: 'start' });
    }
    news.acknowledgeScroll();
  }, [news.scrollRequest, news.acknowledgeScroll, news.items]);

  const { items } = news;
  return (
    <SectionCard title="新闻" status={status}>
      {items.length > 0 ? (
        <div className={LIST_SCROLL_CLASS}>
          <ul className="flex flex-col gap-3" data-testid="news-list" ref={listRef}>
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
        </div>
      ) : null}
      {news.stopped ? (
        <p
          className="mt-4 border-t border-border pt-4 text-center text-sm text-muted-foreground"
          data-testid="news-no-more"
        >
          没有更多相关新闻
        </p>
      ) : items.length > 0 ? (
        <div className="mt-4 border-t border-border pt-4">
          {news.error ? (
            <div className="mb-2 flex flex-wrap items-center justify-center gap-2">
              <p
                className="text-sm text-destructive"
                role="alert"
                data-testid="news-load-more-error"
              >
                加载更多失败：{news.error}
              </p>
              <Button
                variant="outline"
                size="sm"
                onClick={news.retry}
                data-testid="news-load-more-retry"
              >
                重试
              </Button>
            </div>
          ) : null}
          <div className="flex justify-center">
            <Button
              variant="outline"
              size="sm"
              onClick={news.loadMore}
              disabled={news.loading}
              aria-busy={news.loading}
              data-testid="news-load-more"
            >
              {news.loading ? '加载中…' : '加载更多相关新闻'}
            </Button>
          </div>
        </div>
      ) : null}
    </SectionCard>
  );
}
