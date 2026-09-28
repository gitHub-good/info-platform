import { useCallback, useEffect, useRef, useState } from 'react';
import { AlertTriangle, ArrowRight } from 'lucide-react';
import { getEvents } from '@/api/eventStream';
import { getIndustryHeatBoard } from '@/api/industryHeat';
import { getOverview } from '@/api/overview';
import { ApiError } from '@/api/http';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { HealthStatusStrip } from '@/components/overview/HealthStatusStrip';
import { RecommendationCard } from '@/components/overview/RecommendationCard';
import { StatCard } from '@/components/overview/StatCard';
import { Top10DigestCard } from '@/components/overview/Top10DigestCard';
import { formatPct } from '@/lib/format';
import { navigate } from '@/lib/navigation';
import { IMPORTANCE_LABELS, labelOf } from '@/types/industryHeat';
import type { EventCard } from '@/types/eventStream';
import type { IndustryHeatBoardView } from '@/types/industryHeat';
import type { OverviewSourceHealth, OverviewView } from '@/types/overview';

// 概览页（M25 T223 V3.0 概览重组 11→6，UI 方案 §2）：打开即见结论。
// - 第一屏：今日推荐主位（lg 2/3 宽，RecommendationCard 保留不动）+ 全市场 Top10 精华（1/3，新建自管三态）；
// - 第二排：最新事件 3 条 / 行业热度 Top5（沿 WorkbenchPanel 原样迁移，行级下钻保留）+ 今日异动（StatCard，
//   含行情源异常警示体检 E2 联动）；
// - 底部：平台健康状态条（五段单行，替换平台健康三卡与工作台大盘健康块）。
// - 移除（拍板三）：最新推荐摘要（与主位重复）与最新政策卡（承接三路：资讯库 L1 预填/信息流 POLICY/详情政策分区）；
//   WorkbenchPanel 拆解删除，30s 自动刷新 + document.hidden 暂停 + 单块降级语义由迁移块与新区块继承。
// - 政策取数仍在 /overview 聚合返回（契约不动），前端不再消费展示。

/** 自动刷新间隔（沿大盘 30 秒机制）。 */
const AUTO_REFRESH_MILLIS = 30_000;

/** 热度摘要取 Top N。 */
const HEAT_TOP_N = 5;

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

/** 行情源（QUOTE）是否异常：最近事件存在且非 OK（体检 E2——异动检测依赖行情源，异常时警示监控受限）。 */
function isQuoteSourceUnhealthy(sources: OverviewSourceHealth[]): boolean {
  const quote = sources.find((source) => source.sourceCode === 'QUOTE');
  return quote != null && quote.lastEventType != null && quote.lastEventType !== 'OK';
}

/** 数据源异常警示条（叠加在今日异动卡内，点击仍整卡跳自选清单）。 */
function AnomalySourceWarning() {
  return (
    <div
      className="flex items-center gap-1.5 rounded-md bg-amber-500/10 px-2 py-1.5 text-xs text-amber-400"
      data-testid="anomaly-source-warning"
      role="status"
    >
      <AlertTriangle className="size-3.5 shrink-0" aria-hidden="true" />
      行情源异常，异动监控受限
    </div>
  );
}

/** 块级三态外壳（标题 + 直达链接 + 骨架/错误重试/内容；沿 WorkbenchPanel Block 原样迁移）。 */
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
        ) : error && empty ? (
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

/** 块状态速记（热度/事件迁移块各自持有）。 */
interface BlockState<T> {
  data: T | null;
  loading: boolean;
  error: string | null;
}

const initialBlock = <T,>(): BlockState<T> => ({ data: null, loading: true, error: null });

/**
 * 概览仪表盘页（登录后默认落地页）。V3.0 重组为 6 个信息块（5 张内容卡 + 1 条状态条），
 * 1080p 第一屏容纳 P0/P1 全部内容；运维信息降为底部单行状态条。
 */
export function Overview() {
  const [data, setData] = useState<OverviewView | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [heat, setHeat] = useState<BlockState<IndustryHeatBoardView>>(initialBlock);
  const [events, setEvents] = useState<BlockState<EventCard[]>>(initialBlock);

  // 卸载/重挂载时中止在途请求，避免旧响应覆盖新结果
  const abortRef = useRef<AbortController | null>(null);
  const blockAbortRef = useRef<AbortController | null>(null);

  const load = useCallback(async (silent: boolean) => {
    abortRef.current?.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    if (!silent) {
      setLoading(true);
      setError(null);
    }
    try {
      const view = await getOverview(ctrl.signal);
      if (ctrl.signal.aborted) return;
      setData(view);
    } catch (err) {
      if (ctrl.signal.aborted) return;
      // 静默轮询失败：已有数据保留，不闪整页错误
      if (!silent) setError(messageOf(err, '概览数据加载失败'));
    } finally {
      if (!ctrl.signal.aborted && !silent) setLoading(false);
    }
  }, []);

  // 迁移块加载（热度 + 事件；单块失败独立降级，不拖累其余块）
  const loadBlocks = useCallback(async (silent: boolean) => {
    blockAbortRef.current?.abort();
    const ctrl = new AbortController();
    blockAbortRef.current = ctrl;
    if (!silent) {
      setHeat((prev) => ({ ...prev, loading: true, error: null }));
      setEvents((prev) => ({ ...prev, loading: true, error: null }));
    }
    void (async () => {
      try {
        const board = await getIndustryHeatBoard('D7', ctrl.signal);
        if (!ctrl.signal.aborted) setHeat({ data: board, loading: false, error: null });
      } catch (err) {
        if (!ctrl.signal.aborted && !silent)
          setHeat({ data: null, loading: false, error: messageOf(err, '热度摘要加载失败') });
      }
    })();
    void (async () => {
      try {
        const view = await getEvents({ limit: EVENT_LIMIT }, ctrl.signal);
        if (!ctrl.signal.aborted) setEvents({ data: view.items, loading: false, error: null });
      } catch (err) {
        if (!ctrl.signal.aborted && !silent)
          setEvents({ data: null, loading: false, error: messageOf(err, '事件摘要加载失败') });
      }
    })();
  }, []);

  useEffect(() => {
    void load(false);
    void loadBlocks(false);
    return () => {
      abortRef.current?.abort();
      blockAbortRef.current?.abort();
    };
  }, [load, loadBlocks]);

  // 30s 刷新：document.hidden 暂停（沿大盘机制；Top10 精华卡与健康状态条组件内自管同节奏）
  useEffect(() => {
    const timer = window.setInterval(() => {
      if (document.hidden) return;
      void load(true);
      void loadBlocks(true);
    }, AUTO_REFRESH_MILLIS);
    return () => window.clearInterval(timer);
  }, [load, loadBlocks]);

  const sources = data?.sourceHealth ?? [];
  const quoteUnhealthy = isQuoteSourceUnhealthy(sources);
  const heatRows = heat.data?.industries.slice(0, HEAT_TOP_N) ?? [];

  return (
    <main className="mx-auto w-full max-w-6xl p-4 sm:p-6" data-testid="overview-page">
      <header className="mb-4">
        <h1 className="text-xl font-medium">概览</h1>
        <p className="mt-1 text-sm text-muted-foreground">今日值得看的动态，与平台健康度</p>
      </header>

      {error && data == null ? (
        <div className="flex flex-col items-start gap-2" data-testid="overview-error">
          <p className="text-sm text-destructive" role="alert">
            {error}
          </p>
          <Button variant="outline" size="sm" onClick={() => void load(false)} data-testid="overview-retry">
            重试
          </Button>
        </div>
      ) : (
        <div className="flex flex-col gap-6">
          {/* —— 第一屏：今日推荐主位（2/3）+ 全市场 Top10 精华（1/3） —— */}
          <section aria-label="今日" data-testid="overview-today">
            <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
              <div className="lg:col-span-2">
                <RecommendationCard />
              </div>
              <Top10DigestCard />
            </div>
          </section>

          {/* —— 第二排：最新事件 3 条 / 热度 Top5 / 今日异动 —— */}
          <section aria-label="最新动态" data-testid="overview-dynamics">
            <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
              <Block
                testId="overview-events"
                title="最新事件"
                link="#/events"
                linkTestId="overview-events-link"
                loading={events.loading}
                error={events.error}
                onRetry={() => void loadBlocks(false)}
                retryTestId="overview-events-retry"
                empty={(events.data?.length ?? 0) === 0}
              >
                {(events.data ?? []).map((event) => (
                  <button
                    key={event.id}
                    type="button"
                    className="flex w-full cursor-pointer items-start gap-2 rounded-sm text-left text-sm transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
                    data-testid={`overview-event-${event.id}`}
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
                testId="overview-heat"
                title="行业热度 Top5（7 天）"
                link="#/industry-heat"
                linkTestId="overview-heat-link"
                loading={heat.loading}
                error={heat.error}
                onRetry={() => void loadBlocks(false)}
                retryTestId="overview-heat-retry"
                empty={heatRows.length === 0}
              >
                {heatRows.map((row, index) => (
                  <button
                    key={row.industry}
                    type="button"
                    className="flex w-full cursor-pointer items-center gap-2 rounded-sm text-left text-sm transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
                    data-testid={`overview-heat-row-${row.industry}`}
                    title={`跳转行业热度并展开「${row.industry}」下钻`}
                    onClick={() =>
                      navigate(`/industry-heat?industry=${encodeURIComponent(row.industry)}`)
                    }
                  >
                    <span className="w-4 text-right text-xs text-muted-foreground tabular-nums">
                      {index + 1}
                    </span>
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
              {loading ? (
                <Skeleton className="h-28 w-full" data-testid="overview-loading" />
              ) : data ? (
                <StatCard
                  title="今日异动"
                  subtitle="今日"
                  value={`${data.anomalyToday.count} 条`}
                  extra={quoteUnhealthy ? <AnomalySourceWarning /> : undefined}
                  href="#/watchlists"
                  error={data.anomalyToday.error}
                  onRetry={() => void load(false)}
                  testId="anomaly"
                />
              ) : null}
            </div>
          </section>

          {/* —— 底部：平台健康状态条（单行，替换平台健康三卡） —— */}
          <section aria-label="平台健康" data-testid="overview-health">
            <HealthStatusStrip
              llmToday={
                data?.llmToday
                  ? { status: data.llmToday.status, error: data.llmToday.error }
                  : null
              }
              jobHealth={
                data?.jobHealth
                  ? { failed: data.jobHealth.windowFailed, error: data.jobHealth.error }
                  : null
              }
            />
          </section>
        </div>
      )}
    </main>
  );
}

export default Overview;
