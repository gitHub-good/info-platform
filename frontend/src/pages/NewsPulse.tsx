import { useCallback, useEffect, useMemo, useState } from 'react';
import { Activity, RefreshCw } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { MarketTabs } from '@/components/common/MarketTabs';
import { EmptyState } from '@/components/ui/EmptyState';
import { PageHeader } from '@/components/ui/PageHeader';
import { Skeleton } from '@/components/ui/skeleton';
import { WINDOW_LABELS, listPulseWindows, refreshPulse, type NewsPulseWindowKey } from '@/api/newsPulse';
import { ApiError } from '@/api/http';
import { useMarketParam } from '@/hooks/useMarketParam';
import { directionToneClass, formatDateTime } from '@/lib/format';
import type { PulseAnalysis, PulseIndustryCount, PulseMarketStat, PulseRow } from '@/types/newsPulse';

/** 非 ApiError 兜底文案。 */
function messageOf(err: unknown, fallback: string): string {
  return err instanceof ApiError ? err.msg : fallback;
}

/** 情绪徽章配色：市场方向轨经 directionToneClass 单点（偏多=up/偏空=down/中性灰，M26 T229 收口）。 */
function sentimentClass(sentiment: string | undefined): string {
  if (sentiment === '偏多') return directionToneClass('up');
  if (sentiment === '偏空') return directionToneClass('down');
  return 'bg-muted text-muted-foreground';
}

/** 市场类型徽章（装饰性分类色，非方向轨）：A股 amber / 港股 sky / 美股 violet。 */
function marketBadgeClass(market: string): string {
  if (market === 'A股') return 'border-amber-500/40 text-amber-500';
  if (market === '港股') return 'border-sky-500/30 text-sky-400';
  if (market === '美股') return 'border-violet-500/30 text-violet-400';
  return 'border-border text-muted-foreground';
}

/** 统计 JSON 列安全解析（损坏 → 空数组不阻塞页面）。 */
function parseJsonArray<T>(json: string | null | undefined): T[] {
  if (!json) return [];
  try {
    const parsed = JSON.parse(json);
    return Array.isArray(parsed) ? (parsed as T[]) : [];
  } catch {
    return [];
  }
}

function parseAnalysis(row: PulseRow | null): PulseAnalysis | null {
  if (!row?.analysis) return null;
  try {
    return JSON.parse(row.analysis) as PulseAnalysis;
  } catch {
    return null;
  }
}

const MARKETS = ['A股', '港股', '美股'] as const;

/** 三市场概览卡（AI overview + 情绪徽章；无分析时降级显示规则统计的市场归集）。 */
function MarketOverviewSection({
  analysis,
  marketStats,
}: {
  analysis: PulseAnalysis | null;
  marketStats: PulseMarketStat[];
}) {
  const statOf = (market: string) => marketStats.find((s) => s.market === market);
  return (
    <div className="grid grid-cols-1 gap-3 md:grid-cols-3" data-testid="pulse-markets">
      {MARKETS.map((market) => {
        const stat = statOf(market);
        return (
          <Card key={market} data-testid={`pulse-market-${market}`}>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-sm">
                <span>{market}</span>
                {analysis ? (
                  <Badge className={sentimentClass(analysis.sentiment?.[market])}>
                    {analysis.sentiment?.[market] ?? '--'}
                  </Badge>
                ) : null}
                <span className="ml-auto text-xs font-normal text-muted-foreground">
                  {stat ? `${stat.newsCount} 条` : '0 条'}
                </span>
              </CardTitle>
            </CardHeader>
            <CardContent className="text-sm text-muted-foreground">
              <p className="whitespace-pre-wrap break-words" data-testid={`pulse-overview-${market}`}>
                {analysis?.overview?.[market] ?? '窗口内无相关资讯或 AI 分析降级'}
              </p>
              {stat && stat.topSubjects.length > 0 ? (
                <div className="mt-2 flex flex-wrap gap-1">
                  {stat.topSubjects.map((subject) => (
                    <Badge key={subject} variant="secondary" className="font-normal">
                      {subject}
                    </Badge>
                  ))}
                </div>
              ) : null}
            </CardContent>
          </Card>
        );
      })}
    </div>
  );
}

/** 热点主线（AI hotTracks）：横条长度 = newsCount 相对占比（规则可对账数字）。 */
function HotTracksSection({ analysis }: { analysis: PulseAnalysis }) {
  const max = Math.max(...analysis.hotTracks.map((track) => track.newsCount), 1);
  return (
    <Card data-testid="pulse-hot-tracks">
      <CardHeader>
        <CardTitle className="text-base">热点主线（AI 归纳）</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {analysis.hotTracks.map((track, index) => (
          <div key={`${track.name}-${index}`} data-testid={`pulse-track-${index}`}>
            <div className="flex flex-wrap items-center gap-1.5 text-sm">
              <span className="font-medium">{track.name}</span>
              <Badge variant={track.type === '概念' ? 'outline' : 'secondary'}>{track.type}</Badge>
              {track.markets.map((market) => (
                <Badge key={market} variant="outline" className={marketBadgeClass(market)}>
                  {market}
                </Badge>
              ))}
              <span className="text-xs text-muted-foreground">{track.newsCount} 条</span>
            </div>
            <div className="mt-1 h-2 w-full overflow-hidden rounded-full bg-muted/50">
              <div
                className="h-full rounded-full bg-gradient-to-r from-sky-500/70 to-violet-500/70"
                style={{ width: `${Math.max(8, Math.round((track.newsCount / max) * 100))}%` }}
              />
            </div>
            {track.summary ? (
              <p className="mt-1 text-xs text-muted-foreground">{track.summary}</p>
            ) : null}
          </div>
        ))}
      </CardContent>
    </Card>
  );
}

/** 关键事件（AI keyEvents）：重要性星级 + 市场/行业/概念/标的 chips。 */
function KeyEventsSection({ analysis }: { analysis: PulseAnalysis }) {
  return (
    <Card data-testid="pulse-key-events">
      <CardHeader>
        <CardTitle className="text-base">关键事件（重要性降序）</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {analysis.keyEvents.map((event, index) => (
          <div
            key={`${event.title}-${index}`}
            className="rounded-xl border border-border bg-muted/20 p-3"
            data-testid={`pulse-event-${index}`}
          >
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-amber-400" title={`重要性 ${event.importance}/5`}>
                {'★'.repeat(Math.max(1, Math.min(5, event.importance)))}
                <span className="text-muted-foreground/50">
                  {'★'.repeat(5 - Math.max(1, Math.min(5, event.importance)))}
                </span>
              </span>
              <span className="font-medium">{event.title}</span>
            </div>
            {event.summary ? (
              <p className="mt-1 text-sm text-muted-foreground">{event.summary}</p>
            ) : null}
            <div className="mt-2 flex flex-wrap gap-1">
              {event.markets.map((market) => (
                <Badge key={market} variant="outline" className={marketBadgeClass(market)}>
                  {market}
                </Badge>
              ))}
              {event.industries.map((industry) => (
                <Badge key={industry} variant="secondary">
                  {industry}
                </Badge>
              ))}
              {event.concepts.map((concept) => (
                <Badge key={concept} variant="outline" className="border-violet-500/30 text-violet-400">
                  {concept}
                </Badge>
              ))}
              {event.subjects.map((subject) => (
                <Badge key={subject} variant="outline" className="border-primary/30 text-primary">
                  {subject}
                </Badge>
              ))}
            </div>
          </div>
        ))}
      </CardContent>
    </Card>
  );
}

/** 行业分布（规则统计硬数据）：L1 计数横条。 */
function IndustryStatsSection({ stats }: { stats: PulseIndustryCount[] }) {
  const max = Math.max(...stats.map((s) => s.count), 1);
  return (
    <Card data-testid="pulse-industry-stats">
      <CardHeader>
        <CardTitle className="text-base">行业资讯分布（规则统计）</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-2">
        {stats.map((stat) => (
          <div key={stat.industry} className="flex items-center gap-2 text-sm">
            <span className="w-20 shrink-0 truncate" title={stat.industry}>
              {stat.industry}
            </span>
            <div className="h-2 flex-1 overflow-hidden rounded-full bg-muted/50">
              <div
                className="h-full rounded-full bg-gradient-to-r from-red-500/70 to-amber-500/70"
                style={{ width: `${Math.max(6, Math.round((stat.count / max) * 100))}%` }}
                data-testid={`pulse-industry-bar-${stat.industry}`}
              />
            </div>
            <span className="w-10 shrink-0 text-right text-xs text-muted-foreground">
              {stat.count}
            </span>
          </div>
        ))}
      </CardContent>
    </Card>
  );
}

/**
 * 资讯脉搏页（V3.2 M28，第 19 页·分析组）：抓取资讯按 30m/1h/3h/6h/12h/24h 六窗归纳——
 * 规则统计（行业分布 + 市场归集 A股/港股/美股/未关联）恒可看 + AI 事件归纳（概览/热点主线/关键事件/情绪面），
 * 手动刷新（5min 守卫）。AI 输出仅归纳输入条目，不构成投资建议。
 * M29 T257：三市场切换（?market= 持久化；market 参数随请求下发——§5.3 契约零变化，
 * 归集四桶恒全量回显不隐藏，联调差异由 T258 收口）。
 */
export function NewsPulse() {
  const [windowKey, setWindowKey] = useState<NewsPulseWindowKey>('1h');
  const [market, setMarket] = useMarketParam();
  const [latest, setLatest] = useState<PulseRow | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const reload = useCallback(
    async (key: NewsPulseWindowKey, signal?: AbortSignal) => {
      // 六窗总览一次取全（tab 切换零额外请求）
      const views = await listPulseWindows(market, signal);
      return views.find((view) => view.windowKey === key)?.latest ?? null;
    },
    [market],
  );

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    reload(windowKey, controller.signal)
      .then((row) => {
        if (controller.signal.aborted) return;
        setLatest(row);
      })
      .catch((err) => {
        if (controller.signal.aborted) return;
        setError(messageOf(err, '资讯脉搏加载失败'));
        setLatest(null);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [windowKey, reload]);

  const handleRefresh = async () => {
    setRefreshing(true);
    setActionError(null);
    try {
      setLatest(await refreshPulse(windowKey, market));
    } catch (err) {
      setActionError(messageOf(err, '刷新失败'));
    } finally {
      setRefreshing(false);
    }
  };

  const analysis = useMemo(() => parseAnalysis(latest), [latest]);
  const industryStats = useMemo(
    () => parseJsonArray<PulseIndustryCount>(latest?.industryStats),
    [latest],
  );
  const marketStats = useMemo(
    () => parseJsonArray<PulseMarketStat>(latest?.marketStats),
    [latest],
  );
  const classifiedPct =
    latest && latest.newsCount > 0
      ? Math.round((latest.classifiedCount / latest.newsCount) * 100)
      : 0;

  return (
    <main className="mx-auto w-full max-w-7xl p-4 sm:p-6" data-testid="news-pulse-page">
      <PageHeader
        title="资讯脉搏"
        subtitle="抓取资讯按时间窗 AI 归纳：大盘概览 · 热点主线 · 关键事件 · 行业/市场归集（A股/港股/美股）"
        actions={
          <Button
            size="sm"
            onClick={() => void handleRefresh()}
            disabled={refreshing || loading}
            data-testid="pulse-refresh"
          >
            <RefreshCw className={`size-4 ${refreshing ? 'animate-spin' : ''}`} aria-hidden />
            {refreshing ? '分析中…' : '手动刷新'}
          </Button>
        }
      />

      {/* 三市场切换（M29：统一 MarketTabs；归集四桶恒全量展示不受切换影响——拍板三不隐藏桶） */}
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <MarketTabs value={market} onChange={setMarket} />
      </div>

      {/* 时间窗 tabs */}
      <div
        className="mb-4 flex flex-wrap items-center gap-2"
        role="tablist"
        aria-label="时间窗"
        data-testid="pulse-window-tabs"
      >
        {(Object.keys(WINDOW_LABELS) as NewsPulseWindowKey[]).map((key) => (
          <Button
            key={key}
            role="tab"
            aria-selected={key === windowKey}
            size="sm"
            variant={key === windowKey ? 'default' : 'outline'}
            onClick={() => setWindowKey(key)}
            data-testid={`pulse-tab-${key}`}
          >
            {WINDOW_LABELS[key]}
          </Button>
        ))}
      </div>

      {actionError ? (
        <p className="mb-3 text-sm text-destructive" role="alert" data-testid="pulse-action-error">
          {actionError}
        </p>
      ) : null}

      {loading ? (
        <div className="flex flex-col gap-3" data-testid="pulse-loading">
          <Skeleton className="h-8 w-full" />
          <Skeleton className="h-40 w-full" />
          <Skeleton className="h-64 w-full" />
        </div>
      ) : error ? (
        <div className="flex flex-col items-center gap-2 py-10" data-testid="pulse-error">
          <p className="text-sm text-destructive" role="alert">
            {error}
          </p>
          <Button variant="outline" size="sm" onClick={() => setWindowKey((k) => k)}>
            重试
          </Button>
        </div>
      ) : !latest ? (
        <EmptyState
          title="该时间窗尚未分析"
          description="点「手动刷新」立即分析，或等待下一个 30 分钟自动轮次"
          action={
            <Button size="sm" onClick={() => void handleRefresh()} disabled={refreshing} data-testid="pulse-empty-refresh">
              立即分析
            </Button>
          }
          testId="pulse-empty"
        />
      ) : (
        <div className="flex flex-col gap-4">
          {/* 快照元信息 */}
          <div
            className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground"
            data-testid="pulse-meta"
          >
            <span data-testid="pulse-window-range">
              窗口 {formatDateTime(latest.windowStart)} ~ {formatDateTime(latest.windowEnd)}
            </span>
            <span data-testid="pulse-news-count">资讯 {latest.newsCount} 条</span>
            <span data-testid="pulse-classified">已分类 {classifiedPct}%</span>
            {/* 未关联桶计数（M29 拍板三：四桶全量展示不隐藏——未关联 = 未回联标的的资讯） */}
            <span data-testid="pulse-market-unlinked">
              未关联 {marketStats.find((stat) => stat.market === '未关联')?.newsCount ?? 0} 条
            </span>
            {latest.degraded ? (
              <Badge className="bg-amber-500/15 text-amber-400" title={latest.degradedReason ?? undefined}>
                AI 分析降级
              </Badge>
            ) : (
              <Badge variant="secondary" title={latest.model ?? undefined}>
                {latest.model ?? '--'}
              </Badge>
            )}
            <span>分析于 {formatDateTime(latest.createdAt)}</span>
            <span className="text-muted-foreground/70">{latest.triggerSource === 'MANUAL' ? '手动' : '自动'}</span>
          </div>

          <MarketOverviewSection analysis={analysis} marketStats={marketStats} />

          {analysis ? (
            <>
              <HotTracksSection analysis={analysis} />
              <KeyEventsSection analysis={analysis} />
            </>
          ) : (
            <Card data-testid="pulse-degraded-note">
              <CardContent className="flex items-center gap-2 text-sm text-muted-foreground">
                <Activity className="size-4 text-amber-400" aria-hidden />
                AI 事件归纳暂不可用（{latest.degradedReason ?? 'LLM 降级'}），以上为规则统计；下一个轮次或手动刷新将重试。
              </CardContent>
            </Card>
          )}

          {industryStats.length > 0 ? <IndustryStatsSection stats={industryStats} /> : null}

          <p className="text-xs text-muted-foreground" data-testid="pulse-disclaimer">
            口径：窗口按资讯入库时刻圈定（最多取重要性前 150 条入 AI，统计计数为窗口全量）；市场由关联标的代码推导；AI
            仅归纳输入条目不引入外部事实，不构成投资建议。
          </p>
        </div>
      )}
    </main>
  );
}

export default NewsPulse;
