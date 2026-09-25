import { useCallback, useRef } from 'react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Pagination } from '@/components/ui/pagination';
import { changeColorClass, formatPct, formatPrice } from '@/lib/format';
import { SectionCard } from './SectionCard';
import { EVENT_PAGE_SIZE, useEventSectionPage } from '@/hooks/useSectionPage';
import type { EventItem, SourceStatus } from '@/types/subject-detail';

/** 列表滚动容器样式（UI 设计 §4 高度策略：md 起内滚，分页条常驻滚动区外）。 */
const LIST_SCROLL_CLASS = 'md:max-h-[26rem] md:overflow-y-auto';

interface EventSectionProps {
  data: EventItem[] | null | undefined;
  status: SourceStatus;
  /** 数字主键（分区子端点寻址；未解析为 null 时翻页入口静默） */
  subjectId: number | null;
  /** 首屏 7 天窗内精确总数（聚合 sectionPagination.event.total；缺省 0 不渲染分页条） */
  total?: number;
}

/** 异动类型展示标签（anomaly_event.anomaly_type 枚举名 → 中文） */
const ANOMALY_TYPE_LABEL: Record<string, string> = {
  PRICE_CHANGE: '涨跌幅异动',
  VOLUME: '量异动',
  EVENT: '事件',
};

function typeLabel(anomalyType: string): string {
  return ANOMALY_TYPE_LABEL[anomalyType] ?? anomalyType;
}

/** 触发时刻（ISO-8601 UTC）→ 本地时间串展示 */
function formatTriggerTime(triggerTime: string): string {
  const date = new Date(triggerTime);
  if (Number.isNaN(date.getTime())) return triggerTime;
  return date.toLocaleString('zh-CN', { hour12: false });
}

/**
 * 事件分区（ADR-0013）：本地 anomaly_event 近期异动/事件列表 + 标准页码条（M12 T95，
 * UI 设计 §3.3）：totalLabel「共 N 条（近 7 天）」窗口口径贴数字走；单页收敛精简态。
 */
export function EventSection({ data, status, subjectId, total = 0 }: EventSectionProps) {
  const scrollRef = useRef<HTMLDivElement | null>(null);
  // 翻页成功：内容滚动容器回顶（不是页面 scrollIntoView，卡片位置不动——§2.4 交互 1）
  const handleLanded = useCallback(() => {
    if (scrollRef.current) scrollRef.current.scrollTop = 0;
  }, []);
  const paging = useEventSectionPage(subjectId, { items: data ?? [], total }, handleLanded);

  const { items, meta, page, loading, error, goToPage, retry } = paging;
  return (
    <SectionCard title="事件监控" status={status}>
      {items.length > 0 ? (
        <div ref={scrollRef} className={LIST_SCROLL_CLASS}>
          <ul className="flex flex-col gap-3" data-testid="event-list">
            {items.map((item) => (
              <li
                key={`${item.anomalyType}-${item.triggerTime}`}
                className="flex flex-col gap-1 border-b border-border pb-3 last:border-0 last:pb-0"
              >
                <div className="flex flex-wrap items-center gap-2">
                  <Badge variant="secondary">{typeLabel(item.anomalyType)}</Badge>
                  {typeof item.changePct === 'number' ? (
                    <span className={`text-sm font-medium ${changeColorClass(item.changePct)}`}>
                      {formatPct(item.changePct)}
                    </span>
                  ) : null}
                </div>
                {item.detail ? (
                  <p className="text-xs text-muted-foreground line-clamp-2">{item.detail}</p>
                ) : null}
                <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
                  <span>{formatTriggerTime(item.triggerTime)}</span>
                  {typeof item.currentPrice === 'number' ? (
                    <span>现价 {formatPrice(item.currentPrice)}</span>
                  ) : null}
                </div>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
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
            data-testid="event-pagination-error"
          >
            加载第 {error.page} 页失败：{error.message}
          </p>
          <Button
            variant="outline"
            size="sm"
            onClick={retry}
            data-testid="event-pagination-retry"
          >
            重试
          </Button>
        </div>
      ) : null}
      <Pagination
        page={page}
        pageSize={EVENT_PAGE_SIZE}
        total={meta.total}
        totalLabel={`共 ${meta.total} 条（近 7 天）`}
        onPageChange={goToPage}
        disabled={loading}
        label="事件监控分页"
        testIdPrefix="event-pagination"
      />
    </SectionCard>
  );
}
