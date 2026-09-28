import { useCallback, useEffect, useRef, useState } from 'react';
import { ChevronDown } from 'lucide-react';
import { ApiError } from '@/api/http';
import {
  getIndustryHeatBoard,
  getIndustryHeatItems,
  getIndustryReportDetail,
  getIndustryReports,
  getIndustryWeeklyReportDetail,
  getIndustryWeeklyReports,
  retryIndustryReport,
  retryIndustryWeeklyReport,
} from '@/api/industryHeat';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { EmptyState } from '@/components/ui/EmptyState';
import { Skeleton } from '@/components/ui/skeleton';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import {
  directionTextClass,
  directionToneClass,
  formatDateTime,
  formatNumber,
  formatPct,
  statusToneClass,
} from '@/lib/format';
import { currentRoute, queryOf } from '@/lib/navigation';
import { cn } from '@/lib/utils';
import {
  DIRECTION_LABELS,
  EVENT_TYPE_LABELS,
  IMPORTANCE_LABELS,
  labelOf,
  type GuardLevel,
  type HeatWindow,
  type IndustryHeatBoardView,
  type IndustryHeatItem,
  type IndustryItemsType,
  type IndustryReportDetailView,
  type IndustryReportListView,
  type IndustryWeeklyReportDetailView,
  type IndustryWeeklyReportListView,
} from '@/types/industryHeat';

// 行业热度与日报页（M15 T126，#/industry-heat 全站第 16 页——方案 §4.8 + REQ 故事 2/3）。
// 双 Tab：热度榜（24h/7d 窗口切换、31 行业榜单、环比徽章沿 A 股惯例、行点击页内下钻对账、
// 降级/熔断横幅与 pipeline/status 三处同源、口径脚注）/ 行业日报（列表回看 + 详情 + FAILED 重试 202 轻轮询）。
// 三态齐备：加载骨架 / 空态引导（首日无日报）/ 错误重试；受保护接口 401 由 http 层统一跳登录。

/** 日报重试轻轮询缺省间隔（3s，测试可缩短加速）。 */
const DEFAULT_RETRY_POLL_MS = 3_000;
/** 重试轮询轮数上限（≈45s，防久挂；终态由列表 status 变化收敛）。 */
const RETRY_POLL_MAX_TICKS = 15;

function messageOf(err: unknown, fallback: string): string {
  return err instanceof ApiError ? err.msg : fallback;
}

/** 护栏横幅（DEGRADED 黄 / FUSED 红，数据面 = 榜单 pipeline.level 与 /pipeline/status 同源）。 */
function PipelineBanner({ level }: { level: GuardLevel }) {
  if (level === 'NORMAL') return null;
  const fused = level === 'FUSED';
  return (
    <div
      role="status"
      data-testid="heat-banner"
      className={cn(
        'rounded-lg border px-3 py-2 text-sm',
        fused
          ? 'border-rose-500/30 bg-rose-500/10 text-rose-400'
          : 'border-amber-500/30 bg-amber-500/10 text-amber-400',
      )}
    >
      {fused
        ? 'AI 管道已熔断：今日管道暂停（L1/L2 全跳），次日预算重置后自动恢复并补跑；榜单为既有快照统计。'
        : 'AI 管道降级中：L2 事件提取与日报 AI 叙述暂缓，仅保 L0+L1；热度榜为纯统计口径不受影响。'}
    </div>
  );
}

/** 环比徽章（A 股惯例涨红跌绿——方向轨单点 directionTextClass，T229；prev=0 记 100 由后端口径产出）。 */
function DeltaBadge({ deltaPct, testId }: { deltaPct: number; testId: string }) {
  const tone =
    deltaPct > 0
      ? directionTextClass('up')
      : deltaPct < 0
        ? directionTextClass('down')
        : 'text-muted-foreground';
  return (
    <span className={cn('text-xs font-medium tabular-nums', tone)} data-testid={testId}>
      {deltaPct > 0 ? '+' : ''}
      {formatPct(deltaPct)}
    </span>
  );
}

/** 事件方向徽章（利好红 / 利空绿 / 中性灰，A 股惯例——方向轨单点 directionToneClass，T229）。 */
function DirectionBadge({ direction }: { direction: string | null }) {
  const label = labelOf(DIRECTION_LABELS, direction);
  const tone =
    direction === 'BULLISH'
      ? directionToneClass('up')
      : direction === 'BEARISH'
        ? directionToneClass('down')
        : directionToneClass('flat');
  return <Badge className={tone}>{label}</Badge>;
}

/** T163 trace-v1 溯源行：来源灰字 + 「查看原文」外链（历史数据无 newsUrl 时仅来源——判空降级不渲染死链）。 */
function TraceSourceRow({
  sourceName,
  newsUrl,
  testId,
}: {
  sourceName?: string | null;
  newsUrl?: string | null;
  testId: string;
}) {
  if (!sourceName && !newsUrl) return null;
  return (
    <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground" data-testid={testId}>
      {sourceName ? <span className="min-w-0 truncate">来源：{sourceName}</span> : null}
      {newsUrl ? (
        <a
          href={newsUrl}
          target="_blank"
          rel="noreferrer"
          data-testid={`${testId}-link`}
          className="shrink-0 underline underline-offset-2 hover:text-foreground"
        >
          查看原文
        </a>
      ) : null}
    </div>
  );
}

/** T163 历史报告脚注：任一事件缺 newsUrl（上线前物化）时提示外链溯源口径（quote B 级兜底，不回填）。 */
function TraceFootnote({ anyMissingUrl }: { anyMissingUrl: boolean }) {
  if (!anyMissingUrl) return null;
  return (
    <p className="mt-1 text-xs text-muted-foreground" data-testid="report-trace-footnote">
      注：新版报告起支持「查看原文」外链溯源；历史报告保留原文引用兜底（不回填）。
    </p>
  );
}

// —— 热度榜 Tab：下钻面板 ——

interface DrillDownProps {
  industry: string;
  window: HeatWindow;
}

/** 行业下钻（news / events 双清单 + beforeId 游标加载更多；total 与榜单计数对账）。 */
function IndustryDrillDown({ industry, window }: DrillDownProps) {
  const [type, setType] = useState<IndustryItemsType>('news');
  const [items, setItems] = useState<IndustryHeatItem[]>([]);
  const [total, setTotal] = useState(0);
  const [nextBeforeId, setNextBeforeId] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  // 双清单各自游标互不污染：切类型即整段重置
  const stateRef = useRef({ type, nextBeforeId });
  stateRef.current = { type, nextBeforeId };

  const fetchPage = useCallback(
    async (listType: IndustryItemsType, beforeId?: number) => {
      abortRef.current?.abort();
      const ctrl = new AbortController();
      abortRef.current = ctrl;
      if (beforeId == null) setLoading(true);
      else setLoadingMore(true);
      try {
        const view = await getIndustryHeatItems(industry, { window, type: listType, beforeId }, ctrl.signal);
        if (ctrl.signal.aborted) return;
        if (stateRef.current.type !== listType) return; // 竞态守卫：响应到达时已切类型则丢弃
        setTotal(view.total);
        setNextBeforeId(view.nextBeforeId);
        setItems((prev) => (beforeId == null ? view.items : [...prev, ...view.items]));
        setError(null);
      } catch (err) {
        if (ctrl.signal.aborted) return;
        setError(messageOf(err, '下钻清单加载失败'));
      } finally {
        if (!ctrl.signal.aborted) {
          setLoading(false);
          setLoadingMore(false);
        }
      }
    },
    [industry, window],
  );

  useEffect(() => {
    void fetchPage(type);
    return () => abortRef.current?.abort();
  }, [fetchPage, type]);

  const switchType = (next: IndustryItemsType) => {
    if (next === type) return;
    setItems([]);
    setTotal(0);
    setNextBeforeId(null);
    setType(next);
  };

  return (
    <div
      className="flex flex-col gap-2 rounded-lg border bg-muted/20 p-3"
      data-testid={`heat-drilldown-${industry}`}
    >
      <div className="flex flex-wrap items-center gap-2">
        <div className="inline-flex overflow-hidden rounded-md border" data-testid={`drill-switch-${industry}`}>
          <Button
            type="button"
            size="sm"
            variant={type === 'news' ? 'default' : 'ghost'}
            className="rounded-none"
            onClick={() => switchType('news')}
            data-testid={`drill-type-news-${industry}`}
          >
            行业资讯
          </Button>
          <Button
            type="button"
            size="sm"
            variant={type === 'events' ? 'default' : 'ghost'}
            className="rounded-none"
            onClick={() => switchType('events')}
            data-testid={`drill-type-events-${industry}`}
          >
            行业事件
          </Button>
        </div>
        <span className="text-xs text-muted-foreground" data-testid={`drill-total-${industry}`}>
          共 {total} 条（与榜单计数对账）
        </span>
      </div>

      {loading ? (
        <div className="flex flex-col gap-2" data-testid={`drill-loading-${industry}`}>
          <Skeleton className="h-10 w-full" />
          <Skeleton className="h-10 w-full" />
        </div>
      ) : error ? (
        <div className="flex flex-col items-start gap-1">
          <p className="text-xs text-destructive" role="alert">
            {error}
          </p>
          <Button
            variant="outline"
            size="sm"
            onClick={() => void fetchPage(type)}
            data-testid={`drill-retry-${industry}`}
          >
            重试
          </Button>
        </div>
      ) : items.length === 0 ? (
        <p className="py-4 text-center text-xs text-muted-foreground">
          窗口内暂无{type === 'news' ? '条目' : '事件'}
        </p>
      ) : (
        <div className="flex flex-col divide-y" data-testid={`drill-list-${industry}`}>
          {items.map((item) =>
            type === 'news' ? (
              <div
                key={`news-${item.newsId}`}
                className="flex flex-wrap items-center gap-x-3 gap-y-1 py-2 text-sm"
                data-testid={`drill-item-${item.newsId}`}
              >
                {item.url ? (
                  // T162 trace-v1 A 级：标题即原文外链（新窗口直达源站）；无 url 行降级纯文本
                  <a
                    href={item.url}
                    target="_blank"
                    rel="noreferrer"
                    className="min-w-0 flex-1 truncate underline decoration-border underline-offset-2 hover:text-foreground"
                    title={item.title ?? undefined}
                    data-testid={`drill-news-link-${item.newsId}`}
                  >
                    {item.title ?? `#${item.newsId}`}
                  </a>
                ) : (
                  <span className="min-w-0 flex-1 truncate" title={item.title ?? undefined}>
                    {item.title ?? `#${item.newsId}`}
                  </span>
                )}
                {item.hasEvent ? (
                  <Badge className="bg-violet-500/15 text-violet-400" data-testid={`drill-has-event-${item.newsId}`}>
                    含事件
                  </Badge>
                ) : null}
                <span className="shrink-0 text-xs text-muted-foreground">{item.sourceName ?? '--'}</span>
                <span className="shrink-0 text-xs text-muted-foreground">
                  {formatDateTime(item.publishedAt)}
                </span>
              </div>
            ) : (
              <div
                key={`event-${item.eventId ?? item.newsId}`}
                className="flex flex-col gap-1 py-2 text-sm"
                data-testid={`drill-item-${item.eventId ?? item.newsId}`}
              >
                <div className="flex flex-wrap items-center gap-2">
                  <Badge className="bg-sky-500/15 text-sky-400">
                    {labelOf(EVENT_TYPE_LABELS, item.eventType)}
                  </Badge>
                  <DirectionBadge direction={item.direction} />
                  <Badge className="bg-amber-500/15 text-amber-400">
                    重要度 {labelOf(IMPORTANCE_LABELS, item.importance)}
                  </Badge>
                  <span className="ml-auto text-xs text-muted-foreground">
                    {formatDateTime(item.eventTime)}
                  </span>
                </div>
                <p className="font-medium">{item.title ?? `#${item.newsId}`}</p>
                {item.summary ? (
                  <p className="text-xs text-muted-foreground">{item.summary}</p>
                ) : null}
                {item.quote ? (
                  <p
                    className="border-l-2 border-border pl-2 text-xs text-muted-foreground"
                    data-testid={`drill-event-quote-${item.eventId ?? item.newsId}`}
                  >
                    原文引用：「{item.quote}」
                  </p>
                ) : null}
                <TraceSourceRow
                  sourceName={item.sourceName}
                  newsUrl={item.newsUrl}
                  testId={`drill-event-source-${item.eventId ?? item.newsId}`}
                />
              </div>
            ),
          )}
          {nextBeforeId != null ? (
            <Button
              variant="ghost"
              size="sm"
              className="self-center"
              disabled={loadingMore}
              onClick={() => void fetchPage(type, nextBeforeId)}
              data-testid={`drill-load-more-${industry}`}
            >
              {loadingMore ? '加载中…' : '加载更多'}
            </Button>
          ) : null}
        </div>
      )}
    </div>
  );
}

// —— 热度榜 Tab ——

/** 热度榜（窗口切换 + 榜单行 + 页内下钻 + 护栏横幅 + 口径脚注；focusIndustry 为 C 级溯源链直达参数）。 */
function HeatBoardTab({ focusIndustry }: { focusIndustry: string | null }) {
  const [window, setWindow] = useState<HeatWindow>('H24');
  const [board, setBoard] = useState<IndustryHeatBoardView | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  const load = useCallback(
    async (target: HeatWindow) => {
      abortRef.current?.abort();
      const ctrl = new AbortController();
      abortRef.current = ctrl;
      try {
        const data = await getIndustryHeatBoard(target, ctrl.signal);
        if (ctrl.signal.aborted) return;
        setBoard(data);
        setError(null);
      } catch (err) {
        if (ctrl.signal.aborted) return;
        setError(messageOf(err, '热度榜加载失败'));
      } finally {
        if (!ctrl.signal.aborted) setLoading(false);
      }
    },
    [],
  );

  useEffect(() => {
    setLoading(true);
    // C 级溯源链落地：#/industry-heat?industry=X（报告行业名/工作台 Top5 行）→ 展开该行业下钻；
    // 切窗重置后若直达参数仍在（URL 未变）则保持展开（focus 粘性）
    setExpanded(focusIndustry ?? null);
    void load(window);
    return () => abortRef.current?.abort();
  }, [load, window, focusIndustry]);

  const rows = board?.industries ?? [];
  const topScore = rows.length > 0 ? Math.max(...rows.map((row) => row.heatScore)) : 0;

  return (
    <div className="flex flex-col gap-3">
      {board ? <PipelineBanner level={board.pipeline.level} /> : null}

      <div className="flex flex-wrap items-center gap-2">
        <div className="inline-flex overflow-hidden rounded-md border" data-testid="heat-window-switch">
          <Button
            type="button"
            size="sm"
            variant={window === 'H24' ? 'default' : 'ghost'}
            className="rounded-none"
            onClick={() => setWindow('H24')}
            data-testid="heat-window-h24"
          >
            24 小时
          </Button>
          <Button
            type="button"
            size="sm"
            variant={window === 'D7' ? 'default' : 'ghost'}
            className="rounded-none"
            onClick={() => setWindow('D7')}
            data-testid="heat-window-d7"
          >
            7 天
          </Button>
        </div>
        <span className="text-xs text-muted-foreground">31 个申万一级行业 · 0 分沉底 · 每日 30 分钟快照</span>
      </div>

      {loading ? (
        <div className="flex flex-col gap-2" data-testid="heat-loading">
          {Array.from({ length: 6 }, (_, i) => (
            <Skeleton key={i} className="h-10 w-full" />
          ))}
        </div>
      ) : error && !board ? (
        <div className="flex flex-col items-start gap-2" data-testid="heat-error">
          <p className="text-sm text-destructive" role="alert">
            {error}
          </p>
          <Button variant="outline" size="sm" onClick={() => void load(window)} data-testid="heat-retry">
            重试
          </Button>
        </div>
      ) : rows.length === 0 ? (
        <EmptyState
          title="暂无行业热度数据"
          description="AI 管道快照生成中，可稍后刷新"
          testId="heat-empty"
        />
      ) : (
        <div className="overflow-hidden rounded-lg border" data-testid="heat-board">
          {rows.map((row, index) => {
            const isOpen = expanded === row.industry;
            const widthPct = topScore > 0 ? (row.heatScore / topScore) * 100 : 0;
            return (
              <div key={row.industry} className="border-b last:border-b-0">
                <button
                  type="button"
                  aria-expanded={isOpen}
                  onClick={() => setExpanded(isOpen ? null : row.industry)}
                  data-testid={`heat-row-${row.industry}`}
                  className="flex w-full items-center gap-3 px-3 py-2 text-left text-sm transition-colors hover:bg-muted/40 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
                >
                  <span
                    className="w-6 shrink-0 text-center text-xs text-muted-foreground tabular-nums"
                    data-testid="heat-rank"
                  >
                    {index + 1}
                  </span>
                  <span className="w-16 shrink-0 truncate font-medium">{row.industry}</span>
                  <span className="h-2 min-w-0 flex-1 overflow-hidden rounded-full bg-muted">
                    <span
                      className="block h-full rounded-full bg-primary/70"
                      style={{ width: `${widthPct}%` }}
                      data-testid={`heat-bar-${row.industry}`}
                    />
                  </span>
                  <span className="w-14 shrink-0 text-right text-xs text-muted-foreground tabular-nums">
                    {formatNumber(row.heatScore)}
                  </span>
                  <DeltaBadge deltaPct={row.deltaPct} testId={`heat-delta-${row.industry}`} />
                  <span className="shrink-0 text-xs text-muted-foreground">
                    资讯{' '}
                    <span className="text-foreground tabular-nums" data-testid={`heat-news-${row.industry}`}>
                      {row.newsCount}
                    </span>{' '}
                    · 事件{' '}
                    <span className="text-foreground tabular-nums" data-testid={`heat-event-${row.industry}`}>
                      {row.eventCount}
                    </span>
                  </span>
                  <ChevronDown
                    className={cn(
                      'size-4 shrink-0 text-muted-foreground transition-transform',
                      isOpen && 'rotate-180',
                    )}
                    aria-hidden="true"
                  />
                </button>
                {isOpen ? <div className="px-3 pb-3"><IndustryDrillDown industry={row.industry} window={window} /></div> : null}
              </div>
            );
          })}
        </div>
      )}

      {board ? (
        <p className="text-xs text-muted-foreground" data-testid="heat-footnote">
          热度口径 {board.basis} · 快照 {formatDateTime(board.snapshotAt)} · 环比 = vs 上一等长窗口
        </p>
      ) : null}
      {error && board ? (
        <p className="text-sm text-destructive" role="alert">
          {error}
        </p>
      ) : null}
    </div>
  );
}

// —— 日报 Tab ——

/** 日报详情（叙述 + 行业动态表 + 事件精选 + watchPoints + 免责声明；quote/figures 原文可回溯）。 */
function ReportDetail({ detail, onBack }: { detail: IndustryReportDetailView; onBack: () => void }) {
  const content = detail.content;
  return (
    <div className="flex flex-col gap-4" data-testid="report-detail-page">
      <div className="flex flex-wrap items-center gap-2">
        <Button variant="ghost" size="sm" onClick={onBack} data-testid="report-detail-back">
          ← 返回列表
        </Button>
        <span className="text-base font-medium">{detail.reportDate} 日报</span>
        <Badge
          className={
            detail.status === 'SUCCESS'
              ? statusToneClass('success')
              : statusToneClass('failure')
          }
        >
          {detail.status === 'SUCCESS' ? '成功' : '失败'}
        </Badge>
        {content?.narrativeDegraded ? (
          <Badge className="bg-amber-500/15 text-amber-400" title="AI 叙述生成失败或降级，本版为纯统计版">
            纯统计版
          </Badge>
        ) : null}
      </div>

      {!content ? (
        <p className="text-sm text-muted-foreground">
          该日报无内容（生成失败）：{detail.errorMessage ?? '未留失败原因'}，可返回列表重试。
        </p>
      ) : (
        <>
          <Card>
            <CardHeader>
              <CardTitle className="text-base">昨日主线（AI 总结）</CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm leading-relaxed" data-testid="report-detail-summary">
                {content.summary}
              </p>
              <p className="mt-2 text-xs text-muted-foreground">
                资讯 {content.totalNews} 条 · 事件 {content.totalEvents} 条（统计口径，非 AI 生成）
              </p>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-base">行业动态 Top</CardTitle>
            </CardHeader>
            <CardContent className="flex flex-col gap-2">
              {content.topIndustries.map((top) => (
                <div
                  key={top.industry}
                  className="flex flex-col gap-1 rounded-lg border px-3 py-2"
                  data-testid={`report-detail-top-${top.industry}`}
                >
                  <div className="flex flex-wrap items-center gap-2 text-sm">
                    <a
                      href={`#/industry-heat?industry=${encodeURIComponent(top.industry)}`}
                      className="font-medium underline decoration-border underline-offset-2 hover:text-foreground"
                      data-testid={`report-detail-top-link-${top.industry}`}
                      title="跳转热度榜并展开该行业下钻"
                    >
                      {top.industry}
                    </a>
                    <span className="text-xs text-muted-foreground">
                      资讯 <span className="text-foreground tabular-nums">{top.newsCount}</span> · 事件{' '}
                      <span className="text-foreground tabular-nums">{top.eventCount}</span>
                    </span>
                    <span className="text-xs text-muted-foreground tabular-nums">
                      热度 {formatNumber(top.heatScore)}
                    </span>
                    <DeltaBadge deltaPct={top.deltaPct} testId={`report-detail-delta-${top.industry}`} />
                  </div>
                  {top.commentary ? (
                    <p className="text-xs text-muted-foreground">{top.commentary}</p>
                  ) : null}
                </div>
              ))}
              {content.topIndustries.length === 0 ? (
                <p className="text-xs text-muted-foreground">当日无行业动态</p>
              ) : null}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-base">事件精选（重要度降序，quote/关键数字取自原文）</CardTitle>
            </CardHeader>
            <CardContent className="flex flex-col gap-3">
              {content.events.map((event) => (
                <div
                  key={event.eventId}
                  className="flex flex-col gap-1 rounded-lg border px-3 py-2"
                  data-testid={`report-detail-event-${event.eventId}`}
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge className="bg-sky-500/15 text-sky-400">
                      {labelOf(EVENT_TYPE_LABELS, event.eventType)}
                    </Badge>
                    <DirectionBadge direction={event.direction} />
                    <Badge className="bg-amber-500/15 text-amber-400">
                      重要度 {labelOf(IMPORTANCE_LABELS, event.importance)}
                    </Badge>
                    <span className="ml-auto text-xs text-muted-foreground">
                      {formatDateTime(event.eventTime)}
                    </span>
                  </div>
                  <p className="text-sm">{event.summary}</p>
                  {event.industries.length > 0 ? (
                    <div className="flex flex-wrap gap-1">
                      {event.industries.map((industry) => (
                        <Badge key={industry} variant="secondary">
                          {industry}
                        </Badge>
                      ))}
                    </div>
                  ) : null}
                  {event.figures.length > 0 ? (
                    <div className="flex flex-wrap gap-1">
                      {event.figures.map((figure, idx) => (
                        <Badge key={idx} className="bg-muted font-mono text-muted-foreground">
                          {figure.label}: {figure.value}
                          {figure.unit}
                        </Badge>
                      ))}
                    </div>
                  ) : null}
                  {event.quote ? (
                    <p className="border-l-2 border-border pl-2 text-xs text-muted-foreground">
                      原文引用：「{event.quote}」
                    </p>
                  ) : null}
                  <TraceSourceRow
                    sourceName={event.sourceName}
                    newsUrl={event.newsUrl}
                    testId={`report-detail-event-source-${event.eventId}`}
                  />
                </div>
              ))}
              {content.events.length === 0 ? (
                <p className="text-xs text-muted-foreground">当日无高价值事件</p>
              ) : null}
              <TraceFootnote anyMissingUrl={content.events.some((event) => !event.newsUrl)} />
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-base">今日关注</CardTitle>
            </CardHeader>
            <CardContent>
              <ul
                className="flex list-disc flex-col gap-1 pl-4 text-sm"
                data-testid="report-detail-watchpoints"
              >
                {content.watchPoints.map((point, idx) => (
                  <li key={idx}>{point}</li>
                ))}
              </ul>
              {content.watchPoints.length === 0 ? (
                <p className="text-xs text-muted-foreground">无</p>
              ) : null}
              <p className="mt-3 text-xs text-muted-foreground" data-testid="report-detail-disclaimer">
                {content.disclaimer}
              </p>
            </CardContent>
          </Card>
        </>
      )}
    </div>
  );
}

/** 日报 Tab（列表回看 + 详情 + FAILED 重试 202 轻轮询）。 */
function ReportTab({ retryPollMs }: { retryPollMs: number }) {
  const [list, setList] = useState<IndustryReportListView | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [detailDate, setDetailDate] = useState<string | null>(null);
  const [detail, setDetail] = useState<IndustryReportDetailView | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [retryingDates, setRetryingDates] = useState<Set<string>>(new Set());
  const [retryError, setRetryError] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const pollTimerRef = useRef<number | null>(null);
  const pollTicksRef = useRef(0);

  useEffect(
    () => () => {
      abortRef.current?.abort();
      if (pollTimerRef.current != null) window.clearInterval(pollTimerRef.current);
    },
    [],
  );

  const load = useCallback(async (): Promise<IndustryReportListView | null> => {
    abortRef.current?.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    try {
      const data = await getIndustryReports(undefined, undefined, ctrl.signal);
      if (ctrl.signal.aborted) return null;
      setList(data);
      setError(null);
      return data;
    } catch (err) {
      if (ctrl.signal.aborted) return null;
      setError(messageOf(err, '日报列表加载失败'));
      return null;
    } finally {
      if (!ctrl.signal.aborted) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const stopPoll = useCallback(() => {
    if (pollTimerRef.current != null) {
      window.clearInterval(pollTimerRef.current);
      pollTimerRef.current = null;
    }
    pollTicksRef.current = 0;
  }, []);

  // retryingDates 经 ref 读取，避免轮询闭包依赖重启
  const retryingDatesRef = useRef(retryingDates);
  retryingDatesRef.current = retryingDates;

  /** 202 受理后的轻轮询：目标日期 status 离开 FAILED 即收敛（上限防久挂）。 */
  const startPoll = useCallback(() => {
    stopPoll();
    pollTimerRef.current = window.setInterval(() => {
      pollTicksRef.current += 1;
      if (document.hidden) return;
      void load().then((fresh) => {
        if (!fresh) return;
        const stillFailed = fresh.reports
          .filter((report) => retryingDatesRef.current.has(report.reportDate))
          .every((report) => report.status === 'FAILED');
        if (!stillFailed || pollTicksRef.current >= RETRY_POLL_MAX_TICKS) {
          setRetryingDates(new Set());
          stopPoll();
        }
      });
    }, retryPollMs);
  }, [load, retryPollMs, stopPoll]);

  const openDetail = async (date: string) => {
    setDetailDate(date);
    setDetail(null);
    setDetailError(null);
    setDetailLoading(true);
    try {
      const data = await getIndustryReportDetail(date);
      setDetail(data);
    } catch (err) {
      setDetailError(messageOf(err, '日报详情加载失败'));
    } finally {
      setDetailLoading(false);
    }
  };

  const handleRetry = async (date: string) => {
    setRetryError(null);
    try {
      await retryIndustryReport(date);
      setRetryingDates((prev) => new Set(prev).add(date));
      startPoll();
    } catch (err) {
      // 30077 已成功 / 30078 不存在：后端文案直出，不进入轮询
      setRetryError(messageOf(err, '重试请求失败，请稍后再试'));
    }
  };

  if (detailDate != null) {
    return (
      <div className="flex flex-col gap-3">
        {detailLoading ? (
          <div className="flex flex-col gap-3" data-testid="report-detail-loading">
            <Skeleton className="h-8 w-48" />
            <Skeleton className="h-24 w-full" />
            <Skeleton className="h-40 w-full" />
          </div>
        ) : detailError ? (
          <div className="flex flex-col items-start gap-2" data-testid="report-detail-error">
            <p className="text-sm text-destructive" role="alert">
              {detailError}
            </p>
            <div className="flex gap-2">
              <Button variant="outline" size="sm" onClick={() => setDetailDate(null)}>
                返回列表
              </Button>
              <Button
                variant="outline"
                size="sm"
                onClick={() => void openDetail(detailDate)}
                data-testid="report-detail-retry"
              >
                重试
              </Button>
            </div>
          </div>
        ) : detail ? (
          <ReportDetail detail={detail} onBack={() => setDetailDate(null)} />
        ) : null}
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-3">
      {retryError ? (
        <p
          className="rounded bg-rose-500/15 px-3 py-2 text-sm text-rose-400"
          role="alert"
          data-testid="report-retry-error"
        >
          {retryError}
        </p>
      ) : null}

      {loading ? (
        <div className="flex flex-col gap-3" data-testid="report-loading">
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-24 w-full" />
        </div>
      ) : error && !list ? (
        <div className="flex flex-col items-start gap-2" data-testid="report-error">
          <p className="text-sm text-destructive" role="alert">
            {error}
          </p>
          <Button variant="outline" size="sm" onClick={() => void load()} data-testid="report-retry">
            重试
          </Button>
        </div>
      ) : (list?.reports.length ?? 0) === 0 ? (
        <EmptyState
          title="暂无行业日报"
          description="日报每日 08:00 自动生成前一交易日报告，首个完整数据日的次日出报。"
          testId="report-empty"
        />
      ) : (
        <div className="flex flex-col gap-3" data-testid="report-list">
          {list?.reports.map((report) => {
            const retrying = retryingDates.has(report.reportDate);
            return (
              <Card
                key={report.reportDate}
                className="cursor-pointer transition-colors hover:bg-muted/30"
                data-testid={`report-list-card-${report.reportDate}`}
                onClick={() => void openDetail(report.reportDate)}
              >
                <CardHeader>
                  <CardTitle className="flex flex-wrap items-center gap-2 text-base">
                    <span className="font-medium">{report.reportDate}</span>
                    <Badge
                      className={
                        report.status === 'SUCCESS'
                          ? statusToneClass('success')
                          : statusToneClass('failure')
                      }
                      data-testid={`report-status-${report.reportDate}`}
                    >
                      {report.status === 'SUCCESS' ? '成功' : '失败'}
                    </Badge>
                    {report.narrativeDegraded ? (
                      <Badge
                        className="bg-amber-500/15 text-amber-400"
                        title="AI 叙述生成失败或降级，本版为纯统计版"
                        data-testid={`report-degraded-${report.reportDate}`}
                      >
                        纯统计版
                      </Badge>
                    ) : null}
                    {retrying ? (
                      <span
                        className="text-xs text-amber-400"
                        data-testid={`report-retrying-${report.reportDate}`}
                      >
                        重试已受理 · 生成中…
                      </span>
                    ) : null}
                    {report.status !== 'SUCCESS' ? (
                      <Button
                        variant="outline"
                        size="sm"
                        className="ml-auto"
                        disabled={retrying}
                        onClick={(e) => {
                          e.stopPropagation();
                          void handleRetry(report.reportDate);
                        }}
                        data-testid={`report-retry-${report.reportDate}`}
                      >
                        重试生成
                      </Button>
                    ) : null}
                  </CardTitle>
                </CardHeader>
                <CardContent>
                  <p className="text-sm text-muted-foreground">{report.summary}</p>
                  <p className="mt-1 text-xs text-muted-foreground">
                    资讯{' '}
                    <span className="text-foreground tabular-nums">{report.totalNews}</span> 条 · 事件{' '}
                    <span className="text-foreground tabular-nums">{report.totalEvents}</span> 条
                  </p>
                </CardContent>
              </Card>
            );
          })}
        </div>
      )}
    </div>
  );
}

// —— 周报 Tab（M17 T145：列表回看 + 五区块详情 + FAILED 重试） ——

/** 周报详情（五区块：热度总览 / 事件回顾 / 政策动向 / 下周关注点 / 走向判断；置信度与免责标注）。 */
function WeeklyReportDetail({ detail, onBack }: { detail: IndustryWeeklyReportDetailView; onBack: () => void }) {
  const content = detail.content;
  return (
    <div className="flex flex-col gap-4" data-testid="weekly-detail-page">
      <div className="flex flex-wrap items-center gap-2">
        <Button variant="ghost" size="sm" onClick={onBack} data-testid="weekly-detail-back">
          ← 返回列表
        </Button>
        <span className="text-base font-medium">{detail.weekStart} 起本周周报</span>
        <Badge
          className={
            detail.status === 'SUCCESS'
              ? statusToneClass('success')
              : statusToneClass('failure')
          }
        >
          {detail.status === 'SUCCESS' ? '成功' : '失败'}
        </Badge>
        {content?.narrativeDegraded ? (
          <Badge className="bg-amber-500/15 text-amber-400" title="AI 叙述生成失败或降级，本版为纯统计模板直出">
            纯统计版
          </Badge>
        ) : null}
      </div>

      {!content ? (
        <p className="text-sm text-muted-foreground">
          该周周报无内容（生成失败）：{detail.errorMessage ?? '未留失败原因'}，可返回列表重试。
        </p>
      ) : (
        <>
          <Card>
            <CardHeader>
              <CardTitle className="text-base">本周总结（AI 组织，数字来自统计）</CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm leading-relaxed" data-testid="weekly-detail-summary">
                {content.summary}
              </p>
              <p className="mt-2 text-xs text-muted-foreground">
                周窗 {content.weekStart} ~ {content.weekEnd} · 资讯 {content.totalNews} 条 · 事件{' '}
                {content.totalEvents} 条（主键归并，跨日去重）
              </p>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-base">热度总览（周环比）</CardTitle>
            </CardHeader>
            <CardContent className="flex flex-col gap-3 sm:flex-row">
              <div className="flex-1">
                <p className={cn('mb-1 text-xs', directionTextClass('up'))}>升温 Top</p>
                {content.topRisers.length === 0 ? (
                  <p className="text-xs text-muted-foreground">本周无显著升温行业</p>
                ) : (
                  content.topRisers.map((row) => (
                    <div key={row.industry} className="flex items-center gap-2 py-1 text-sm" data-testid={`weekly-riser-${row.industry}`}>
                      <a
                        href={`#/industry-heat?industry=${encodeURIComponent(row.industry)}`}
                        className="font-medium underline decoration-border underline-offset-2 hover:text-foreground"
                        data-testid={`weekly-riser-link-${row.industry}`}
                        title="跳转热度榜并展开该行业下钻"
                      >
                        {row.industry}
                      </a>
                      <span className="text-xs text-muted-foreground tabular-nums">
                        热度 {formatNumber(row.score)} · 事件 {row.eventCount}
                      </span>
                      <DeltaBadge deltaPct={row.deltaPct} testId={`weekly-riser-delta-${row.industry}`} />
                    </div>
                  ))
                )}
              </div>
              <div className="flex-1">
                <p className={cn('mb-1 text-xs', directionTextClass('down'))}>降温 Top</p>
                {content.topFallers.length === 0 ? (
                  <p className="text-xs text-muted-foreground">本周无显著降温行业</p>
                ) : (
                  content.topFallers.map((row) => (
                    <div key={row.industry} className="flex items-center gap-2 py-1 text-sm" data-testid={`weekly-faller-${row.industry}`}>
                      <a
                        href={`#/industry-heat?industry=${encodeURIComponent(row.industry)}`}
                        className="font-medium underline decoration-border underline-offset-2 hover:text-foreground"
                        data-testid={`weekly-faller-link-${row.industry}`}
                        title="跳转热度榜并展开该行业下钻"
                      >
                        {row.industry}
                      </a>
                      <span className="text-xs text-muted-foreground tabular-nums">
                        热度 {formatNumber(row.score)} · 事件 {row.eventCount}
                      </span>
                      <DeltaBadge deltaPct={row.deltaPct} testId={`weekly-faller-delta-${row.industry}`} />
                    </div>
                  ))
                )}
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-base">事件回顾（主键归并，重要度降序）</CardTitle>
            </CardHeader>
            <CardContent className="flex flex-col gap-2">
              {content.eventReview.map((event) => (
                <div key={event.eventId} className="flex flex-col gap-1 rounded-lg border px-3 py-2" data-testid={`weekly-event-${event.eventId}`}>
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge className="bg-sky-500/15 text-sky-400">
                      {labelOf(EVENT_TYPE_LABELS, event.eventType)}
                    </Badge>
                    <DirectionBadge direction={event.direction} />
                    <Badge className="bg-amber-500/15 text-amber-400">
                      重要度 {labelOf(IMPORTANCE_LABELS, event.importance)}
                    </Badge>
                    <span className="ml-auto text-xs text-muted-foreground">{event.eventDate}</span>
                  </div>
                  <p className="text-sm">{event.summary}</p>
                  {event.quote ? (
                    <p className="border-l-2 border-border pl-2 text-xs text-muted-foreground">
                      原文引用：「{event.quote}」
                    </p>
                  ) : null}
                  <TraceSourceRow
                    sourceName={event.sourceName}
                    newsUrl={event.newsUrl}
                    testId={`weekly-event-source-${event.eventId}`}
                  />
                </div>
              ))}
              {content.eventReview.length === 0 ? (
                <p className="text-xs text-muted-foreground">本周无结构化事件</p>
              ) : null}
              <TraceFootnote anyMissingUrl={content.eventReview.some((event) => !event.newsUrl)} />
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-base">政策动向（官方源周窗清单）</CardTitle>
            </CardHeader>
            <CardContent className="flex flex-col gap-2">
              {content.policyMoves.map((policy) => (
                <div key={policy.eventId} className="flex flex-col gap-1 rounded-lg border px-3 py-2" data-testid={`weekly-policy-${policy.eventId}`}>
                  <div className="flex flex-wrap items-center gap-2">
                    <DirectionBadge direction={policy.direction} />
                    <span className="ml-auto text-xs text-muted-foreground">
                      {formatDateTime(policy.eventTime)}
                    </span>
                  </div>
                  <p className="text-sm">{policy.summary}</p>
                  {policy.quote ? (
                    <p className="border-l-2 border-border pl-2 text-xs text-muted-foreground">
                      原文引用：「{policy.quote}」
                    </p>
                  ) : null}
                  <TraceSourceRow
                    sourceName={policy.sourceName}
                    newsUrl={policy.newsUrl}
                    testId={`weekly-policy-source-${policy.eventId}`}
                  />
                </div>
              ))}
              {content.policyMoves.length === 0 ? (
                <p className="text-xs text-muted-foreground">本周无政策发布事件</p>
              ) : null}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-base">下周关注点</CardTitle>
            </CardHeader>
            <CardContent>
              <ul className="flex list-disc flex-col gap-1 pl-4 text-sm" data-testid="weekly-watchpoints">
                {content.nextWeekWatch.map((point, idx) => (
                  <li key={idx}>{point}</li>
                ))}
              </ul>
              {content.nextWeekWatch.length === 0 ? (
                <p className="text-xs text-muted-foreground">无</p>
              ) : null}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-base">
                走向判断 v1（{content.trendJudgement.basis} · 置信度规则层锁定）
              </CardTitle>
            </CardHeader>
            <CardContent className="flex flex-col gap-2">
              {content.trendJudgement.items.map((item) => (
                <div key={item.industry} className="flex flex-col gap-1 rounded-lg border px-3 py-2" data-testid={`weekly-trend-${item.industry}`}>
                  <div className="flex flex-wrap items-center gap-2 text-sm">
                    <span className="font-medium">{item.industry}</span>
                    <Badge
                      className={
                        item.signal === 'HEATING'
                          ? directionToneClass('up')
                          : item.signal === 'COOLING'
                            ? directionToneClass('down')
                            : directionToneClass('flat')
                      }
                    >
                      {item.signalLabel}
                    </Badge>
                    <Badge className="bg-amber-500/15 text-amber-400">置信度 {item.confidenceLabel}</Badge>
                    <DeltaBadge deltaPct={item.deltaPct} testId={`weekly-trend-delta-${item.industry}`} />
                    <span className="ml-auto text-xs text-muted-foreground">
                      {item.narrativeSource === 'TEMPLATE' ? '模板直出' : 'AI 组织'}
                    </span>
                  </div>
                  <p className="text-xs text-muted-foreground">{item.narrative}</p>
                  <p className="text-xs text-muted-foreground">
                    依据：周内事件 {item.eventCount} 条 · 政策 {item.policyCount} 条 · 证据事件{' '}
                    {item.evidenceEventIds.join(' / ') || '--'}
                  </p>
                </div>
              ))}
              {content.trendJudgement.items.length === 0 ? (
                <p className="text-xs text-muted-foreground">本周无显著升温/降温行业（走向判断缺省不叙述）</p>
              ) : null}
              <p className="mt-2 text-xs text-muted-foreground" data-testid="weekly-detail-disclaimer">
                {content.disclaimer} · 走向判断为行业信息面趋势描述，不构成买卖建议与点位预测。
              </p>
            </CardContent>
          </Card>
        </>
      )}
    </div>
  );
}

/** 周报 Tab（列表回看 + 详情 + FAILED 重试 202 轻轮询）。 */
function WeeklyTab({ retryPollMs }: { retryPollMs: number }) {
  const [list, setList] = useState<IndustryWeeklyReportListView | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [detailWeek, setDetailWeek] = useState<string | null>(null);
  const [detail, setDetail] = useState<IndustryWeeklyReportDetailView | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [retryingWeeks, setRetryingWeeks] = useState<Set<string>>(new Set());
  const abortRef = useRef<AbortController | null>(null);
  const pollTimerRef = useRef<number | null>(null);
  const pollTicksRef = useRef(0);

  useEffect(
    () => () => {
      abortRef.current?.abort();
      if (pollTimerRef.current != null) window.clearInterval(pollTimerRef.current);
    },
    [],
  );

  const load = useCallback(async (): Promise<IndustryWeeklyReportListView | null> => {
    abortRef.current?.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    try {
      const data = await getIndustryWeeklyReports(undefined, undefined, ctrl.signal);
      if (ctrl.signal.aborted) return null;
      setList(data);
      setError(null);
      return data;
    } catch (err) {
      if (ctrl.signal.aborted) return null;
      setError(messageOf(err, '周报列表加载失败'));
      return null;
    } finally {
      if (!ctrl.signal.aborted) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const stopPoll = useCallback(() => {
    if (pollTimerRef.current != null) {
      window.clearInterval(pollTimerRef.current);
      pollTimerRef.current = null;
    }
    pollTicksRef.current = 0;
  }, []);

  const retryingWeeksRef = useRef(retryingWeeks);
  retryingWeeksRef.current = retryingWeeks;

  const startPoll = useCallback(() => {
    stopPoll();
    pollTimerRef.current = window.setInterval(() => {
      pollTicksRef.current += 1;
      if (document.hidden) return;
      void load().then((fresh) => {
        if (!fresh) return;
        const stillFailed = fresh.reports
          .filter((report) => retryingWeeksRef.current.has(report.weekStart))
          .every((report) => report.status === 'FAILED');
        if (!stillFailed || pollTicksRef.current >= RETRY_POLL_MAX_TICKS) {
          setRetryingWeeks(new Set());
          stopPoll();
        }
      });
    }, retryPollMs);
  }, [load, retryPollMs, stopPoll]);

  const openDetail = async (weekStart: string) => {
    setDetailWeek(weekStart);
    setDetail(null);
    setDetailError(null);
    setDetailLoading(true);
    try {
      const data = await getIndustryWeeklyReportDetail(weekStart);
      setDetail(data);
    } catch (err) {
      setDetailError(messageOf(err, '周报详情加载失败'));
    } finally {
      setDetailLoading(false);
    }
  };

  const handleRetry = async (weekStart: string) => {
    try {
      await retryIndustryWeeklyReport(weekStart);
      setRetryingWeeks((prev) => new Set(prev).add(weekStart));
      startPoll();
    } catch (err) {
      setError(messageOf(err, '重试请求失败，请稍后再试'));
    }
  };

  if (detailWeek != null) {
    return (
      <div className="flex flex-col gap-3">
        {detailLoading ? (
          <div className="flex flex-col gap-3" data-testid="weekly-detail-loading">
            <Skeleton className="h-8 w-48" />
            <Skeleton className="h-24 w-full" />
            <Skeleton className="h-40 w-full" />
          </div>
        ) : detailError ? (
          <div className="flex flex-col items-start gap-2" data-testid="weekly-detail-error">
            <p className="text-sm text-destructive" role="alert">
              {detailError}
            </p>
            <Button variant="outline" size="sm" onClick={() => setDetailWeek(null)}>
              返回列表
            </Button>
          </div>
        ) : detail ? (
          <WeeklyReportDetail detail={detail} onBack={() => setDetailWeek(null)} />
        ) : null}
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-3">
      {loading ? (
        <div className="flex flex-col gap-3" data-testid="weekly-loading">
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-24 w-full" />
        </div>
      ) : error && !list ? (
        <div className="flex flex-col items-start gap-2" data-testid="weekly-error">
          <p className="text-sm text-destructive" role="alert">
            {error}
          </p>
          <Button variant="outline" size="sm" onClick={() => void load()} data-testid="weekly-retry">
            重试
          </Button>
        </div>
      ) : (list?.reports.length ?? 0) === 0 ? (
        <EmptyState
          title="暂无行业周报"
          description="周报每周日晚 20:00 自动生成本周报告（热度总览/事件回顾/政策动向/下周关注点/走向判断）。"
          testId="weekly-empty"
        />
      ) : (
        <div className="flex flex-col gap-3" data-testid="weekly-list">
          {list?.reports.map((report) => {
            const retrying = retryingWeeks.has(report.weekStart);
            return (
              <Card
                key={report.weekStart}
                className="cursor-pointer transition-colors hover:bg-muted/30"
                data-testid={`weekly-list-card-${report.weekStart}`}
                onClick={() => void openDetail(report.weekStart)}
              >
                <CardHeader>
                  <CardTitle className="flex flex-wrap items-center gap-2 text-base">
                    <span className="font-medium">{report.weekStart} 起</span>
                    <Badge
                      className={
                        report.status === 'SUCCESS'
                          ? statusToneClass('success')
                          : statusToneClass('failure')
                      }
                      data-testid={`weekly-status-${report.weekStart}`}
                    >
                      {report.status === 'SUCCESS' ? '成功' : '失败'}
                    </Badge>
                    {report.narrativeDegraded ? (
                      <Badge className="bg-amber-500/15 text-amber-400" data-testid={`weekly-degraded-${report.weekStart}`}>
                        纯统计版
                      </Badge>
                    ) : null}
                    {retrying ? (
                      <span className="text-xs text-amber-400" data-testid={`weekly-retrying-${report.weekStart}`}>
                        重试已受理 · 生成中…
                      </span>
                    ) : null}
                    {report.status !== 'SUCCESS' ? (
                      <Button
                        variant="outline"
                        size="sm"
                        className="ml-auto"
                        disabled={retrying}
                        onClick={(e) => {
                          e.stopPropagation();
                          void handleRetry(report.weekStart);
                        }}
                        data-testid={`weekly-retry-${report.weekStart}`}
                      >
                        重试生成
                      </Button>
                    ) : null}
                  </CardTitle>
                </CardHeader>
                <CardContent>
                  <p className="text-sm text-muted-foreground">{report.summary}</p>
                  <p className="mt-1 text-xs text-muted-foreground">
                    资讯 <span className="text-foreground tabular-nums">{report.totalNews}</span> 条 · 事件{' '}
                    <span className="text-foreground tabular-nums">{report.totalEvents}</span> 条
                  </p>
                </CardContent>
              </Card>
            );
          })}
        </div>
      )}
    </div>
  );
}

// —— 页面 ——

interface IndustryHeatProps {
  /** 日报重试轻轮询间隔（默认 3s，测试可缩短加速）。 */
  retryPollMs?: number;
}

/** 行业热度与日报页（第 16 页，三 Tab：热度榜 / 日报 / 周报——M17 T145 扩三 Tab）。 */
export function IndustryHeat({ retryPollMs = DEFAULT_RETRY_POLL_MS }: IndustryHeatProps = {}) {
  const [tab, setTab] = useState<'heat' | 'report' | 'weekly'>('heat');
  // C 级溯源链直达：#/industry-heat?industry=X（日报/周报行业名、工作台 Top5 行）→ 切热度榜并展开下钻；
  // 挂载期惰性读一次 + hashchange 监听（报告 Tab 页内点击自身路由参数变化不重挂载，由监听承接）
  const [focusIndustry, setFocusIndustry] = useState<string | null>(() =>
    queryOf(currentRoute()).get('industry'),
  );
  useEffect(() => {
    const onHashChange = () => {
      const industry = queryOf(currentRoute()).get('industry');
      if (industry) {
        setFocusIndustry(industry);
        setTab('heat');
      }
    };
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  return (
    <main className="mx-auto w-full max-w-4xl p-4 sm:p-6" data-testid="industry-heat-page">
      <header className="mb-4">
        <h1 className="text-xl font-medium">行业热度与日报</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          按行业看信息、按事件抓重点：热度榜 31 行业分钟级快照，行业日报每日 08:00 晨读，行业周报周日晚 20:00 纵深复盘。
        </p>
      </header>

      <Tabs
        value={tab}
        onValueChange={(value) =>
          setTab(value === 'report' ? 'report' : value === 'weekly' ? 'weekly' : 'heat')
        }
        className="gap-3"
      >
        <TabsList>
          <TabsTrigger value="heat" data-testid="heat-tab-heat">
            热度榜
          </TabsTrigger>
          <TabsTrigger value="report" data-testid="heat-tab-report">
            行业日报
          </TabsTrigger>
          <TabsTrigger value="weekly" data-testid="heat-tab-weekly">
            行业周报
          </TabsTrigger>
        </TabsList>
      </Tabs>

      {tab === 'heat' ? (
        <HeatBoardTab focusIndustry={focusIndustry} />
      ) : tab === 'report' ? (
        <ReportTab retryPollMs={retryPollMs} />
      ) : (
        <WeeklyTab retryPollMs={retryPollMs} />
      )}
    </main>
  );
}

export default IndustryHeat;
