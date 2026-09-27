import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '@/api/http';
import { getMarketTopRank, getMarketTopVersions } from '@/api/marketTop';
import { trackReadingOnce } from '@/api/readingEvent';
import { addWatchlistItem, createWatchlist, listWatchlists } from '@/api/watchlist';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { formatDateTime } from '@/lib/format';
import { cn } from '@/lib/utils';
import type {
  MarketTopCitation,
  MarketTopDropped,
  MarketTopFactor,
  MarketTopItem,
  MarketTopRankView,
  MarketTopVersionSummary,
} from '@/types/marketTop';
import type { WatchlistView } from '@/types/watchlist';

// 全市场推荐页（M21 T184，#/market-top 全站第 20 页「分析」组第 7 项——方案 §4.8.1 + REQ 故事 2/3）。
// 榜单卡流 Top10：排名徽章（Top3 金银铜）/标的（跳详情）/总分与合成分/百分位/「有突破」/五维迷你条/
// 深析区（FULL 可展开论点+亮点+风险+引用下钻；FACTOR_ONLY 标注因子分排序）/变动徽章/一键加自选（幂等）；
// 页头日期与版本选择 + 生成信息 + 漏斗徽章链 + 降级横幅 + 跌出名单折叠 + 推荐中心/概览互链 + 免责常驻。
// 三态：加载骨架 / 空态（30089——每日 18:00 生成引导）/ 错误重试；埋点 MARKET_TOP_VIEW/ACT（adopt-v1 先例）。

/** 后端错误码：无任何榜单（MARKET_TOP_NOT_FOUND——Job 未跑过）。 */
const CODE_MARKET_TOP_NOT_FOUND = 30089;

/** 后端错误码：标的已在清单（幂等成功——按钮态照常切「已自选」）。 */
const CODE_ALREADY_IN_WATCHLIST = 30011;

/** 无清单用户自动创建的默认清单名（沿 RecommendationFeedbackService ADD_WATCHLIST 先例）。 */
const DEFAULT_WATCHLIST_NAME = '默认清单';

/** 空态引导口径（MARKET_TOP_JOB CRON 18:00 盘后日频）。 */
const EMPTY_HINT = '榜单每日 18:00 生成，生成后此处展示全市场 Top10 榜单。';

/** 降级横幅文案（batch.degradedReason 值域——需求锁定语义的呈现面）。 */
const DEGRADED_BANNERS: Record<string, { text: string; className: string }> = {
  COST_CAP: {
    text: '深析额度触顶，部分标的按因子分排序',
    className: 'border-amber-500/40 bg-amber-500/10 text-amber-400',
  },
  LLM_FAILURE: {
    text: '深析连续失败已暂停，本榜单按因子分排序',
    className: 'border-rose-500/40 bg-rose-500/10 text-rose-400',
  },
};

/** 触发来源展示名（triggerSource 值域）。 */
const TRIGGER_LABELS: Record<string, string> = { DAILY: '每日定时', MANUAL: '手动触发' };

/** 变动徽章配色（NEW emerald / UP amber / DOWN rose / SAME 灰）。 */
const CHANGE_BADGES: Record<string, string> = {
  NEW: 'bg-emerald-500/15 text-emerald-400',
  UP: 'bg-amber-500/15 text-amber-400',
  DOWN: 'bg-rose-500/15 text-rose-400',
  SAME: 'bg-muted text-muted-foreground',
};

/** 一位数展示（总分/因子分——沿 ValueScoreSection 口径）。 */
function formatNumber(value: number): string {
  return Math.round(value * 10) / 10 === value ? String(value) : value.toFixed(1);
}

function messageOf(err: unknown, fallback: string): string {
  return err instanceof ApiError && err.msg ? err.msg : fallback;
}

/** 排名徽章（Top3 金银铜色系裁量沿 token；4+ 灰）。 */
function RankBadge({ rankNo }: { rankNo: number }) {
  const tone =
    rankNo === 1
      ? 'bg-amber-400/20 text-amber-300'
      : rankNo === 2
        ? 'bg-slate-300/20 text-slate-300'
        : rankNo === 3
          ? 'bg-orange-500/15 text-orange-400'
          : 'bg-muted text-muted-foreground';
  return (
    <Badge className={cn(tone, 'text-sm tabular-nums')} data-testid={`market-top-rank-${rankNo}`}>
      {rankNo}
    </Badge>
  );
}

/** 变动徽章（NEW/UP/DOWN/SAME；title 带昨日名次）。 */
function ChangeBadge({ item }: { item: MarketTopItem }) {
  const tone = CHANGE_BADGES[item.changeType] ?? CHANGE_BADGES.SAME;
  const label =
    item.changeType === 'NEW' || item.prevRank == null
      ? item.changeType
      : `${item.changeType} ${item.prevRank}→${item.rankNo}`;
  return (
    <Badge className={tone} title={`昨日第 ${item.prevRank ?? '—'} 名`} data-testid="market-top-change">
      {label}
    </Badge>
  );
}

/** 五维迷你分解条（权重 0 维弱化——沿 ValueScoreSection 先例）。 */
function FactorMiniBars({ factors }: { factors: MarketTopFactor[] }) {
  return (
    <div className="flex flex-col gap-1" data-testid="market-top-factors">
      {factors.map((factor) => (
        <div
          key={factor.key}
          className={cn('flex items-center gap-2', factor.weight === 0 && 'opacity-60')}
          data-testid={`market-top-factor-${factor.key}`}
        >
          <span className="w-16 shrink-0 text-xs text-muted-foreground">{factor.name}</span>
          <div className="h-1.5 flex-1 overflow-hidden rounded bg-muted">
            <div
              className="h-full rounded bg-primary/70"
              style={{ width: `${Math.max(0, Math.min(100, factor.score))}%` }}
            />
          </div>
          <span className="w-10 shrink-0 text-right text-xs tabular-nums">
            {formatNumber(factor.score)}
          </span>
          <span className="w-14 shrink-0 text-right text-[10px] text-muted-foreground">
            {factor.weight === 0 ? '未启用' : `权重 ${factor.weight.toFixed(2)}`}
          </span>
        </div>
      ))}
    </div>
  );
}

/** 引用 chip（EVENT → 事件流 focus 下钻 / NEWS → 资讯库）。 */
function CitationChip({ citation }: { citation: MarketTopCitation }) {
  const isEvent = citation.type === 'EVENT';
  return (
    <a
      href={isEvent ? `#/events?focus=${citation.id}` : '#/news-library'}
      data-testid={`market-top-citation-${citation.type}-${citation.id}`}
      className="rounded bg-secondary px-1.5 py-0.5 text-[10px] text-secondary-foreground underline-offset-2 transition-colors hover:bg-secondary/70 hover:underline"
    >
      {isEvent ? `事件 #${citation.id}` : `资讯 #${citation.id}`}
    </a>
  );
}

/** 深析区（FULL：摘要可展开论点+亮点+风险+引用；FACTOR_ONLY：额度满标注）。 */
function DiveArea({ item }: { item: MarketTopItem }) {
  const [expanded, setExpanded] = useState(false);
  if (item.generation !== 'FULL') {
    return (
      <p className="text-xs text-muted-foreground" data-testid="market-top-dive-factor-only">
        因子分排序（深析额度已满）
      </p>
    );
  }
  const citations = item.diveDetail?.citations ?? [];
  return (
    <div className="flex flex-col gap-1.5" data-testid="market-top-dive">
      <p className="text-sm text-foreground/90" data-testid="market-top-dive-summary">
        {item.diveSummary ?? '本期未产出深析摘要。'}
      </p>
      <div className="flex flex-wrap items-center gap-2">
        <Button
          variant="ghost"
          size="sm"
          className="h-6 px-2 text-xs"
          onClick={() => setExpanded((prev) => !prev)}
          data-testid="market-top-dive-toggle"
        >
          {expanded ? '收起深析' : '展开深析'}
        </Button>
        {citations.length > 0 ? (
          <Badge variant="secondary" className="text-[10px]" data-testid="market-top-dive-citations">
            引用 {citations.length}
          </Badge>
        ) : null}
      </div>
      {expanded ? (
        <div
          className="flex flex-col gap-1.5 rounded-md border border-border bg-muted/30 p-2"
          data-testid="market-top-dive-detail"
        >
          <p className="text-xs" data-testid="market-top-dive-thesis">
            {item.diveDetail?.thesis ?? '--'}
          </p>
          {item.diveDetail?.highlights?.length ? (
            <ul className="flex flex-col gap-0.5" data-testid="market-top-dive-highlights">
              {item.diveDetail.highlights.map((entry, idx) => (
                <li key={idx} className="text-xs text-emerald-500/90">
                  ＋ {entry.text}
                </li>
              ))}
            </ul>
          ) : null}
          {item.diveDetail?.risks?.length ? (
            <ul className="flex flex-col gap-0.5" data-testid="market-top-dive-risks">
              {item.diveDetail.risks.map((entry, idx) => (
                <li key={idx} className="text-xs text-rose-500/90">
                  － {entry.text}
                </li>
              ))}
            </ul>
          ) : null}
          {citations.length > 0 ? (
            <div
              className="flex flex-wrap gap-1"
              data-testid="market-top-dive-citation-chips"
            >
              {citations.map((citation) => (
                <CitationChip key={`${citation.type}-${citation.id}`} citation={citation} />
              ))}
            </div>
          ) : null}
        </div>
      ) : null}
      <p className="text-[10px] text-muted-foreground">深析由 LLM 生成并经引用对账，仅供参考</p>
    </div>
  );
}

/** 一键加自选按钮（三态：加自选/加入中/已自选；失败就地提示可重试）。 */
function AddWatchlistButton({
  item,
  inWatchlist,
  adding,
  error,
  onAdd,
}: {
  item: MarketTopItem;
  inWatchlist: boolean;
  adding: boolean;
  error: string | null;
  onAdd: (item: MarketTopItem) => void;
}) {
  return (
    <span className="flex items-center gap-2">
      {inWatchlist ? (
        <Badge variant="secondary" data-testid={`market-top-inwatch-${item.subjectCode}`}>
          已自选
        </Badge>
      ) : (
        <Button
          variant="outline"
          size="sm"
          className="h-6 px-2 text-xs"
          disabled={adding}
          onClick={() => onAdd(item)}
          data-testid={`market-top-addwatch-${item.subjectCode}`}
        >
          {adding ? '加入中…' : '加自选'}
        </Button>
      )}
      {error ? (
        <span
          className="text-xs text-destructive"
          role="alert"
          data-testid={`market-top-watch-error-${item.subjectCode}`}
        >
          {error}
        </span>
      ) : null}
    </span>
  );
}

/** 榜单卡（排名徽章 + 标的 + 分数区 + 五维条 + 深析区 + 操作行）。 */
function MarketTopCard({
  item,
  inWatchlist,
  adding,
  watchError,
  onAdd,
}: {
  item: MarketTopItem;
  inWatchlist: boolean;
  adding: boolean;
  watchError: string | null;
  onAdd: (item: MarketTopItem) => void;
}) {
  return (
    <Card data-testid={`market-top-card-${item.subjectCode}`}>
      <CardContent className="flex flex-col gap-2 p-4">
        <div className="flex flex-wrap items-center gap-2">
          <RankBadge rankNo={item.rankNo} />
          <a
            href={`#/subjects/${item.subjectCode}`}
            data-testid={`market-top-subject-${item.subjectCode}`}
            className="text-sm font-medium text-primary underline underline-offset-2"
            title={item.subjectName}
          >
            {item.subjectName}
          </a>
          <span className="font-mono text-xs text-muted-foreground">{item.subjectCode}</span>
          <ChangeBadge item={item} />
          {item.breakthrough ? (
            <Badge
              className="bg-emerald-500/15 font-medium text-emerald-400"
              title="事件催化/行业传导/风险安全三阈值同时满足"
              data-testid="market-top-breakthrough"
            >
              有突破
            </Badge>
          ) : null}
          {item.percentile != null ? (
            <Badge variant="secondary" data-testid="market-top-percentile">
              超过全市场 {formatNumber(item.percentile)}%
            </Badge>
          ) : null}
          <span className="ml-auto text-[10px] text-muted-foreground" data-testid="market-top-last-event">
            最近事件 {item.lastEventDate ?? '--'}
          </span>
        </div>

        <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
          <span className="text-2xl font-semibold tabular-nums" data-testid="market-top-total">
            {formatNumber(item.totalScore)}
          </span>
          <span className="text-xs text-muted-foreground">
            总分（合成后 <span className="tabular-nums" data-testid="market-top-final">{formatNumber(item.finalScore)}</span>）
          </span>
          <span className="text-xs text-muted-foreground" data-testid="market-top-evidence">
            依据事件 {item.evidenceCount} 条
          </span>
          <span className="text-[10px] text-muted-foreground">计算于 {formatDateTime(item.computedAt)}</span>
        </div>

        {item.factors.length > 0 ? <FactorMiniBars factors={item.factors} /> : null}
        <DiveArea item={item} />

        <div className="flex flex-wrap items-center gap-2 border-t border-border pt-2">
          <AddWatchlistButton
            item={item}
            inWatchlist={inWatchlist}
            adding={adding}
            error={watchError}
            onAdd={onAdd}
          />
        </div>
      </CardContent>
    </Card>
  );
}

/** 漏斗徽章链（全量 → 粗筛池 → 深析候选 → Top10；title 带全程计数）。 */
function FunnelChain({ view }: { view: MarketTopRankView }) {
  const stats = view.batch.funnelStats;
  const steps: Array<{ key: string; label: string; value: number | undefined }> = [
    { key: 'snapshotRows', label: '全量快照', value: stats.snapshotRows },
    { key: 'poolSize', label: '粗筛池', value: stats.poolSize },
    { key: 'divePlanned', label: '深析候选', value: stats.divePlanned },
    { key: 'topSize', label: 'Top10', value: stats.topSize },
  ];
  const title =
    `合格 ${stats.eligible ?? '--'} · 排除 ST ${stats.excluded?.st ?? '--'}/无信号 ${stats.excluded?.noSignal ?? '--'}` +
    ` · 深析完成 ${stats.diveDone ?? '--'} · 模板 ${stats.diveTemplate ?? '--'} · 跳过 ${stats.diveSkipped ?? '--'}`;
  return (
    <div className="flex flex-wrap items-center gap-1.5" data-testid="market-top-funnel" title={title}>
      {steps.map((step, idx) => (
        <span key={step.key} className="flex items-center gap-1.5">
          {idx > 0 ? <span className="text-xs text-muted-foreground">›</span> : null}
          <Badge variant="outline" data-testid={`market-top-funnel-${step.key}`}>
            {step.label} {step.value ?? '--'}
          </Badge>
        </span>
      ))}
    </div>
  );
}

/** 降级横幅（COST_CAP 黄 / LLM_FAILURE 红；未知 reason 兜底中性文案）。 */
function DegradedBanner({ reason }: { reason: string | null }) {
  if (!reason) return null;
  const banner = DEGRADED_BANNERS[reason] ?? {
    text: `深析降级（${reason}），本榜单按因子分排序`,
    className: 'border-amber-500/40 bg-amber-500/10 text-amber-400',
  };
  return (
    <div
      role="status"
      className={cn('rounded-md border px-3 py-2 text-sm', banner.className)}
      data-testid="market-top-degraded"
    >
      {banner.text}
    </div>
  );
}

/** 跌出名单（折叠展示昨日入榜今日出榜的标的）。 */
function DroppedPanel({ dropped }: { dropped: MarketTopDropped[] }) {
  if (dropped.length === 0) return null;
  return (
    <details data-testid="market-top-dropped" className="rounded-md border border-border px-3 py-2">
      <summary className="cursor-pointer text-xs text-muted-foreground">
        本期跌出 Top10（{dropped.length} 只）
      </summary>
      <ul className="mt-2 flex flex-col gap-1">
        {dropped.map((subject) => (
          <li key={subject.code} className="flex items-center gap-2 text-xs">
            <a
              href={`#/subjects/${subject.code}`}
              className="text-primary underline underline-offset-2"
              data-testid={`market-top-dropped-${subject.code}`}
            >
              {subject.name}
            </a>
            <span className="font-mono text-muted-foreground">{subject.code}</span>
            <span className="text-muted-foreground">昨日第 {subject.prevRank} 名</span>
          </li>
        ))}
      </ul>
    </details>
  );
}

/** 页面级状态（榜单视图 + 自选状态；方法论子路由随 T185 增补）。 */
interface PageState {
  view: MarketTopRankView | null;
  versions: MarketTopVersionSummary[];
  selectedDate: string;
  selectedVersion: string;
}

/** 全市场推荐页（第 20 页，「分析」组）。 */
export function MarketTop() {
  const [state, setState] = useState<PageState>({
    view: null,
    versions: [],
    selectedDate: '',
    selectedVersion: '',
  });
  const [loading, setLoading] = useState(true);
  const [empty, setEmpty] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  // 自选状态：首清单 id + 已在任一清单的标的集合 + 页内新增/进行中/失败态
  const [firstWatchlistId, setFirstWatchlistId] = useState<number | null>(null);
  const [watchSubjectIds, setWatchSubjectIds] = useState<Set<number>>(new Set());
  const [addingIds, setAddingIds] = useState<Set<number>>(new Set());
  const [watchErrors, setWatchErrors] = useState<Record<number, string>>({});

  const applyRank = useCallback((data: MarketTopRankView, date: string, version: string) => {
    setState((prev) => ({
      ...prev,
      view: data,
      // 选择器同步到实际命中版本（缺省请求 = 最新有榜单日最大版本）
      selectedDate: date || data.rankDate,
      selectedVersion: version || String(data.version),
    }));
    setEmpty(false);
    setError(null);
    // 曝光埋点（adopt-v1 曝光②先例）：会话内同版本同卡一次 fire-and-forget
    for (const item of data.items) {
      trackReadingOnce(`market-top-view:${data.rankDate}:${data.version}:${item.rankNo}`, {
        contentType: 'MARKET_TOP_VIEW',
        contentRef: `${data.rankDate}:v${data.version}:r${item.rankNo}`,
        subjectCode: item.subjectCode,
      });
    }
  }, []);

  const loadRank = useCallback(
    async (date: string, version: string) => {
      abortRef.current?.abort();
      const ctrl = new AbortController();
      abortRef.current = ctrl;
      try {
        const data = await getMarketTopRank(
          {
            date: date || undefined,
            version: version ? Number(version) : undefined,
          },
          ctrl.signal,
        );
        if (ctrl.signal.aborted) return;
        applyRank(data, date, version);
      } catch (err) {
        if (ctrl.signal.aborted) return;
        if (err instanceof ApiError && err.code === CODE_MARKET_TOP_NOT_FOUND) {
          setEmpty(true);
          setState((prev) => ({ ...prev, view: null }));
          setError(null);
          return;
        }
        setError(messageOf(err, '榜单加载失败'));
      } finally {
        if (!ctrl.signal.aborted) setLoading(false);
      }
    },
    [applyRank],
  );

  useEffect(() => {
    void loadRank('', '');
    // 版本列表 + 自选清单并行预取（选择器数据源 / 加自选幂等态）
    getMarketTopVersions()
      .then((versions) => setState((prev) => ({ ...prev, versions })))
      .catch(() => undefined); // 版本列表失败不阻断榜单（选择器回退当前版本单选项）
    listWatchlists()
      .then((lists) => {
        setFirstWatchlistId(lists.length > 0 ? lists[0].id : null);
        setWatchSubjectIds(subjectIdsOf(lists));
      })
      .catch(() => undefined); // 清单读取失败按未自选处理（加自选时再兜底解析）
    return () => abortRef.current?.abort();
  }, [loadRank]);

  /** 加自选：首清单（无则建「默认清单」）→ 加标的；30011 已在清单按成功处理（幂等红线）。 */
  const handleAddWatchlist = (item: MarketTopItem) => {
    setAddingIds((prev) => new Set(prev).add(item.subjectId));
    setWatchErrors((prev) => ({ ...prev, [item.subjectId]: '' }));
    void (async () => {
      try {
        const targetId = await resolveWatchlistId(firstWatchlistId, setFirstWatchlistId);
        await addWatchlistItem(targetId, item.subjectId);
        setWatchSubjectIds((prev) => new Set(prev).add(item.subjectId));
        setWatchErrors((prev) => ({ ...prev, [item.subjectId]: '' }));
        // 采纳埋点（ACT 与加自选成功同点——沿 M16 先例）
        trackReadingOnce(`market-top-act:${item.subjectCode}`, {
          contentType: 'MARKET_TOP_ACT',
          contentRef: `${state.selectedDate}:v${state.selectedVersion}:r${item.rankNo}`,
          subjectCode: item.subjectCode,
        });
      } catch (err) {
        if (err instanceof ApiError && err.code === CODE_ALREADY_IN_WATCHLIST) {
          setWatchSubjectIds((prev) => new Set(prev).add(item.subjectId));
          return;
        }
        setWatchErrors((prev) => ({
          ...prev,
          [item.subjectId]: messageOf(err, '加自选失败，请重试'),
        }));
      } finally {
        setAddingIds((prev) => {
          const next = new Set(prev);
          next.delete(item.subjectId);
          return next;
        });
      }
    })();
  };

  const handleDateChange = (date: string) => {
    setLoading(true);
    setState((prev) => ({ ...prev, selectedDate: date, selectedVersion: '' }));
    void loadRank(date, '');
  };

  const handleVersionChange = (version: string) => {
    setLoading(true);
    setState((prev) => ({ ...prev, selectedVersion: version }));
    void loadRank(state.selectedDate, version);
  };

  const handleRetry = () => {
    setLoading(true);
    setError(null);
    void loadRank(state.selectedDate || '', state.selectedVersion || '');
  };

  return (
    <main className="mx-auto w-full max-w-4xl p-4 sm:p-6" data-testid="market-top-page">
      <header className="mb-4">
        <h1 className="text-xl font-medium">全市场推荐</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          全市场快照经四层漏斗（粗筛 → LLM 深析 → 合成）产出的每日 Top10；评分每日盘后更新，深析为日频。
        </p>
      </header>

      {state.view ? (
        <div className="mb-3 flex flex-col gap-2" data-testid="market-top-header">
          <div className="flex flex-wrap items-center gap-2">
            <DateSelect value={state.selectedDate} versions={state.versions} view={state.view} onChange={handleDateChange} />
            <VersionSelect
              value={state.selectedVersion}
              versions={state.versions}
              view={state.view}
              selectedDate={state.selectedDate}
              onChange={handleVersionChange}
            />
            <span className="text-xs text-muted-foreground" data-testid="market-top-meta">
              {`计算于 ${formatDateTime(state.view.batch.computedAt)} · 快照日 ${state.view.batch.snapshotDate}` +
                ` · ${TRIGGER_LABELS[state.view.triggerSource] ?? state.view.triggerSource}`}
            </span>
            <span className="ml-auto flex items-center gap-2 text-xs" data-testid="market-top-links">
              <a
                href="#/recommendations"
                data-testid="market-top-link-recommendations"
                className="text-primary underline underline-offset-2"
              >
                个人推荐 → 推荐中心
              </a>
              <a
                href="#/overview"
                data-testid="market-top-link-overview"
                className="text-primary underline underline-offset-2"
              >
                每日推荐 → 概览
              </a>
            </span>
          </div>
          <FunnelChain view={state.view} />
        </div>
      ) : null}

      <div className="mb-3 flex flex-col gap-2">
        {state.view?.batch.degraded ? (
          <DegradedBanner reason={state.view.batch.degradedReason} />
        ) : null}
        {state.view ? <DroppedPanel dropped={state.view.batch.dropped ?? []} /> : null}
      </div>

      {loading ? (
        <div className="flex flex-col gap-3" data-testid="market-top-loading">
          <Skeleton className="h-44 w-full" />
          <Skeleton className="h-44 w-full" />
          <Skeleton className="h-44 w-full" />
        </div>
      ) : empty ? (
        <p className="py-10 text-center text-sm text-muted-foreground" data-testid="market-top-empty">
          {EMPTY_HINT}
        </p>
      ) : error && !state.view ? (
        <div className="flex flex-col items-start gap-2" data-testid="market-top-error">
          <p className="text-sm text-destructive" role="alert">
            {error}
          </p>
          <Button variant="outline" size="sm" onClick={handleRetry} data-testid="market-top-retry">
            重试
          </Button>
        </div>
      ) : state.view && state.view.items.length > 0 ? (
        <div className="flex flex-col gap-3" data-testid="market-top-list">
          {state.view.items.map((item) => (
            <MarketTopCard
              key={item.subjectCode}
              item={item}
              inWatchlist={watchSubjectIds.has(item.subjectId)}
              adding={addingIds.has(item.subjectId)}
              watchError={watchErrors[item.subjectId] || null}
              onAdd={handleAddWatchlist}
            />
          ))}
          {state.view.items.length < 10 ? (
            <p className="text-center text-xs text-muted-foreground" data-testid="market-top-shortfall">
              本期入榜 {state.view.items.length} 只（不足 10——池内合格标的有限或深析降级，如实呈现）
            </p>
          ) : null}
          <p className="pt-2 text-center text-xs text-muted-foreground" data-testid="market-top-disclaimer">
            {state.view.disclaimer}
          </p>
        </div>
      ) : (
        <p className="py-10 text-center text-sm text-muted-foreground" data-testid="market-top-empty-items">
          本期榜单为空。
        </p>
      )}
    </main>
  );
}

/** 任一清单内的标的 id 集（「已自选」判定口径）。 */
function subjectIdsOf(lists: WatchlistView[]): Set<number> {
  const ids = new Set<number>();
  for (const list of lists) {
    for (const item of list.items ?? []) {
      ids.add(item.subjectId);
    }
  }
  return ids;
}

/** 解析加自选目标清单：已有首清单直用；预取失败/无清单时重查一次，仍无则建「默认清单」。 */
async function resolveWatchlistId(
  known: number | null,
  setFirst: (id: number) => void,
): Promise<number> {
  if (known != null) return known;
  const lists = await listWatchlists();
  if (lists.length > 0) {
    setFirst(lists[0].id);
    return lists[0].id;
  }
  const created = await createWatchlist(DEFAULT_WATCHLIST_NAME);
  setFirst(created.id);
  return created.id;
}

/** 日期选择（有榜单日；versions 失败时回退当前 rankDate 单选项）。 */
function DateSelect({
  value,
  versions,
  view,
  onChange,
}: {
  value: string;
  versions: MarketTopVersionSummary[];
  view: MarketTopRankView;
  onChange: (date: string) => void;
}) {
  const dates = [...new Set(versions.map((summary) => summary.rankDate))];
  if (!dates.includes(view.rankDate)) dates.unshift(view.rankDate);
  const selectClass =
    'h-8 rounded-md border bg-background px-2 text-xs text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50';
  return (
    <select
      aria-label="榜单日期"
      data-testid="market-top-date-select"
      value={value}
      onChange={(e) => onChange(e.target.value)}
      className={selectClass}
    >
      {dates.map((date) => (
        <option key={date} value={date}>
          {date}
        </option>
      ))}
    </select>
  );
}

/** 版本选择（选中日的版本降序列表；回退当前版本单选项）。 */
function VersionSelect({
  value,
  versions,
  view,
  selectedDate,
  onChange,
}: {
  value: string;
  versions: MarketTopVersionSummary[];
  view: MarketTopRankView;
  selectedDate: string;
  onChange: (version: string) => void;
}) {
  const date = selectedDate || view.rankDate;
  const options = versions
    .filter((summary) => summary.rankDate === date)
    .map((summary) => String(summary.version));
  if (!options.includes(String(view.version))) options.unshift(String(view.version));
  const selectClass =
    'h-8 rounded-md border bg-background px-2 text-xs text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50';
  return (
    <select
      aria-label="榜单版本"
      data-testid="market-top-version-select"
      value={value}
      onChange={(e) => onChange(e.target.value)}
      className={selectClass}
    >
      {options.map((version) => (
        <option key={version} value={version}>
          {`v${version}${version === String(view.version) ? '（当前）' : ''}`}
        </option>
      ))}
    </select>
  );
}

export default MarketTop;
