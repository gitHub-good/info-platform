import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Dialog } from '@/components/ui/dialog';
import { Skeleton } from '@/components/ui/skeleton';
import { SectionCard } from '@/components/subject/SectionCard';
import { formatTriggerTime, typeLabel } from '@/components/subject/eventFormat';
import { useSubjectDetail } from '@/hooks/useSubjectDetail';
import { changeColorClass, formatPct, formatPrice, formatVolume } from '@/lib/format';
import type {
  Announcement,
  EventItem,
  NewsItem,
  PolicySectionItem,
  SourceStatus,
} from '@/types/subject-detail';

/** 分区预览条数（核心版弹框：每分区最多 3 条，完整列表经「查看完整详情」进独立页）。 */
const PREVIEW_ITEM_COUNT = 3;

interface SubjectDetailDialogProps {
  /** 受控显隐（打开时才挂载数据主体、才发起取数）。 */
  open: boolean;
  /** 标的代码（内部统一代码，如 SZ000858）；null 时视为关闭（调用方守门）。 */
  subjectCode: string | null;
  onClose: () => void;
}

/** 分区预览切片（null 安全；超过上限截断）。 */
function previewItems<T>(items: T[] | null | undefined): T[] {
  return (items ?? []).slice(0, PREVIEW_ITEM_COUNT);
}

function DialogSkeleton() {
  return (
    <div className="flex flex-col gap-4" data-testid="subject-dialog-loading">
      <Skeleton className="h-9 w-56" />
      <Skeleton className="h-28 w-full" />
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        {Array.from({ length: 4 }, (_, index) => (
          <Skeleton key={index} className="h-36 w-full" />
        ))}
      </div>
    </div>
  );
}

/** 行情摘要指标（紧凑版：最新价/涨跌幅着色，最高/最低/量兜底「--」）。 */
function QuoteMetric({
  label,
  value,
  className,
}: {
  label: string;
  value: string;
  className?: string;
}) {
  return (
    <div className="flex flex-col gap-1">
      <span className="text-xs text-muted-foreground">{label}</span>
      <span className={`text-sm font-medium ${className ?? 'text-foreground'}`}>{value}</span>
    </div>
  );
}

/** 外链行元信息（时间 / 分类或来源；与详情页分区行同口径）。 */
function RowMeta({ parts }: { parts: Array<string | null | undefined> }) {
  const visible = parts.filter((p): p is string => !!p);
  if (visible.length === 0) return null;
  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
      {visible.map((part) => (
        <span key={part}>{part}</span>
      ))}
    </div>
  );
}

/** 公告预览：标题外链 + 分类/时间/来源（前 3 条）。 */
function AnnouncePreview({ items, status }: { items: Announcement[]; status: SourceStatus }) {
  return (
    <SectionCard title="公告" status={status}>
      {items.length > 0 ? (
        <ul className="flex flex-col gap-3" data-testid="subject-dialog-announce-list">
          {items.map((item) => (
            <li key={item.id ?? item.title} className="flex flex-col gap-1">
              <a
                href={item.url ?? '#'}
                target="_blank"
                rel="noopener noreferrer"
                className="text-sm font-medium text-foreground hover:underline"
              >
                {item.title}
              </a>
              <RowMeta parts={[item.category, item.publishedAt, item.source && `来源：${item.source}`]} />
            </li>
          ))}
        </ul>
      ) : (
        <p className="py-2 text-center text-sm text-muted-foreground">暂无数据</p>
      )}
    </SectionCard>
  );
}

/** 新闻预览：标题外链 + 摘要 + 时间/来源（前 3 条）。 */
function NewsPreview({ items, status }: { items: NewsItem[]; status: SourceStatus }) {
  return (
    <SectionCard title="新闻" status={status}>
      {items.length > 0 ? (
        <ul className="flex flex-col gap-3" data-testid="subject-dialog-news-list">
          {items.map((item) => (
            <li key={item.id ?? item.title} className="flex flex-col gap-1">
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
              <RowMeta parts={[item.publishedAt, item.source && `来源：${item.source}`]} />
            </li>
          ))}
        </ul>
      ) : (
        <p className="py-2 text-center text-sm text-muted-foreground">暂无数据</p>
      )}
    </SectionCard>
  );
}

/** 政策预览：标题外链 + 时间/来源（前 3 条；兜底段带口径注记）。 */
function PolicyPreview({
  items,
  isFallback,
  status,
}: {
  items: PolicySectionItem[];
  isFallback: boolean;
  status: SourceStatus;
}) {
  return (
    <SectionCard title="政策" status={status}>
      {items.length > 0 ? (
        <ul className="flex flex-col gap-3" data-testid="subject-dialog-policy-list">
          {isFallback ? (
            <li className="text-xs text-muted-foreground">近期宏观政策 · 无直接关联</li>
          ) : null}
          {items.map((item) => (
            <li key={item.id ?? item.title} className="flex flex-col gap-1">
              <a
                href={item.url ?? '#'}
                target="_blank"
                rel="noopener noreferrer"
                className="text-sm font-medium text-foreground hover:underline"
              >
                {item.title}
              </a>
              <RowMeta parts={[item.publishedAt, item.sourceName && `来源：${item.sourceName}`]} />
            </li>
          ))}
        </ul>
      ) : (
        <p className="py-2 text-center text-sm text-muted-foreground">暂无数据</p>
      )}
    </SectionCard>
  );
}

/** 事件预览：类型徽章 + 涨跌幅着色 + 触发时间 + 详情（前 3 条）。 */
function EventPreview({ items, status }: { items: EventItem[]; status: SourceStatus }) {
  return (
    <SectionCard title="事件监控" status={status}>
      {items.length > 0 ? (
        <ul className="flex flex-col gap-3" data-testid="subject-dialog-event-list">
          {items.map((item) => (
            <li
              key={`${item.anomalyType}-${item.triggerTime}`}
              className="flex flex-col gap-1"
            >
              <div className="flex flex-wrap items-center gap-2">
                <Badge variant="secondary">{typeLabel(item.anomalyType)}</Badge>
                {typeof item.changePct === 'number' ? (
                  <span
                    className={`text-sm font-medium ${changeColorClass(item.changePct)}`}
                  >
                    {formatPct(item.changePct)}
                  </span>
                ) : null}
              </div>
              {item.detail ? (
                <p className="text-xs text-muted-foreground line-clamp-2">{item.detail}</p>
              ) : null}
              <RowMeta parts={[formatTriggerTime(item.triggerTime)]} />
            </li>
          ))}
        </ul>
      ) : (
        <p className="py-2 text-center text-sm text-muted-foreground">暂无数据</p>
      )}
    </SectionCard>
  );
}

/** 弹框数据主体：仅在打开且有代码时挂载（打开才取数，关闭即卸载停请求）。 */
function SubjectDetailDialogBody({ subjectCode }: { subjectCode: string }) {
  const { data, loading, error, retry } = useSubjectDetail(subjectCode);

  if (loading) return <DialogSkeleton />;
  if (error) {
    return (
      <div
        className="flex flex-col items-center gap-3 py-8 text-center"
        data-testid="subject-dialog-error"
      >
        <p className="text-sm text-destructive">详情加载失败，请稍后重试</p>
        <Button variant="outline" size="sm" onClick={retry} data-testid="subject-dialog-retry">
          重试
        </Button>
      </div>
    );
  }
  if (!data) {
    return (
      <div
        className="py-8 text-center text-sm text-muted-foreground"
        data-testid="subject-dialog-empty"
      >
        暂无该标的数据
      </div>
    );
  }

  const status = data.sourceStatus;
  const changeClass = changeColorClass(data.quote?.changePct);
  // 政策分区：关联命中段优先，空段回宏观兜底段（与详情页 PolicySection 同口径）
  const policyItems = data.policies?.items?.length
    ? data.policies.items
    : (data.policies?.fallback?.items ?? []);
  const policyIsFallback = (data.policies?.items?.length ?? 0) === 0;

  return (
    <div className="flex flex-col gap-4" data-testid="subject-dialog-content">
      {/* 标题栏：标的名 / 代码 / 行业徽章 */}
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1" data-testid="subject-dialog-header">
        <span className="text-base font-medium">{data.subject.name}</span>
        <span className="text-sm text-muted-foreground">{data.subject.subjectCode}</span>
        {data.subject.industry ? (
          <Badge variant="secondary">{data.subject.industry}</Badge>
        ) : null}
      </div>

      {/* 限高滚动：长列表在弹框内消化，页面不动 */}
      <div className="max-h-[65vh] overflow-y-auto pr-1">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <div className="sm:col-span-2" data-testid="subject-dialog-quote">
            <SectionCard
              title="行情"
              status={status.quote}
              source={data.quote?.source}
              updatedAt={data.quote?.updatedAt}
            >
              {data.quote ? (
                <div className="grid grid-cols-2 gap-3 sm:grid-cols-5">
                  <QuoteMetric label="最新价" value={formatPrice(data.quote.price)} className={changeClass} />
                  <QuoteMetric
                    label="涨跌幅"
                    value={formatPct(data.quote.changePct)}
                    className={changeClass}
                  />
                  <QuoteMetric label="最高价" value={formatPrice(data.quote.high)} />
                  <QuoteMetric label="最低价" value={formatPrice(data.quote.low)} />
                  <QuoteMetric label="成交量" value={formatVolume(data.quote.volume)} />
                </div>
              ) : null}
            </SectionCard>
          </div>
          <AnnouncePreview items={previewItems(data.announcements)} status={status.announce} />
          <NewsPreview items={previewItems(data.news)} status={status.news} />
          <PolicyPreview
            items={previewItems(policyItems)}
            isFallback={policyIsFallback}
            status={status.policy}
          />
          <EventPreview items={previewItems(data.events)} status={status.event} />
        </div>
      </div>
    </div>
  );
}

/**
 * 标的详情弹框（交互优化：自选清单「查看详情」承载）。
 * 核心版 = 行情摘要行 + 公告/新闻/政策/事件 各前 3 条 + 「查看完整详情」独立页链接；
 * 复用 useSubjectDetail 两跳取数与 SectionCard 三态降级（骨架/错误/空态）。
 * 宽度沿 ui/dialog contentClassName 先例覆盖 max-w-md → max-w-4xl。
 */
export function SubjectDetailDialog({ open, subjectCode, onClose }: SubjectDetailDialogProps) {
  const active = open && subjectCode != null;
  const fullDetailHref =
    subjectCode != null ? `#/subjects/${encodeURIComponent(subjectCode)}` : null;
  return (
    <Dialog
      open={active}
      title="标的详情"
      onClose={onClose}
      contentClassName="max-w-4xl"
      footer={
        fullDetailHref != null ? (
          <a
            href={fullDetailHref}
            onClick={onClose}
            data-testid="subject-dialog-full-detail"
            className="text-sm text-primary underline-offset-4 hover:underline"
          >
            查看完整详情 →
          </a>
        ) : null
      }
    >
      {active && subjectCode != null ? <SubjectDetailDialogBody subjectCode={subjectCode} /> : null}
    </Dialog>
  );
}
