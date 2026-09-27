import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { formatDateTime } from '@/lib/format';
import {
  DIRECTION_LABELS,
  EVENT_TYPE_LABELS,
  IMPORTANCE_LABELS,
  labelOf,
} from '@/types/industryHeat';
import type { PolicyDetailView, RelatedEventView } from '@/types/policy';

interface PolicyDetailProps {
  detail: PolicyDetailView | null;
  loading: boolean;
  error: string | null;
  onRetry: () => void;
}

/** 事件方向徽章（ai_tendency 承接面：BULLISH 利好红 / BEARISH 利空绿 / NEUTRAL 中性灰，A 股惯例）。 */
function EventDirectionBadge({ direction, testId }: { direction: string; testId: string }) {
  const tone =
    direction === 'BULLISH'
      ? 'bg-red-500/15 text-red-500'
      : direction === 'BEARISH'
        ? 'bg-green-500/15 text-green-500'
        : 'bg-muted text-muted-foreground';
  return (
    <Badge className={tone} data-testid={testId}>
      {labelOf(DIRECTION_LABELS, direction)}
    </Badge>
  );
}

/** 单条关联 L2 政策发布事件（方向徽章 + 类型/重要度/日期 + 摘要 + 事件流下钻入口）。 */
function RelatedEvent({ event }: { event: RelatedEventView }) {
  return (
    <li
      className="flex flex-col gap-1 rounded-lg border p-3"
      data-testid={`policy-related-event-${event.id}`}
    >
      <div className="flex flex-wrap items-center gap-2">
        <EventDirectionBadge
          direction={event.direction}
          testId={`policy-related-event-direction-${event.id}`}
        />
        <Badge variant="secondary">{labelOf(EVENT_TYPE_LABELS, event.eventType)}</Badge>
        <Badge variant="outline">{`重要度 ${labelOf(IMPORTANCE_LABELS, event.importance)}`}</Badge>
        {event.eventDate ? (
          <span className="text-xs text-muted-foreground">{event.eventDate}</span>
        ) : null}
        <a
          href={`#/events?focus=${event.id}`}
          data-testid={`policy-related-event-link-${event.id}`}
          className="ml-auto text-xs text-primary underline underline-offset-4"
        >
          事件流下钻 →
        </a>
      </div>
      {event.summary ? (
        <p className="text-xs text-muted-foreground">{event.summary}</p>
      ) : null}
    </li>
  );
}

/**
 * 政策详情面板（V2.3-M23 T205 换面：news 背书 + relatedEvents 承接倾向，aiTendency 退役）：
 * 标题 + 来源/时间/原文 + L1 归类徽章 + 摘要 + 回联标的（chips 跳标的详情）+ 关联 L2 政策发布事件区。
 * 三态：加载（骨架）/ 错误（重试入口）/ 未选择（引导）/ 详情（完整展示）。
 */
export function PolicyDetail({ detail, loading, error, onRetry }: PolicyDetailProps) {
  // 加载
  if (loading) {
    return (
      <Card data-testid="policy-detail-loading">
        <CardHeader>
          <CardTitle className="text-base">政策详情</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <Skeleton className="h-5 w-3/4" />
          <Skeleton className="h-20 w-full" />
          <Skeleton className="h-8 w-full" />
        </CardContent>
      </Card>
    );
  }

  // 错误
  if (error) {
    return (
      <Card data-testid="policy-detail-error">
        <CardHeader>
          <CardTitle className="text-base">政策详情</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col items-start gap-2">
          <p className="text-sm text-destructive" role="alert">
            {error}
          </p>
          <Button
            variant="outline"
            size="sm"
            onClick={onRetry}
            data-testid="policy-detail-retry"
          >
            重试
          </Button>
        </CardContent>
      </Card>
    );
  }

  // 未选择
  if (!detail) {
    return (
      <Card data-testid="policy-detail-empty">
        <CardContent className="py-10 text-center text-sm text-muted-foreground">
          点击左侧政策条目查看详情、回联标的与关联事件
        </CardContent>
      </Card>
    );
  }

  return (
    <Card data-testid="policy-detail">
      <CardHeader>
        <CardTitle className="text-base">{detail.title}</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
          {detail.sourceName ? <span>来源：{detail.sourceName}</span> : null}
          <span>{formatDateTime(detail.publishedAt)}</span>
          {detail.mainCategory ? (
            <Badge variant="secondary" data-testid="policy-detail-category">
              {detail.mainCategory}
            </Badge>
          ) : null}
          {detail.subIndustry && detail.subIndustry !== detail.mainCategory ? (
            <Badge variant="outline" data-testid="policy-detail-sub">
              {detail.subIndustry}
            </Badge>
          ) : null}
          {detail.url ? (
            <a
              href={detail.url}
              target="_blank"
              rel="noreferrer"
              className="text-primary underline"
              data-testid="policy-source-url"
            >
              原文
            </a>
          ) : null}
        </div>

        {detail.summary ? (
          <p className="text-sm text-muted-foreground" data-testid="policy-summary">
            {detail.summary}
          </p>
        ) : null}

        {detail.matchedSubjects.length > 0 ? (
          <section data-testid="policy-matched-subjects">
            <h3 className="mb-2 text-sm font-medium">回联标的</h3>
            <div className="flex flex-wrap items-center gap-1.5">
              {detail.matchedSubjects.map((subject) => (
                <a
                  key={subject.code}
                  href={`#/subjects/${subject.code}`}
                  data-testid={`policy-matched-${subject.code}`}
                  title={subject.industry ?? undefined}
                  className="rounded-md border border-primary/40 px-2 py-0.5 text-xs text-primary transition-colors hover:bg-primary/10"
                >
                  {subject.name ?? subject.code}
                  <span className="ml-1 text-muted-foreground">{subject.code}</span>
                </a>
              ))}
            </div>
          </section>
        ) : null}

        {detail.relatedEvents.length > 0 ? (
          <section data-testid="policy-related-events">
            <h3 className="mb-2 text-sm font-medium">关联政策发布事件</h3>
            <ul className="flex flex-col gap-2">
              {detail.relatedEvents.map((event) => (
                <RelatedEvent key={event.id} event={event} />
              ))}
            </ul>
          </section>
        ) : null}
      </CardContent>
    </Card>
  );
}
