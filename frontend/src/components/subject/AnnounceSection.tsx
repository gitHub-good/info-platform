import { useCallback, useRef } from 'react';
import { ExternalLink } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Pagination } from '@/components/ui/pagination';
import { SectionCard } from './SectionCard';
import {
  ANNOUNCE_MAX_PAGES,
  ANNOUNCE_PAGE_SIZE,
  useAnnounceSectionPage,
  type AnnouncePageMeta,
} from '@/hooks/useSectionPage';
import type { Announcement, SectionPagination, SourceStatus } from '@/types/subject-detail';

interface AnnounceSectionProps {
  data: Announcement[] | null | undefined;
  status: SourceStatus;
  /** 数字主键（分区子端点寻址；未解析为 null 时翻页入口静默） */
  subjectId: number | null;
  /** 首屏分页元数据（聚合 sectionPagination.announce；缺省不渲染分页条） */
  pagination?: SectionPagination['announce'];
}

/** 列表滚动容器样式（UI 设计 §4 高度策略：md 起内滚，控件常驻滚动区外）。 */
const LIST_SCROLL_CLASS = 'md:max-h-[26rem] md:overflow-y-auto';

/**
 * 公告分区：标题 / 时间 / 分类 / 来源 / URL 列表 + 分区轻量分页条（M12 T95，UI 设计 §3.1）。
 * maxPages=5 显示层封顶（total 显源站真值）；巨潮降级（paginationSupported=false）整条
 * 替换为「当前公告源不支持翻页」；末页且被上限截断时渲染「更多历史公告」源站外链。
 */
export function AnnounceSection({ data, status, subjectId, pagination }: AnnounceSectionProps) {
  const initialMeta: AnnouncePageMeta = {
    total: pagination?.total ?? null,
    paginationSupported: pagination?.paginationSupported ?? true,
    moreUrl: pagination?.moreUrl ?? null,
  };
  const scrollRef = useRef<HTMLDivElement | null>(null);
  // 翻页成功：内容滚动容器回顶（不是页面 scrollIntoView，卡片位置不动——§2.4 交互 1）
  const handleLanded = useCallback(() => {
    if (scrollRef.current) scrollRef.current.scrollTop = 0;
  }, []);
  const paging = useAnnounceSectionPage(
    subjectId,
    { items: data ?? [], meta: initialMeta },
    handleLanded,
  );

  const { items, meta, page, loading, error, goToPage, retry } = paging;
  // 「更多历史公告」渲染条件（D4）：截断（total > pageSize × maxPages）且处于 effective 末页
  const effectiveTotalPages = Math.min(
    Math.max(1, Math.ceil((meta.total ?? 0) / ANNOUNCE_PAGE_SIZE)),
    ANNOUNCE_MAX_PAGES,
  );
  const currentPage = Math.min(Math.max(1, page), effectiveTotalPages);
  const truncated = (meta.total ?? 0) > ANNOUNCE_PAGE_SIZE * ANNOUNCE_MAX_PAGES;
  const showMoreHistory =
    truncated && currentPage === effectiveTotalPages && meta.moreUrl != null;

  return (
    <SectionCard title="公告" status={status}>
      {items.length > 0 ? (
        <div ref={scrollRef} className={LIST_SCROLL_CLASS}>
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
        </div>
      ) : null}
      {meta.paginationSupported ? (
        <>
          {loading ? (
            <p className="mb-2 text-xs text-muted-foreground" aria-live="polite">
              加载中…
            </p>
          ) : null}
          {error ? (
            <div className="mb-2 flex flex-wrap items-center gap-2">
              <p
                className="text-sm text-destructive"
                role="alert"
                data-testid="announce-pagination-error"
              >
                加载第 {error.page} 页失败：{error.message}
              </p>
              <Button
                variant="outline"
                size="sm"
                onClick={retry}
                data-testid="announce-pagination-retry"
              >
                重试
              </Button>
            </div>
          ) : null}
          <Pagination
            page={page}
            pageSize={ANNOUNCE_PAGE_SIZE}
            total={meta.total ?? 0}
            onPageChange={goToPage}
            disabled={loading}
            maxPages={ANNOUNCE_MAX_PAGES}
            label="公告分页"
            testIdPrefix="announce-pagination"
          />
          {showMoreHistory ? (
            <div className="mt-2 flex justify-end">
              <a
                href={meta.moreUrl ?? undefined}
                target="_blank"
                rel="noopener noreferrer"
                data-testid="announce-more-history"
                className="inline-flex items-center gap-1 text-sm text-primary hover:underline"
              >
                更多历史公告
                <ExternalLink aria-hidden="true" className="size-3.5" />
              </a>
            </div>
          ) : null}
        </>
      ) : (
        <p
          className="mt-4 border-t border-border pt-4 text-sm text-muted-foreground"
          data-testid="announce-pagination-unsupported"
        >
          当前公告源不支持翻页
        </p>
      )}
    </SectionCard>
  );
}
