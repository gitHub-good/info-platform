import { useCallback, useEffect, useRef, useState } from 'react';
import { getFeedDashboard } from '@/api/feedDashboard';
import { getNorthStar } from '@/api/northStar';
import { ApiError } from '@/api/http';
import { NorthStarBlock } from '@/components/feed/NorthStarBlock';
import { NewsItemsDialog, type NewsItemsDialogSpec } from '@/components/feed/NewsItemsDialog';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { EmptyState } from '@/components/ui/EmptyState';
import { Skeleton } from '@/components/ui/skeleton';
import { navigate } from '@/lib/navigation';
import { cn } from '@/lib/utils';
import type { FeedDashboardSourceRow, FeedDashboardView } from '@/types/feedDashboard';
import type { NorthStarView } from '@/types/northStar';

// 抓取大盘页 2.0（M14 T116 → M18 T155/T158 大盘 2.0：北极星区块 + >30 源形态，REQ 拍板三/四）。
// 北极星区块（顶置）：六指标卡 + 达标徽章 + 7 天入库迷你趋势（ns-v1），独立三态、失败单区块降级；
// 三区块只读：全局统计卡四指标 + 感知延迟 P50/P90 徽章（仅增量轮口径标注）、
// 源维度表（异常置顶/五态徽章/归档源默认折叠；行数超阈值启用纵向滚动 + 表头吸附的 >30 源形态）、
// 近期失败列表（点击跳源管理页定位）。
// V2.4 T214（REQ-20260928-20 拍板三）：三处数字可点弹框——全局「今日总入库」/ 源行「今日新增」
// 「累计条数」→ Dialog 分页条目列表（news-items 页码模式 + l0=ALL + 入库时间窗预填），
// 弹框 total 与被点数字对账相等为验收锚；去重拦截/源数/延迟明确不可点（hover title 说明）。
// 近实时：30s 自动刷新（document.hidden 暂停）+ 手动刷新 +「更新于 N 秒前」；三态（加载/错误/数据）与空态引导。

/** 自动刷新间隔（蓝图 30 秒级建议值，大盘与北极星同节奏）。 */
const AUTO_REFRESH_MILLIS = 30_000;

/** 「更新于 N 秒前」计时精度。 */
const NOW_TICK_MILLIS = 1_000;

/** 源表纵向滚动阈值（>30 源形态：30+ 行启用滚动 + 表头吸附，渲染不退化）。 */
const SOURCE_TABLE_SCROLL_THRESHOLD = 20;

/**
 * 上海本地日 yyyy-MM-dd（大盘「今日入库」弹框的入库时间窗口径——与后端
 * source_daily_stats.stat_date / fetchedFrom/To 上海日界同域）。
 */
export function shanghaiToday(): string {
  return new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Shanghai' });
}

/** 不可点数字的 hover 说明（REQ 拍板三防蔓延清单 #10：非条目集合/未入库无可列）。 */
const NON_CLICKABLE_TITLES = {
  dup: '去重拦截为未入库条目，无可列实体，不支持点击下钻',
  active: '活跃源数非条目集合（源表运行态统计），不支持点击下钻',
  failed: '失败源数非条目集合（源表运行态统计），不支持点击下钻',
  latency: '感知延迟非条目集合（入库-发布延迟分布），不支持点击下钻',
} as const;

/** 失败列表收起态条数（V2.4 T215：收起最多 3 条 + 展开限高滚动，REQ-20260928-20 拍板四）。 */
const FAILURES_COLLAPSED_LIMIT = 3;

function messageOf(err: unknown, fallback: string): string {
  return err instanceof ApiError ? err.msg : fallback;
}

function formatTime(iso: string | null): string {
  if (!iso) return '—';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '—';
  return `${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(
    date.getHours(),
  ).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
}

/** 感知延迟人读格式：<90s 按秒、其余按分钟（大盘北极星量级为分钟）。 */
function formatLatency(millis: number | null): string {
  if (millis == null) return '—';
  if (millis < 90_000) return `${Math.max(1, Math.round(millis / 1000))} 秒`;
  return `${Math.round(millis / 60_000)} 分钟`;
}

function truncateText(text: string, max: number): string {
  return text.length <= max ? `${text.slice(0, max)}…` : text;
}

/** 类型徽章（RSS=sky / JSON=violet / 预置=amber——与源管理页同语义）。 */
function typeBadgeOf(adapterType: FeedDashboardSourceRow['adapterType']): {
  label: string;
  className: string;
} {
  switch (adapterType) {
    case 'rss':
      return { label: 'RSS', className: 'bg-sky-500/15 text-sky-400' };
    case 'json_api':
      return { label: 'JSON', className: 'bg-violet-500/15 text-violet-400' };
    default:
      return { label: '预置', className: 'bg-amber-500/15 text-amber-400' };
  }
}

/** 状态徽章（五态语义 = 后端 runState 线格式，色彩对齐源管理页徽章矩阵）。 */
function statusBadgeOf(row: FeedDashboardSourceRow): { text: string; className: string } {
  switch (row.runState) {
    case 'backoff':
      return { text: '退避中', className: 'bg-amber-500/15 text-amber-400' };
    case 'fail':
      return { text: '失败', className: 'bg-rose-500/15 text-rose-400' };
    case 'disabled':
      return { text: '停用', className: 'bg-muted text-muted-foreground' };
    case 'pending':
      return { text: '暂未抓取', className: 'bg-muted text-muted-foreground' };
    default:
      return { text: '正常', className: 'bg-emerald-500/15 text-emerald-400' };
  }
}

function StatCard({
  testId,
  label,
  value,
  sub,
  subTestId,
  dataValue,
  hoverTitle,
  subHoverTitle,
}: {
  testId: string;
  label: string;
  value: string;
  sub?: string;
  subTestId?: string;
  dataValue?: string;
  /** 不可点说明（hover title）：非条目集合数字的防蔓延口径明示。 */
  hoverTitle?: string;
  subHoverTitle?: string;
}) {
  return (
    <Card data-testid={testId} data-value={dataValue} title={hoverTitle}>
      <CardHeader>
        <CardTitle className="text-sm font-normal text-muted-foreground">{label}</CardTitle>
      </CardHeader>
      <CardContent>
        <p className="text-2xl font-medium">{value}</p>
        {sub ? (
          <p className="mt-1 text-xs text-muted-foreground" data-testid={subTestId} title={subHoverTitle}>
            {sub}
          </p>
        ) : null}
      </CardContent>
    </Card>
  );
}

/** 可点数字（V2.4 T214 三处下钻入口）：按钮形态 + hover 口径提示。 */
function ClickableNumber({
  value,
  hoverTitle,
  onClick,
  testId,
  dataValue,
  compact = false,
}: {
  value: string;
  hoverTitle: string;
  onClick: () => void;
  testId: string;
  dataValue?: string;
  compact?: boolean;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      title={hoverTitle}
      data-testid={testId}
      data-value={dataValue}
      className={cn(
        'font-medium text-primary underline-offset-4 hover:underline',
        compact ? 'text-sm' : 'text-2xl',
      )}
    >
      {value}
    </button>
  );
}

export function FeedDashboard() {
  const [view, setView] = useState<FeedDashboardView | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [lastFetchedAt, setLastFetchedAt] = useState<number | null>(null);
  const [nowTick, setNowTick] = useState(() => Date.now());
  const [showArchived, setShowArchived] = useState(false);
  // 失败列表折叠态（V2.4 T215）：收起默认前 3 条，展开限高滚动看全部
  const [failuresExpanded, setFailuresExpanded] = useState(false);
  // 北极星区块独立三态（失败单区块降级，不阻断大盘三区块）
  const [nsView, setNsView] = useState<NorthStarView | null>(null);
  const [nsLoading, setNsLoading] = useState(true);
  const [nsError, setNsError] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const nsAbortRef = useRef<AbortController | null>(null);

  const load = useCallback(async () => {
    abortRef.current?.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    try {
      const data = await getFeedDashboard(ctrl.signal);
      if (ctrl.signal.aborted) return;
      setView(data);
      setError(null);
      setLastFetchedAt(Date.now());
    } catch (err) {
      if (ctrl.signal.aborted) return;
      setError(messageOf(err, '大盘数据加载失败'));
    } finally {
      if (!ctrl.signal.aborted) setLoading(false);
    }
  }, []);

  const loadNorthStar = useCallback(async () => {
    nsAbortRef.current?.abort();
    const ctrl = new AbortController();
    nsAbortRef.current = ctrl;
    try {
      const data = await getNorthStar(ctrl.signal);
      if (ctrl.signal.aborted) return;
      // 契约形状防御：缺 basis/latency 视为载荷异常（测试桩/网关错报不白屏）
      if (!data || typeof data.basis !== 'string' || !data.latency) {
        throw new Error('north-star payload shape mismatch');
      }
      setNsView(data);
      setNsError(null);
    } catch (err) {
      if (ctrl.signal.aborted) return;
      setNsError(messageOf(err, '北极星数据加载失败'));
    } finally {
      if (!ctrl.signal.aborted) setNsLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
    void loadNorthStar();
    return () => {
      abortRef.current?.abort();
      nsAbortRef.current?.abort();
    };
  }, [load, loadNorthStar]);

  // 30s 近实时刷新：document.hidden 暂停（隐藏期间跳过本轮不请求）；大盘与北极星同节奏
  useEffect(() => {
    const timer = window.setInterval(() => {
      if (document.hidden) return;
      void load();
      void loadNorthStar();
    }, AUTO_REFRESH_MILLIS);
    return () => window.clearInterval(timer);
  }, [load, loadNorthStar]);

  // 「更新于 N 秒前」秒级计时
  useEffect(() => {
    const timer = window.setInterval(() => setNowTick(Date.now()), NOW_TICK_MILLIS);
    return () => window.clearInterval(timer);
  }, []);

  const refreshAll = useCallback(() => {
    void load();
    void loadNorthStar();
  }, [load, loadNorthStar]);

  const secondsAgo =
    lastFetchedAt == null ? null : Math.max(0, Math.floor((nowTick - lastFetchedAt) / 1000));

  const sources = view?.sources ?? [];
  const visibleSources = showArchived ? sources : sources.filter((row) => !row.deleted);
  const sourceTableScrollable = visibleSources.length > SOURCE_TABLE_SCROLL_THRESHOLD;
  // 恢复过滤隐藏计数（T211 后端；旧载荷缺省 0 不呈现脚注）
  const hiddenRecovered = view?.failuresHiddenRecovered ?? 0;

  // —— V2.4 T214 三处数字弹框（对账锚：弹框 total == 被点数字；l0=ALL + 入库时间窗口径） ——
  const [dialogSpec, setDialogSpec] = useState<NewsItemsDialogSpec | null>(null);

  /** 全局卡「今日总入库」：全部源 + 入库日=今日 + l0=ALL。 */
  const openGlobalToday = () => {
    const today = shanghaiToday();
    setDialogSpec({
      title: '今日入库 · 全部源',
      sourceId: null,
      l0: 'ALL',
      fetchedFrom: today,
      fetchedTo: today,
      deepLink: '/news-library?l0=ALL',
    });
  };

  /** 源行「今日新增」：该源 + 入库日=今日 + l0=ALL。 */
  const openSourceToday = (row: FeedDashboardSourceRow) => {
    const today = shanghaiToday();
    setDialogSpec({
      title: `今日入库 · ${row.name}`,
      sourceId: row.sourceId,
      l0: 'ALL',
      fetchedFrom: today,
      fetchedTo: today,
      deepLink: `/news-library?sourceId=${row.sourceId}&l0=ALL`,
    });
  };

  /** 源行「累计条数」：该源 + l0=ALL，无时间窗。 */
  const openSourceTotal = (row: FeedDashboardSourceRow) => {
    setDialogSpec({
      title: `累计条目 · ${row.name}`,
      sourceId: row.sourceId,
      l0: 'ALL',
      deepLink: `/news-library?sourceId=${row.sourceId}&l0=ALL`,
    });
  };

  return (
    <main className="mx-auto w-full max-w-6xl p-4 sm:p-6" data-testid="feed-dashboard-page">
      <header className="mb-4">
        <h1 className="text-xl font-medium">抓取大盘</h1>
        <div className="mt-1 flex flex-wrap items-center gap-3">
          <p className="max-w-3xl text-sm text-muted-foreground">
            V2.0 北极星六指标、全部资讯源今日入库、运行状态与近期失败的一屏近实时视图（只读）；处置动作跳「资讯源」页完成。
          </p>
          <span className="ml-auto flex items-center gap-3">
            <span
              className="text-xs text-muted-foreground"
              data-testid="dashboard-last-refresh"
            >
              {secondsAgo == null ? '尚未刷新' : `更新于 ${secondsAgo} 秒前`}
            </span>
            <Button
              size="sm"
              variant="outline"
              onClick={refreshAll}
              data-testid="dashboard-refresh"
            >
              刷新
            </Button>
          </span>
        </div>
      </header>

      {loading ? (
        <div className="flex flex-col gap-4" data-testid="dashboard-loading">
          <Skeleton className="h-40 w-full" />
          <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
            {Array.from({ length: 4 }, (_, i) => (
              <Skeleton key={i} className="h-24 w-full" />
            ))}
          </div>
          <Skeleton className="h-64 w-full" />
        </div>
      ) : error && !view ? (
        <div className="flex flex-col items-start gap-2" data-testid="dashboard-error">
          <p className="text-sm text-destructive" role="alert">
            {error}
          </p>
          <Button variant="outline" size="sm" onClick={refreshAll} data-testid="dashboard-retry">
            重试
          </Button>
        </div>
      ) : view ? (
        <div className="flex flex-col gap-4">
          {/* —— V2.0 北极星区块（M18 T158）：独立三态，失败降级不阻断大盘三区块 —— */}
          {nsLoading ? (
            <Skeleton className="h-40 w-full" data-testid="north-star-loading" />
          ) : nsError ? (
            <div
              className="flex flex-wrap items-center gap-2 rounded-lg border border-dashed px-3 py-2 text-sm text-muted-foreground"
              role="status"
              data-testid="north-star-error"
            >
              {nsError}
              <button
                type="button"
                className="text-primary underline underline-offset-4"
                onClick={() => void loadNorthStar()}
                data-testid="north-star-retry"
              >
                重试
              </button>
            </div>
          ) : nsView ? (
            <NorthStarBlock view={nsView} />
          ) : null}
          {sources.length === 0 ? (
            <EmptyState
              title="暂无运行中的源"
              action={
                <button
                  type="button"
                  className="text-primary underline underline-offset-4"
                  onClick={() => navigate('/sources')}
                  data-testid="dashboard-empty-link"
                >
                  去源管理页启用
                </button>
              }
              testId="dashboard-empty"
            />
          ) : (
            <>
              <section className="grid grid-cols-2 gap-4 lg:grid-cols-4">
                {/* 今日总入库可点（T214 全局下钻）；去重拦截不可点（未入库无可列——title 明示） */}
                <Card data-testid="dashboard-stat-today-new" data-value={String(view.global.todayNewCount)}>
                  <CardHeader>
                    <CardTitle className="text-sm font-normal text-muted-foreground">今日总入库</CardTitle>
                  </CardHeader>
                  <CardContent>
                    <ClickableNumber
                      value={String(view.global.todayNewCount)}
                      hoverTitle="点击查看今日入库条目分页列表（全部源 · l0=ALL · 入库日=今日）"
                      onClick={openGlobalToday}
                      testId="dashboard-stat-today-new-value"
                    />
                    <p
                      className="mt-1 text-xs text-muted-foreground"
                      data-testid="dashboard-stat-today-dup"
                      title={NON_CLICKABLE_TITLES.dup}
                    >
                      去重拦截 {view.global.todayDupCount} 条
                    </p>
                  </CardContent>
                </Card>
                <StatCard
                  testId="dashboard-stat-active"
                  label="活跃源数"
                  value={String(view.global.activeSourceCount)}
                  sub="今日 ≥1 次成功抓取"
                  hoverTitle={NON_CLICKABLE_TITLES.active}
                />
                <StatCard
                  testId="dashboard-stat-failed"
                  label="失败源数"
                  value={String(view.global.failedSourceCount)}
                  sub="今日有失败或退避中"
                  hoverTitle={NON_CLICKABLE_TITLES.failed}
                />
                <Card data-testid="dashboard-stat-latency" title={NON_CLICKABLE_TITLES.latency}>
                  <CardHeader>
                    <CardTitle className="flex items-center gap-2 text-sm font-normal text-muted-foreground">
                      感知延迟
                      <Badge
                        className="bg-muted text-muted-foreground"
                        data-testid="dashboard-latency-basis"
                        title={`口径 ${view.global.latency.basis}；排除日粒度源 ${
                          view.global.latency.excludedSourceCodes.join('、') || '无'
                        }`}
                      >
                        仅增量轮
                      </Badge>
                    </CardTitle>
                  </CardHeader>
                  <CardContent className="flex flex-wrap items-center gap-2">
                    <Badge className="bg-emerald-500/15 text-emerald-400" data-testid="dashboard-latency-p50">
                      P50 {formatLatency(view.global.latency.p50Millis)}
                    </Badge>
                    <Badge className="bg-sky-500/15 text-sky-400" data-testid="dashboard-latency-p90">
                      P90 {formatLatency(view.global.latency.p90Millis)}
                    </Badge>
                    <span className="text-xs text-muted-foreground">
                      样本 <span data-testid="dashboard-latency-sample">{view.global.latency.sampleCount}</span> 条
                    </span>
                  </CardContent>
                </Card>
              </section>

              <section className="flex flex-col gap-2">
                <div className="flex flex-wrap items-center gap-3">
                  <h2 className="text-xs text-muted-foreground">源维度</h2>
                  <label className="ml-auto flex items-center gap-2 text-sm text-muted-foreground">
                    <input
                      type="checkbox"
                      checked={showArchived}
                      onChange={(e) => setShowArchived(e.target.checked)}
                      data-testid="dashboard-show-archived"
                    />
                    显示停用/归档源
                  </label>
                </div>
                {/* >30 源形态（M18 T155）：行数超阈值启用纵向滚动 + 表头吸附，渲染不退化 */}
                <div
                  className={cn(
                    'overflow-x-auto rounded-lg border',
                    sourceTableScrollable && 'max-h-[34rem] overflow-y-auto',
                  )}
                  data-testid={sourceTableScrollable ? 'dashboard-source-scroll' : undefined}
                >
                  <table
                    className="w-full min-w-[860px] text-sm"
                    data-testid="dashboard-source-table"
                  >
                    <thead className={sourceTableScrollable ? 'sticky top-0 z-10 bg-card' : undefined}>
                      <tr className="border-b text-left text-xs text-muted-foreground">
                        <th className="px-3 py-2 font-normal">源</th>
                        <th className="px-3 py-2 font-normal">类型</th>
                        <th className="px-3 py-2 font-normal">今日新增</th>
                        <th className="px-3 py-2 font-normal">累计条数</th>
                        <th className="px-3 py-2 font-normal">最近抓取</th>
                        <th className="px-3 py-2 font-normal">状态</th>
                        <th className="px-3 py-2 font-normal">最近错误</th>
                      </tr>
                    </thead>
                    <tbody>
                      {visibleSources.map((row) => {
                        const type = typeBadgeOf(row.adapterType);
                        const status = statusBadgeOf(row);
                        return (
                          <tr
                            key={row.sourceCode}
                            data-testid={`dashboard-source-row-${row.sourceCode}`}
                            data-code={row.sourceCode}
                            className={`border-b last:border-b-0 ${row.abnormal ? 'bg-rose-500/5' : ''} ${
                              row.deleted ? 'opacity-60' : ''
                            }`}
                          >
                            <td className="px-3 py-2">
                              <span className="font-medium">{row.name}</span>
                              <span className="ml-2 text-xs text-muted-foreground">
                                每 {row.intervalMinutes} 分钟
                              </span>
                            </td>
                            <td className="px-3 py-2">
                              <Badge className={type.className}>{type.label}</Badge>
                            </td>
                            <td className="px-3 py-2">
                              {/* 今日新增可点（T214 源行下钻）；败次为并列展示不可点 */}
                              <ClickableNumber
                                value={String(row.todayNewCount)}
                                hoverTitle="点击查看该源今日入库条目（l0=ALL · 入库日=今日）"
                                onClick={() => openSourceToday(row)}
                                testId={`dashboard-source-today-${row.sourceCode}`}
                                dataValue={String(row.todayNewCount)}
                                compact
                              />
                              {row.todayFailCount > 0 ? (
                                <span className="ml-1 text-xs text-rose-400">
                                  （败 {row.todayFailCount}）
                                </span>
                              ) : null}
                            </td>
                            <td className="px-3 py-2">
                              {/* 累计条数可点（T214 源行下钻，无时间窗） */}
                              <ClickableNumber
                                value={String(row.totalCount)}
                                hoverTitle="点击查看该源累计条目（l0=ALL · 不限入库时间）"
                                onClick={() => openSourceTotal(row)}
                                testId={`dashboard-source-total-${row.sourceCode}`}
                                dataValue={String(row.totalCount)}
                                compact
                              />
                            </td>
                            <td className="px-3 py-2 text-muted-foreground">
                              {formatTime(row.lastAttemptAt)}
                            </td>
                            <td className="px-3 py-2">
                              <div className="flex flex-wrap items-center gap-1">
                                <Badge
                                  className={status.className}
                                  data-testid={`dashboard-source-status-${row.sourceCode}`}
                                  title={
                                    row.consecutiveFailures > 0
                                      ? `连续失败 ${row.consecutiveFailures} 轮${
                                          row.backoffUntil ? ` · ${formatTime(row.backoffUntil)} 后重试` : ''
                                        }`
                                      : undefined
                                  }
                                >
                                  {status.text}
                                </Badge>
                                {row.staleSince ? (
                                  <Badge
                                    className="bg-amber-500/15 text-amber-400"
                                    title="连续多日零净入库，自动标记（M15 T128）；恢复入库后自动解除"
                                    data-testid={`dashboard-source-stale-${row.sourceCode}`}
                                  >
                                    疑似停更 {row.staleSince}
                                  </Badge>
                                ) : null}
                              </div>
                            </td>
                            <td
                              className="max-w-[220px] truncate px-3 py-2 text-xs text-muted-foreground"
                              title={row.lastError ?? undefined}
                              data-testid={`dashboard-source-error-${row.sourceCode}`}
                            >
                              {row.lastError ? truncateText(row.lastError, 30) : '—'}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              </section>

              <section className="flex flex-col gap-2">
                <h2 className="text-xs text-muted-foreground">近期失败</h2>
                {view.failures.length === 0 ? (
                  <p
                    className="py-6 text-center text-sm text-muted-foreground"
                    data-testid="dashboard-failures-empty"
                  >
                    近期无失败记录
                  </p>
                ) : (
                  <>
                    {/* V2.4 T215 折叠：收起态最多 3 条（时间倒序前 3），展开限高滚动看全部（沿 >30 源形态先例） */}
                    <div
                      className={cn(
                        'flex flex-col divide-y rounded-lg border',
                        failuresExpanded && 'max-h-72 overflow-y-auto',
                      )}
                      data-testid={failuresExpanded ? 'dashboard-failures-scroll' : undefined}
                    >
                      {(failuresExpanded
                        ? view.failures
                        : view.failures.slice(0, FAILURES_COLLAPSED_LIMIT)
                      ).map((failure, index) => (
                        <div
                          key={`${failure.sourceCode}-${failure.occurredAt}-${index}`}
                          className="flex cursor-pointer flex-wrap items-center gap-x-3 gap-y-1 px-3 py-2 text-sm hover:bg-muted/50"
                          data-testid={`dashboard-failure-${index}`}
                          onClick={() => navigate(`/sources?source=${failure.sourceCode}`)}
                        >
                          <span className="font-medium">{failure.sourceName}</span>
                          <span className="text-xs text-muted-foreground">
                            {formatTime(failure.occurredAt)}
                          </span>
                          <span className="text-xs text-muted-foreground">
                            {failure.origin === 'state' ? '现态' : '事件'}
                          </span>
                          <span
                            className="min-w-0 flex-1 truncate text-xs text-muted-foreground"
                            title={failure.errorSummary}
                          >
                            {failure.errorSummary}
                          </span>
                          <button
                            type="button"
                            className="text-xs text-primary underline underline-offset-4"
                            data-testid={`dashboard-failure-jump-${index}`}
                            onClick={(e) => {
                              e.stopPropagation();
                              navigate(`/sources?source=${failure.sourceCode}`);
                            }}
                          >
                            去处置
                          </button>
                        </div>
                      ))}
                    </div>
                    <div className="flex flex-wrap items-center gap-3">
                      {view.failures.length > FAILURES_COLLAPSED_LIMIT ? (
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => setFailuresExpanded((v) => !v)}
                          data-testid="dashboard-failures-toggle"
                        >
                          {failuresExpanded
                            ? '收起'
                            : `展开全部 ${view.failures.length} 条`}
                        </Button>
                      ) : null}
                      {hiddenRecovered > 0 ? (
                        <span
                          className="text-xs text-muted-foreground"
                          data-testid="dashboard-failures-hidden-recovered"
                          title="已恢复/停用/归档源的失败记录不展示（事件留痕零删除，追溯走 Job 日志/失败事件留痕面）"
                        >
                          已隐藏恢复/停用源失败 {hiddenRecovered} 条（留痕可查）
                        </span>
                      ) : null}
                    </div>
                  </>
                )}
              </section>
            </>
          )}
          {error ? (
            <p className="text-sm text-destructive" role="alert">
              {error}
            </p>
          ) : null}
        </div>
      ) : null}

      {/* V2.4 T214 三处数字下钻弹框（独立请求，不随大盘 30s 轮询重发） */}
      <NewsItemsDialog spec={dialogSpec} onClose={() => setDialogSpec(null)} />
    </main>
  );
}

export default FeedDashboard;
