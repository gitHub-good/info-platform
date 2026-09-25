import { useCallback, useEffect, useRef, useState } from 'react';
import { getFeedDashboard } from '@/api/feedDashboard';
import { ApiError } from '@/api/http';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { navigate } from '@/lib/navigation';
import type { FeedDashboardSourceRow, FeedDashboardView } from '@/types/feedDashboard';

// 抓取大盘页（M14 T116，#/feed-dashboard 运维组第 15 页——REQ 故事 2 / 拍板二）。
// 三区块只读：全局统计卡四指标 + 感知延迟 P50/P90 徽章（仅增量轮口径标注）、
// 源维度表（异常置顶/五态徽章复用源管理页语义/归档源默认折叠）、近期失败列表（点击跳源管理页定位）。
// 近实时：30s 自动刷新（document.hidden 暂停）+ 手动刷新 +「更新于 N 秒前」；三态（加载/错误/数据）与空态引导。

/** 自动刷新间隔（蓝图 30 秒级建议值）。 */
const AUTO_REFRESH_MILLIS = 30_000;

/** 「更新于 N 秒前」计时精度。 */
const NOW_TICK_MILLIS = 1_000;

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
}: {
  testId: string;
  label: string;
  value: string;
  sub?: string;
  subTestId?: string;
  dataValue?: string;
}) {
  return (
    <Card data-testid={testId} data-value={dataValue}>
      <CardHeader>
        <CardTitle className="text-sm font-normal text-muted-foreground">{label}</CardTitle>
      </CardHeader>
      <CardContent>
        <p className="text-2xl font-medium">{value}</p>
        {sub ? (
          <p className="mt-1 text-xs text-muted-foreground" data-testid={subTestId}>
            {sub}
          </p>
        ) : null}
      </CardContent>
    </Card>
  );
}

export function FeedDashboard() {
  const [view, setView] = useState<FeedDashboardView | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [lastFetchedAt, setLastFetchedAt] = useState<number | null>(null);
  const [nowTick, setNowTick] = useState(() => Date.now());
  const [showArchived, setShowArchived] = useState(false);
  const abortRef = useRef<AbortController | null>(null);

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

  useEffect(() => {
    void load();
    return () => abortRef.current?.abort();
  }, [load]);

  // 30s 近实时刷新：document.hidden 暂停（隐藏期间跳过本轮不请求）
  useEffect(() => {
    const timer = window.setInterval(() => {
      if (document.hidden) return;
      void load();
    }, AUTO_REFRESH_MILLIS);
    return () => window.clearInterval(timer);
  }, [load]);

  // 「更新于 N 秒前」秒级计时
  useEffect(() => {
    const timer = window.setInterval(() => setNowTick(Date.now()), NOW_TICK_MILLIS);
    return () => window.clearInterval(timer);
  }, []);

  const secondsAgo =
    lastFetchedAt == null ? null : Math.max(0, Math.floor((nowTick - lastFetchedAt) / 1000));

  const sources = view?.sources ?? [];
  const visibleSources = showArchived ? sources : sources.filter((row) => !row.deleted);

  return (
    <main className="mx-auto w-full max-w-6xl p-4 sm:p-6" data-testid="feed-dashboard-page">
      <header className="mb-4">
        <h1 className="text-xl font-medium">抓取大盘</h1>
        <div className="mt-1 flex flex-wrap items-center gap-3">
          <p className="max-w-3xl text-sm text-muted-foreground">
            全部资讯源今日入库、运行状态与近期失败的一屏近实时视图（只读）；处置动作跳「资讯源管理」页完成。
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
              onClick={() => void load()}
              data-testid="dashboard-refresh"
            >
              刷新
            </Button>
          </span>
        </div>
      </header>

      {loading ? (
        <div className="flex flex-col gap-4" data-testid="dashboard-loading">
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
          <Button variant="outline" size="sm" onClick={() => void load()} data-testid="dashboard-retry">
            重试
          </Button>
        </div>
      ) : view ? (
        <div className="flex flex-col gap-4">
          {sources.length === 0 ? (
            <p
              className="py-10 text-center text-sm text-muted-foreground"
              data-testid="dashboard-empty"
            >
              暂无运行中的源，
              <button
                type="button"
                className="text-primary underline underline-offset-4"
                onClick={() => navigate('/info-sources')}
                data-testid="dashboard-empty-link"
              >
                去源管理页启用
              </button>
            </p>
          ) : (
            <>
              <section className="grid grid-cols-2 gap-4 lg:grid-cols-4">
                <StatCard
                  testId="dashboard-stat-today-new"
                  label="今日总入库"
                  value={String(view.global.todayNewCount)}
                  sub={`去重拦截 ${view.global.todayDupCount} 条`}
                  subTestId="dashboard-stat-today-dup"
                  dataValue={String(view.global.todayNewCount)}
                />
                <StatCard
                  testId="dashboard-stat-active"
                  label="活跃源数"
                  value={String(view.global.activeSourceCount)}
                  sub="今日 ≥1 次成功抓取"
                />
                <StatCard
                  testId="dashboard-stat-failed"
                  label="失败源数"
                  value={String(view.global.failedSourceCount)}
                  sub="今日有失败或退避中"
                />
                <Card data-testid="dashboard-stat-latency">
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
                <div className="overflow-x-auto rounded-lg border">
                  <table
                    className="w-full min-w-[860px] text-sm"
                    data-testid="dashboard-source-table"
                  >
                    <thead>
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
                              <span data-testid={`dashboard-source-today-${row.sourceCode}`}>
                                {row.todayNewCount}
                              </span>
                              {row.todayFailCount > 0 ? (
                                <span className="ml-1 text-xs text-rose-400">
                                  （败 {row.todayFailCount}）
                                </span>
                              ) : null}
                            </td>
                            <td className="px-3 py-2" data-testid={`dashboard-source-total-${row.sourceCode}`}>
                              {row.totalCount}
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
                  <div className="flex flex-col divide-y rounded-lg border">
                    {view.failures.map((failure, index) => (
                      <div
                        key={`${failure.sourceCode}-${failure.occurredAt}-${index}`}
                        className="flex cursor-pointer flex-wrap items-center gap-x-3 gap-y-1 px-3 py-2 text-sm hover:bg-muted/50"
                        data-testid={`dashboard-failure-${index}`}
                        onClick={() => navigate(`/info-sources?source=${failure.sourceCode}`)}
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
                            navigate(`/info-sources?source=${failure.sourceCode}`);
                          }}
                        >
                          去处置
                        </button>
                      </div>
                    ))}
                  </div>
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
    </main>
  );
}

export default FeedDashboard;
