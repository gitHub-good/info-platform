import { useCallback, useEffect, useRef, useState } from 'react';
import { ArrowRight } from 'lucide-react';
import { getEvents } from '@/api/eventStream';
import { getFeedDashboard } from '@/api/feedDashboard';
import { getIndustryHeatBoard } from '@/api/industryHeat';
import { getPipelineStatus } from '@/api/pipelineStatus';
import { getRecommendationCards } from '@/api/recommendation';
import { ApiError } from '@/api/http';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { formatPct } from '@/lib/format';
import { navigate } from '@/lib/navigation';
import {
  EVENT_TYPE_LABELS,
  IMPORTANCE_LABELS,
  labelOf,
} from '@/types/industryHeat';
import type { EventCard } from '@/types/eventStream';
import type { IndustryHeatBoardView } from '@/types/industryHeat';
import type { RecommendationCardItem } from '@/types/recommendation';
import type { PipelineGuardLevel } from '@/types/pipelineStatus';

// V2.0 工作台（M18 T156，REQ 拍板三 C 层）：概览页新增区块——
// 行业热度 Top5 摘要 + 最新推荐卡 ≤2 + 最新事件 ≤3 + 大盘健康摘要。
// 复用既有 API（热度榜/推荐中心/事件流/大盘/管道状态），30 秒刷新沿大盘机制（document.hidden 暂停）；
// 四块各自三态（骨架/错误可重试/空态），单块加载失败降级不影响其余块与页面既有五卡（零回归）。
// M19 T164 行级跳转（trace-v1 C 级溯源链）：热度 Top5 行带 industry 参数跳热度榜下钻、
// 推荐行跳推荐中心 focus 定位、事件行跳事件流；块级「直达」链接保留，大盘健康保持块级（REQ 故事 5 场景 4/5）。

/** 自动刷新间隔（沿大盘 30 秒机制，蓝图故事 7 场景 1 口径）。 */
const AUTO_REFRESH_MILLIS = 30_000;

/** 热度摘要取 Top N。 */
const HEAT_TOP_N = 5;

/** 推荐摘要取最新 N 张。 */
const REC_LIMIT = 2;

/** 事件摘要取最新 N 条。 */
const EVENT_LIMIT = 3;

function messageOf(err: unknown, fallback: string): string {
  return err instanceof ApiError ? err.msg : fallback;
}

/** ISO → MM-dd HH:mm 短格式（空/非法回 '—'）。 */
function formatTime(iso: string | null): string {
  if (!iso) return '—';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '—';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** 微元 → 元百分比水位（预算 0 防除零）。 */
function pctOf(used: number, budget: number): number {
  if (budget <= 0) return 0;
  return Math.min(100, Math.round((used / budget) * 100));
}

/** 块级三态外壳（标题 + 直达链接 + 骨架/错误重试/内容）。 */
function Block({
  testId,
  title,
  link,
  linkTestId,
  loading,
  error,
  onRetry,
  retryTestId,
  empty,
  children,
}: {
  testId: string;
  title: string;
  link: string;
  linkTestId: string;
  loading: boolean;
  error: string | null;
  onRetry: () => void;
  retryTestId: string;
  empty: boolean;
  children: React.ReactNode;
}) {
  return (
    <Card size="sm" data-testid={testId}>
      <CardHeader>
        <CardTitle className="flex items-center justify-between text-sm font-medium">
          {title}
          <a
            href={link}
            className="inline-flex items-center gap-0.5 text-xs font-normal text-muted-foreground transition-colors hover:text-foreground"
            data-testid={linkTestId}
          >
            直达
            <ArrowRight className="size-3" aria-hidden="true" />
          </a>
        </CardTitle>
      </CardHeader>
      <CardContent className="flex min-h-24 flex-col gap-1.5">
        {loading ? (
          <Skeleton className="h-20 w-full" data-testid={`${testId}-loading`} />
        ) : error ? (
          <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground" role="status">
            <span data-testid={`${testId}-error`}>{error}</span>
            <button
              type="button"
              className="text-primary underline underline-offset-4"
              onClick={onRetry}
              data-testid={retryTestId}
            >
              重试
            </button>
          </div>
        ) : empty ? (
          <p className="py-4 text-center text-sm text-muted-foreground" data-testid={`${testId}-empty`}>
            暂无数据
          </p>
        ) : (
          children
        )}
      </CardContent>
    </Card>
  );
}

/** 块状态速记（独立三块数据各自持有）。 */
interface BlockState<T> {
  data: T | null;
  loading: boolean;
  error: string | null;
}

const initialBlock = <T,>(): BlockState<T> => ({ data: null, loading: true, error: null });

export function WorkbenchPanel() {
  const [heat, setHeat] = useState<BlockState<IndustryHeatBoardView>>(initialBlock);
  const [recs, setRecs] = useState<BlockState<RecommendationCardItem[]>>(initialBlock);
  const [events, setEvents] = useState<BlockState<EventCard[]>>(initialBlock);
  const [health, setHealth] = useState<BlockState<{
    activeSourceCount: number;
    enabledCount: number;
    todayNewCount: number;
    costPct: number;
    level: PipelineGuardLevel;
  }>>(initialBlock);
  const abortRef = useRef<AbortController | null>(null);

  const loadAll = useCallback(async () => {
    abortRef.current?.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    void (async () => {
      setHeat((prev) => ({ ...prev, loading: true, error: null }));
      try {
        const board = await getIndustryHeatBoard('D7', ctrl.signal);
        if (!ctrl.signal.aborted) setHeat({ data: board, loading: false, error: null });
      } catch (err) {
        if (!ctrl.signal.aborted)
          setHeat({ data: null, loading: false, error: messageOf(err, '热度摘要加载失败') });
      }
    })();
    void (async () => {
      setRecs((prev) => ({ ...prev, loading: true, error: null }));
      try {
        const page = await getRecommendationCards({ limit: REC_LIMIT }, ctrl.signal);
        if (!ctrl.signal.aborted) setRecs({ data: page.items, loading: false, error: null });
      } catch (err) {
        if (!ctrl.signal.aborted)
          setRecs({ data: null, loading: false, error: messageOf(err, '推荐摘要加载失败') });
      }
    })();
    void (async () => {
      setEvents((prev) => ({ ...prev, loading: true, error: null }));
      try {
        const view = await getEvents({ limit: EVENT_LIMIT }, ctrl.signal);
        if (!ctrl.signal.aborted) setEvents({ data: view.items, loading: false, error: null });
      } catch (err) {
        if (!ctrl.signal.aborted)
          setEvents({ data: null, loading: false, error: messageOf(err, '事件摘要加载失败') });
      }
    })();
    void (async () => {
      setHealth((prev) => ({ ...prev, loading: true, error: null }));
      try {
        const [dashboard, pipeline] = await Promise.all([
          getFeedDashboard(ctrl.signal),
          getPipelineStatus(ctrl.signal),
        ]);
        if (ctrl.signal.aborted) return;
        const enabledCount = dashboard.sources.filter((row) => row.enabled && !row.deleted).length;
        setHealth({
          data: {
            activeSourceCount: dashboard.global.activeSourceCount,
            enabledCount,
            todayNewCount: dashboard.global.todayNewCount,
            costPct: pctOf(pipeline.todayCostMicros, pipeline.budgetMicros),
            level: pipeline.level,
          },
          loading: false,
          error: null,
        });
      } catch (err) {
        if (!ctrl.signal.aborted)
          setHealth({ data: null, loading: false, error: messageOf(err, '大盘健康摘要加载失败') });
      }
    })();
  }, []);

  useEffect(() => {
    void loadAll();
    return () => abortRef.current?.abort();
  }, [loadAll]);

  // 30s 刷新：document.hidden 暂停（沿大盘机制）
  useEffect(() => {
    const timer = window.setInterval(() => {
      if (document.hidden) return;
      void loadAll();
    }, AUTO_REFRESH_MILLIS);
    return () => window.clearInterval(timer);
  }, [loadAll]);

  const heatRows = heat.data?.industries.slice(0, HEAT_TOP_N) ?? [];

  return (
    <section aria-label="V2.0 工作台" data-testid="overview-workbench">
      <h2 className="mb-2 text-sm font-medium text-muted-foreground">V2.0 工作台</h2>
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Block
          testId="workbench-heat"
          title="行业热度 Top5（7 天）"
          link="#/industry-heat"
          linkTestId="workbench-heat-link"
          loading={heat.loading}
          error={heat.error}
          onRetry={() => void loadAll()}
          retryTestId="workbench-heat-retry"
          empty={heatRows.length === 0}
        >
          {heatRows.map((row, index) => (
            <button
              key={row.industry}
              type="button"
              className="flex w-full cursor-pointer items-center gap-2 rounded-sm text-left text-sm transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
              data-testid={`workbench-heat-row-${row.industry}`}
              title={`跳转行业热度并展开「${row.industry}」下钻`}
              onClick={() =>
                navigate(`/industry-heat?industry=${encodeURIComponent(row.industry)}`)
              }
            >
              <span className="w-4 text-right text-xs text-muted-foreground tabular-nums">{index + 1}</span>
              <span className="min-w-0 flex-1 truncate">{row.industry}</span>
              <span className="text-xs text-muted-foreground tabular-nums">{row.heatScore} 分</span>
              <span
                className={
                  row.deltaPct > 0
                    ? 'text-xs font-medium text-red-500 tabular-nums'
                    : row.deltaPct < 0
                      ? 'text-xs font-medium text-green-500 tabular-nums'
                      : 'text-xs text-muted-foreground tabular-nums'
                }
              >
                {row.deltaPct > 0 ? '+' : ''}
                {formatPct(row.deltaPct, 1)}
              </span>
            </button>
          ))}
        </Block>
        <Block
          testId="workbench-recommendations"
          title="最新推荐"
          link="#/recommendations"
          linkTestId="workbench-recommendations-link"
          loading={recs.loading}
          error={recs.error}
          onRetry={() => void loadAll()}
          retryTestId="workbench-recommendations-retry"
          empty={(recs.data?.length ?? 0) === 0}
        >
          {(recs.data ?? []).map((card) => (
            <button
              key={card.id}
              type="button"
              className="flex w-full cursor-pointer items-start gap-2 rounded-sm text-left text-sm transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
              data-testid={`workbench-rec-${card.id}`}
              title="跳转推荐中心并定位该卡"
              onClick={() => navigate(`/recommendations?focus=${card.id}`)}
            >
              <Badge className="bg-amber-500/15 text-amber-400" variant="ghost">
                {labelOf(IMPORTANCE_LABELS, card.importance)}
              </Badge>
              <span className="min-w-0 flex-1 truncate" title={card.summary ?? undefined}>
                {card.summary ?? card.newsTitle ?? `事件 #${card.eventId}`}
              </span>
              <span className="shrink-0 text-xs text-muted-foreground">
                {labelOf(EVENT_TYPE_LABELS, card.eventType)}
              </span>
            </button>
          ))}
        </Block>
        <Block
          testId="workbench-events"
          title="最新事件"
          link="#/events"
          linkTestId="workbench-events-link"
          loading={events.loading}
          error={events.error}
          onRetry={() => void loadAll()}
          retryTestId="workbench-events-retry"
          empty={(events.data?.length ?? 0) === 0}
        >
          {(events.data ?? []).map((event) => (
            <button
              key={event.id}
              type="button"
              className="flex w-full cursor-pointer items-start gap-2 rounded-sm text-left text-sm transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
              data-testid={`workbench-event-${event.id}`}
              title="跳转事件流查看详情"
              onClick={() => navigate('/events')}
            >
              <Badge
                variant="ghost"
                className={
                  event.importance === 'HIGH'
                    ? 'bg-rose-500/15 text-rose-400'
                    : 'bg-sky-500/15 text-sky-400'
                }
              >
                {labelOf(IMPORTANCE_LABELS, event.importance)}
              </Badge>
              <span className="min-w-0 flex-1 truncate" title={event.summary}>
                {event.summary}
              </span>
              <span className="shrink-0 text-xs text-muted-foreground">
                {formatTime(event.eventTime)}
              </span>
            </button>
          ))}
        </Block>
        <Block
          testId="workbench-health"
          title="大盘健康"
          link="#/feed-dashboard"
          linkTestId="workbench-health-link"
          loading={health.loading}
          error={health.error}
          onRetry={() => void loadAll()}
          retryTestId="workbench-health-retry"
          empty={health.data == null}
        >
          {health.data ? (
            <div className="flex flex-col gap-1.5 text-sm" data-testid="workbench-health-body">
              <div className="flex items-center justify-between">
                <span className="text-muted-foreground">源在线</span>
                <span className="tabular-nums" data-testid="workbench-health-sources">
                  {health.data.activeSourceCount}/{health.data.enabledCount}
                </span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-muted-foreground">今日入库</span>
                <span className="tabular-nums" data-testid="workbench-health-intake">
                  {health.data.todayNewCount} 条
                </span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-muted-foreground">管道成本水位</span>
                <span className="flex items-center gap-1.5">
                  <span className="tabular-nums" data-testid="workbench-health-cost">
                    {health.data.costPct}%
                  </span>
                  <Badge
                    variant="ghost"
                    className={
                      health.data.level === 'NORMAL'
                        ? 'bg-emerald-500/15 text-emerald-400'
                        : health.data.level === 'DEGRADED'
                          ? 'bg-amber-500/15 text-amber-400'
                          : 'bg-rose-500/15 text-rose-400'
                    }
                    data-testid="workbench-health-level"
                  >
                    {health.data.level === 'NORMAL'
                      ? '正常'
                      : health.data.level === 'DEGRADED'
                        ? '降级'
                        : '熔断'}
                  </Badge>
                </span>
              </div>
            </div>
          ) : null}
        </Block>
      </div>
    </section>
  );
}

export default WorkbenchPanel;
