import { useCallback, useEffect, useRef, useState } from 'react';
import { ExternalLink, Rss } from 'lucide-react';
import { ApiError } from '@/api/http';
import { getPersonalFeed, listSubscriptions } from '@/api/feed';
import { KeywordHighlight } from '@/components/feed/KeywordHighlight';
import { Badge } from '@/components/ui/badge';
import { Button, buttonVariants } from '@/components/ui/button';
import { Card, CardContent, CardHeader } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import type { FeedItemView, FeedItemType } from '@/types/feed';
import { cn } from '@/lib/utils';

// —— 常量与工具 ——

/** 首屏骨架条目卡数（UI 方案 §3.5 三态 loading）。 */
const SKELETON_CARDS = 4;

/** 非 ApiError 兜底文案。 */
function messageOf(err: unknown, fallback: string): string {
  return err instanceof ApiError ? err.msg : fallback;
}

/** 类型徽章：公告 emerald / 新闻 sky / 政策 violet / 推荐 amber（低饱和底 + 亮字 + 文字明示，UI 方案 §5.1）。 */
const TYPE_META: Record<FeedItemType, { label: string; className: string }> = {
  announce: { label: '公告', className: 'bg-emerald-500/15 text-emerald-400' },
  news: { label: '新闻', className: 'bg-sky-500/15 text-sky-400' },
  policy: { label: '政策', className: 'bg-violet-500/15 text-violet-300' },
  recommendation: { label: '推荐', className: 'bg-amber-500/15 text-amber-400' },
};

/** ISO 时间 → 本地短格式（MM-dd HH:mm，对齐线框「09-22 14:30」）；空/非法返回空串。 */
function formatTime(iso: string | null): string {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  const p = (n: number) => String(n).padStart(2, '0');
  return `${p(date.getMonth() + 1)}-${p(date.getDate())} ${p(date.getHours())}:${p(date.getMinutes())}`;
}

// —— 条目卡 ——

interface FeedItemCardProps {
  item: FeedItemView;
}

/** 信息流条目卡（类型徽章 + 标题/摘要关键词高亮 + 元信息 + 命中原因 chip + 原文外链）。 */
function FeedItemCard({ item }: FeedItemCardProps) {
  const meta = TYPE_META[item.type] ?? { label: item.type, className: 'bg-muted text-muted-foreground' };
  const metaFragments = [item.source, item.subjectName ? `标的 ${item.subjectName}` : null].filter(
    (fragment): fragment is string => fragment != null && fragment !== '',
  );
  return (
    <Card data-testid={`feed-item-${item.id}`}>
      <CardHeader>
        <div className="flex items-start gap-2">
          <Badge variant="ghost" className={`mt-0.5 shrink-0 ${meta.className}`} data-testid={`feed-item-type-${item.id}`}>
            {meta.label}
          </Badge>
          <p className="min-w-0 flex-1 break-words text-sm leading-snug font-medium">
            <KeywordHighlight text={item.title} keywords={item.keywords} />
          </p>
          <span className="ml-1 shrink-0 pt-0.5 text-xs whitespace-nowrap text-muted-foreground">
            {formatTime(item.publishedAt)}
          </span>
        </div>
      </CardHeader>
      <CardContent className="flex flex-col gap-2">
        {item.summary ? (
          <p className="line-clamp-2 text-sm text-muted-foreground">
            <KeywordHighlight text={item.summary} keywords={item.keywords} />
          </p>
        ) : null}
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5 text-xs text-muted-foreground">
          {metaFragments.length > 0 ? <span>{metaFragments.join(' · ')}</span> : null}
          {item.matchReason ? (
            <Badge
              variant="ghost"
              className="bg-muted text-muted-foreground"
              data-testid={`feed-item-reason-${item.id}`}
            >
              命中 {item.matchReason}
            </Badge>
          ) : null}
          {item.url ? (
            <a
              href={item.url}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-0.5 text-primary outline-none transition-colors hover:underline focus-visible:ring-2 focus-visible:ring-ring/50"
              data-testid={`feed-item-link-${item.id}`}
            >
              原文
              <ExternalLink className="size-3" aria-hidden="true" />
            </a>
          ) : null}
        </div>
      </CardContent>
    </Card>
  );
}

// —— 页面 ——

/**
 * 个人信息流页（T43，UI 方案 §3.5）。
 * - 条目流：GET /feed/personal 游标分页（单页 20，publishedAt 倒序），条目卡关键词高亮。
 * - 分页（D7）：触底哨兵 IntersectionObserver 自动加载 + 「加载更多」按钮兜底
 *   （IO 不可用或翻页失败时显示，键盘可达）；翻页失败不清已有条目、可重试；
 *   nextCursor 为空停止哨兵并显示「已加载全部」。
 * - 空态：无活跃订阅→引导空态（CTA 去自选清单）；有订阅无命中→muted 文案（无 CTA）。
 * - 三态：首屏 4 卡骨架 / 空态 / 首屏错误重试；401 由 http 层统一跳登录。
 */
export function Feed() {
  const [items, setItems] = useState<FeedItemView[]>([]);
  const [nextCursor, setNextCursor] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [loadMoreError, setLoadMoreError] = useState<string | null>(null);
  // null = 未知（订阅查询未返回或失败）：空态降级为「无命中」文案，不误导性引导
  const [hasActiveSubs, setHasActiveSubs] = useState<boolean | null>(null);

  const abortRef = useRef<AbortController | null>(null);
  const sentinelRef = useRef<HTMLDivElement>(null);
  const ioAvailable = typeof IntersectionObserver !== 'undefined';

  // 首屏：信息流首页 + 订阅判别并行；订阅查询失败不阻断信息流（降级 unknown）
  const loadFirst = useCallback(async () => {
    abortRef.current?.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    setLoading(true);
    setError(null);
    setLoadMoreError(null);
    try {
      const [page, subs] = await Promise.all([
        getPersonalFeed(null, ctrl.signal),
        listSubscriptions(ctrl.signal).catch(() => null),
      ]);
      if (ctrl.signal.aborted) return;
      setItems(page.items);
      setNextCursor(page.nextCursor);
      setHasActiveSubs(
        subs == null ? null : subs.some((sub) => sub.status === 1),
      );
    } catch (err) {
      if (ctrl.signal.aborted) return;
      setError(messageOf(err, '信息流加载失败'));
    } finally {
      if (!ctrl.signal.aborted) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadFirst();
    return () => abortRef.current?.abort();
  }, [loadFirst]);

  // 翻页：追加不清已有条目；失败保留条目并露出兜底按钮（可重试）
  const loadMore = useCallback(async () => {
    if (nextCursor == null || loadingMore || loading) return;
    setLoadingMore(true);
    setLoadMoreError(null);
    try {
      const page = await getPersonalFeed(nextCursor);
      setItems((prev) => [...prev, ...page.items]);
      setNextCursor(page.nextCursor);
    } catch (err) {
      setLoadMoreError(messageOf(err, '加载更多失败'));
    } finally {
      setLoadingMore(false);
    }
  }, [nextCursor, loadingMore, loading]);

  const hasMore = nextCursor != null && !loading && !error;

  // 触底哨兵（D7）：进入视口自动加载下一页；翻页失败/在途时停止观察，
  // 成功后重新挂载即再触发（元素仍可见则 IO 立即回调，连续翻页）
  useEffect(() => {
    const node = sentinelRef.current;
    if (!ioAvailable || !hasMore || loadingMore || loadMoreError || !node) return;
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) void loadMore();
    });
    observer.observe(node);
    return () => observer.disconnect();
  }, [ioAvailable, hasMore, loadingMore, loadMoreError, loadMore]);

  const showFallbackButton = !ioAvailable || (!!loadMoreError && hasMore);

  return (
    <main className="mx-auto w-full max-w-4xl p-4 sm:p-6" data-testid="feed-page">
      <header className="mb-4">
        <h1 className="text-xl font-medium">个人信息流</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          按时间倒序 · 命中你的主题 / 标的 / 事件类型订阅
        </p>
      </header>

      {loading ? (
        <div className="flex flex-col gap-3" data-testid="feed-loading">
          {Array.from({ length: SKELETON_CARDS }, (_, index) => (
            <Skeleton key={index} className="h-28 w-full" />
          ))}
        </div>
      ) : error ? (
        <div className="flex flex-col items-start gap-2" data-testid="feed-error">
          <p className="text-sm text-destructive" role="alert">
            {error}
          </p>
          <Button variant="outline" size="sm" onClick={() => void loadFirst()} data-testid="feed-retry">
            重试
          </Button>
        </div>
      ) : items.length === 0 ? (
        hasActiveSubs === false ? (
          <div
            className="flex flex-col items-center gap-3 py-10 text-center"
            data-testid="feed-empty-no-subs"
          >
            <span className="flex size-10 items-center justify-center rounded-full bg-muted">
              <Rss className="size-5 text-muted-foreground" aria-hidden="true" />
            </span>
            <p className="text-sm text-muted-foreground">
              还没有订阅内容会出现在这里。订阅你的主题 / 标的 / 事件类型后，命中的公告、新闻与政策会汇总到本页。
            </p>
            <a
              href="#/watchlists"
              data-testid="feed-empty-cta"
              className={cn('mt-1', buttonVariants({ size: 'sm' }))}
            >
              去自选清单
            </a>
          </div>
        ) : (
          <div
            className="py-10 text-center text-sm text-muted-foreground"
            data-testid="feed-empty-no-hits"
          >
            暂无命中内容，可稍后再来看看
          </div>
        )
      ) : (
        <>
          <div className="flex flex-col gap-3" data-testid="feed-list">
            {items.map((item) => (
              <FeedItemCard key={item.id} item={item} />
            ))}
          </div>
          {hasMore ? (
            <div className="mt-3" data-testid="feed-more-area">
              {showFallbackButton ? (
                <div className="flex flex-col items-center gap-2">
                  {loadMoreError ? (
                    <p className="text-sm text-destructive" role="alert" data-testid="feed-more-error">
                      {loadMoreError}
                    </p>
                  ) : null}
                  <Button
                    variant="outline"
                    size="sm"
                    className="w-full"
                    onClick={() => void loadMore()}
                    disabled={loadingMore}
                    data-testid="feed-load-more"
                  >
                    {loadingMore ? '加载中…' : '加载更多'}
                  </Button>
                </div>
              ) : (
                // 触底哨兵：占位骨架进入视口即自动加载（IO 正常时按钮不出现）
                <div ref={sentinelRef} aria-hidden="true" data-testid="feed-sentinel">
                  <Skeleton className="h-16 w-full" />
                </div>
              )}
            </div>
          ) : (
            <p
              className="py-6 text-center text-sm text-muted-foreground"
              data-testid="feed-end"
            >
              已加载全部
            </p>
          )}
        </>
      )}
    </main>
  );
}

export default Feed;
