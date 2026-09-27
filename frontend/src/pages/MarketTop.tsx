import { useCallback, useEffect, useRef, useState } from 'react';
import type { ReactNode, SyntheticEvent } from 'react';
import { ApiError } from '@/api/http';
import {
  getMarketTopConfig,
  getMarketTopHitStats,
  getMarketTopRank,
  getMarketTopVersions,
} from '@/api/marketTop';
import { trackReadingOnce } from '@/api/readingEvent';
import { getScoreWeights } from '@/api/valueScore';
import { addWatchlistItem, createWatchlist, listWatchlists } from '@/api/watchlist';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { formatDateTime } from '@/lib/format';
import { cn } from '@/lib/utils';
import type {
  MarketTopCitation,
  MarketTopConfigView,
  MarketTopDropped,
  MarketTopFactor,
  MarketTopHitStatsView,
  MarketTopItem,
  MarketTopRankView,
  MarketTopVersionSummary,
} from '@/types/marketTop';
import type { ScoreWeightsView } from '@/types/valueScore';
import type { WatchlistView } from '@/types/watchlist';

// 全市场推荐页（M21 T184/T185，#/market-top 全站第 20 页「分析」组第 7 项——方案 §4.8 + REQ 故事 2/3/4）。
// 榜单卡流 Top10：排名徽章（Top3 金银铜）/标的（跳详情）/总分与合成分/百分位/「有突破」/五维迷你条/
// 深析区（FULL 可展开论点+亮点+风险+引用下钻；FACTOR_ONLY 标注因子分排序）/变动徽章/一键加自选（幂等）；
// 页头日期与版本选择 + 生成信息 + 漏斗徽章链 + 降级横幅 + 跌出名单折叠 + 推荐中心/概览互链 + 免责常驻。
// M22 T192：页头双时间戳（盘后全量重算 + 事件增量重评——晚者在上）与双层口径文案（禁止单一「更新于」混淆口径）。
// 三态：加载骨架 / 空态（30089——每日 18:00 生成引导）/ 错误重试；埋点 MARKET_TOP_VIEW/ACT（adopt-v1 先例）。
// M22 T193：页底「历史表现」折叠区块（hits-v1 信号验证统计——可达/样本标注 N/10/免责三要素，零新增页面）。
// 子路由 #/market-top/methodology（T185，裁决 7 页内 hash 切换导航仍 1 项）：五段式方法论——
// 漏斗图解/五维定义与公式（weights 端点实时读——非硬编码）/合成公式/降级语义/免责与合规。

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

/** 触发来源展示名（triggerSource 值域——EVENT = M22 增量联动版本）。 */
const TRIGGER_LABELS: Record<string, string> = {
  DAILY: '每日定时',
  MANUAL: '手动触发',
  EVENT: '事件驱动',
};

/** 数据延迟双层口径文案（拍板三：全量日频盘后 + 高重要事件分钟级增量——禁止单一「更新于」混淆）。 */
const DUAL_LAYER_LATENCY_COPY = '全量日频盘后更新 + 高重要事件分钟级增量重评，深析为日频。';

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

/** 上次全量重算时刻（当前榜单日的最新 DAILY 版本 computedAt；versions 预取失败且当前版本非 EVENT 时回退当前批）。 */
function fullRecomputeAt(view: MarketTopRankView, versions: MarketTopVersionSummary[]): string | null {
  const daily = versions
    .filter((summary) => summary.rankDate === view.rankDate && summary.triggerSource === 'DAILY')
    .sort((a, b) => b.version - a.version)[0];
  if (daily) return daily.computedAt;
  return view.triggerSource !== 'EVENT' ? view.batch.computedAt : null;
}

/**
 * 页头双时间戳（M22 T192，拍板三：盘后全量 + 事件增量区分文案，晚者在上——增量晚于全量时增量在前）。
 * 增量行 title 携带触发事件摘要清单（hover 下钻入口之一，事件 chip 见 value-score increment 块同款交互）。
 */
function DualTimestamps({ view, versions }: { view: MarketTopRankView; versions: MarketTopVersionSummary[] }) {
  const increment = view.recentIncrement;
  const entries: Array<{ key: string; at: string | null; node: ReactNode }> = [
    {
      key: 'full',
      at: fullRecomputeAt(view, versions),
      node: (
        <span className="text-xs text-muted-foreground" data-testid="market-top-full-at">
          盘后全量重算{' '}
          {fullRecomputeAt(view, versions) ? formatDateTime(fullRecomputeAt(view, versions)!) : '--'}
        </span>
      ),
    },
  ];
  if (increment) {
    const eventTitle = increment.triggerEvents
      .map((event) => `#${event.eventId} ${event.importance ?? ''} ${event.summary ?? ''}`)
      .join('\n');
    entries.push({
      key: 'increment',
      at: increment.computedAt,
      node: (
        <span
          className="text-xs font-medium text-sky-400"
          title={eventTitle || '触发事件留痕已过生命周期窗口'}
          data-testid="market-top-increment-at"
        >
          事件增量重评 v{increment.version} {formatDateTime(increment.computedAt)}
          {increment.triggerEvents.length > 0
            ? `（${increment.triggerEvents[0].summary ?? `事件 #${increment.triggerEvents[0].eventId}`}）`
            : ''}
        </span>
      ),
    });
  }
  entries.sort((a, b) => (b.at ?? '').localeCompare(a.at ?? '')); // 晚者在上（ISO 串字典序即时间序）
  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1" data-testid="market-top-dual-ts">
      {entries.map((entry) => (
        <span key={entry.key}>{entry.node}</span>
      ))}
    </div>
  );
}

/** 页面级状态（榜单视图 + 自选状态；方法论子路由随 T185 增补）。 */
interface PageState {
  view: MarketTopRankView | null;
  versions: MarketTopVersionSummary[];
  selectedDate: string;
  selectedVersion: string;
}

/** 榜单视图（第 20 页主体；方法论子路由视图见 MarketTopMethodology）。 */
function MarketTopRankPage() {
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
          全市场快照经四层漏斗（粗筛 → LLM 深析 → 合成）产出的每日 Top10；{DUAL_LAYER_LATENCY_COPY}
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
                href="#/market-top/methodology"
                data-testid="market-top-link-methodology"
                className="text-primary underline underline-offset-2"
              >
                方法论
              </a>
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
          <DualTimestamps view={state.view} versions={state.versions} />
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
          <HitStatsPanel />
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

// ==================== M22 T193 历史表现折叠区块（hits-v1 信号验证统计——需求故事 5） ====================

/** 占比格式化（0.333 → 33.3%）。 */
function pctOf(value: number): string {
  return `${(value * 100).toFixed(1)}%`;
}

/**
 * 历史表现区块（页底折叠，零新增页面）：三要素——可达（页内折叠一键展开）/ 样本标注（N/10 与样本日计数）/
 * 免责常驻「历史统计不构成收益承诺」。展开首拉（惰性——hits-v1 服务端现算零物化）；INSUFFICIENT 窗显示「样本积累中」。
 */
function HitStatsPanel() {
  const [stats, setStats] = useState<MarketTopHitStatsView | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const loadedRef = useRef(false);
  const abortRef = useRef<AbortController | null>(null);

  const load = useCallback(() => {
    abortRef.current?.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    setLoading(true);
    getMarketTopHitStats(ctrl.signal)
      .then((data) => {
        if (ctrl.signal.aborted) return;
        setStats(data);
        setError(null);
      })
      .catch((err: unknown) => {
        if (ctrl.signal.aborted) return;
        setError(err instanceof ApiError && err.msg ? err.msg : '历史表现加载失败');
      })
      .finally(() => {
        if (!ctrl.signal.aborted) setLoading(false);
      });
  }, []);

  const handleToggle = (event: SyntheticEvent<HTMLDetailsElement>) => {
    if (event.currentTarget.open && !loadedRef.current) {
      loadedRef.current = true; // 展开首拉一次（后续展开用缓存态，刷新随页面重载）
      load();
    }
  };

  const handleRetry = () => load();

  return (
    <details
      data-testid="market-top-hitstats"
      className="mt-3 rounded-md border border-border px-3 py-2"
      onToggle={handleToggle}
    >
      <summary className="cursor-pointer text-xs text-muted-foreground" data-testid="market-top-hitstats-summary">
        历史表现（信号验证统计：T+1 / T+5 / T+20 上涨家数占比与中位涨跌幅）
      </summary>
      <div className="mt-2 flex flex-col gap-2">
        {loading ? (
          <div className="flex flex-col gap-2" data-testid="market-top-hitstats-loading">
            <Skeleton className="h-6 w-64" />
            <Skeleton className="h-16 w-full" />
          </div>
        ) : error ? (
          <div className="flex flex-col items-start gap-2" data-testid="market-top-hitstats-error">
            <p className="text-xs text-destructive" role="alert">
              {error}
            </p>
            <Button variant="outline" size="sm" className="h-6 px-2 text-xs" onClick={handleRetry} data-testid="market-top-hitstats-retry">
              重试
            </Button>
          </div>
        ) : stats ? (
          <div className="flex flex-col gap-2" data-testid="market-top-hitstats-body">
            {stats.windows.map((win) => (
              <div key={win.window} className="flex flex-col gap-1" data-testid={`market-top-hitstats-${win.window}`}>
                <div className="flex flex-wrap items-baseline gap-x-2">
                  <span className="text-xs font-medium">{win.window}</span>
                  {win.agg.status === 'OK' ? (
                    <>
                      <Badge variant="secondary" className="text-[10px]" data-testid={`market-top-hitstats-${win.window}-upratio`}>
                        上涨占比 {pctOf(win.agg.upRatio ?? 0)}
                      </Badge>
                      <Badge variant="outline" className="text-[10px]" data-testid={`market-top-hitstats-${win.window}-median`}>
                        中位涨跌 {(win.agg.medianPct ?? 0).toFixed(2)}%
                      </Badge>
                    </>
                  ) : (
                    <Badge variant="outline" className="text-[10px]" data-testid={`market-top-hitstats-${win.window}-insufficient`}>
                      样本积累中
                    </Badge>
                  )}
                  <span className="text-[10px] text-muted-foreground">
                    样本日 {win.agg.days} 天（{win.agg.status === 'OK' ? '聚合 ≥5 天' : '不足 5 天如实标注'}）
                  </span>
                </div>
                {win.days.length > 0 ? (
                  <ul className="flex flex-col gap-0.5" data-testid={`market-top-hitstats-${win.window}-days`}>
                    {win.days.map((day) => (
                      <li
                        key={day.rankDate}
                        className="flex flex-wrap items-baseline gap-x-2 text-[10px] text-muted-foreground"
                        data-testid={`market-top-hitstats-${win.window}-day-${day.rankDate}`}
                      >
                        <span className="tabular-nums">{day.rankDate}</span>
                        <span>
                          样本 {day.pricedSamples}/{day.topSize}
                          {day.excluded > 0 ? `（剔除 ${day.excluded}：停牌/无价）` : ''}
                        </span>
                        <span className="tabular-nums">上涨 {day.upRatio != null ? pctOf(day.upRatio) : '--'}</span>
                        <span className="tabular-nums">中位 {day.medianPctChg != null ? `${day.medianPctChg.toFixed(2)}%` : '--'}</span>
                      </li>
                    ))}
                  </ul>
                ) : null}
              </div>
            ))}
            <p className="text-[10px] text-muted-foreground" data-testid="market-top-hitstats-basis">
              {`口径 ${stats.basis} · 截至 ${stats.asOf ?? '--'}（停牌/无价样本剔除并标注）`}
            </p>
            <p className="text-[10px] text-muted-foreground" data-testid="market-top-hitstats-disclaimer">
              {stats.disclaimer}
            </p>
          </div>
        ) : null}
      </div>
    </details>
  );
}

// ==================== T185 方法论子路由（#/market-top/methodology，页内 hash 切换） ====================

/** 方法论子路由路径（榜单页头 + 价值评分区块脚注入口）。 */
export const MARKET_TOP_METHODOLOGY_ROUTE = '/market-top/methodology';

/** 当前路由是否方法论子路由（纯读 hash，无查询串干扰）。 */
export function isMethodologyRoute(route: string): boolean {
  return route.split('?')[0] === MARKET_TOP_METHODOLOGY_ROUTE;
}

/** 五维权重键（FACTOR_DEFINITIONS 的 key 值域——排除 basis/updatedAt 等非数值键）。 */
type WeightKey = 'wCatalyst' | 'wConduction' | 'wFundamental' | 'wRisk' | 'wValuation';

/** 五维因子定义（定义与数据源为口径静态文案；权重与阈值恒从 weights 端点实时读——非硬编码红线）。 */
const FACTOR_DEFINITIONS: Array<{ key: WeightKey; name: string; definition: string; dataSource: string }> = [
  {
    key: 'wCatalyst',
    name: '事件催化',
    definition: '催化窗口内关联事件的重要性与方向加权和，按半衰期时间衰减（F1）。',
    dataSource: '事件流（L2 结构化提取）',
  },
  {
    key: 'wConduction',
    name: '行业传导',
    definition: '所属申万一级行业 24h 热度的归一化映射（F2，纯成员关联封顶）。',
    dataSource: '行业热度管道（东财板块成员关系）',
  },
  {
    key: 'wFundamental',
    name: '基本面边际',
    definition: '关联窗口内基本面类事件（业绩/回购等）的边际变化（F3）。',
    dataSource: '事件流 + 财务源',
  },
  {
    key: 'wRisk',
    name: '风险安全',
    definition: '风险事件扣减与 ST 标记的反向分（F4，ST 直接排除出榜单）。',
    dataSource: '事件流 + 标的池 ST 标记',
  },
  {
    key: 'wValuation',
    name: '估值水平',
    definition: 'PE/PB 横截面分位（F5；数据缺失按中性处理，权重可配为 0 停用）。',
    dataSource: '腾讯行情批量（market_daily_snapshot）',
  },
];

/** 方法论页数据（weights + market.top 漏斗配置——两路实时读）。 */
interface MethodologyData {
  weights: ScoreWeightsView;
  config: MarketTopConfigView;
}

/** 方法论页（五段式：漏斗图解 / 因子定义与公式 / 合成口径 / 降级语义 / 免责与合规）。 */
function MarketTopMethodology() {
  const [data, setData] = useState<MethodologyData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  const load = useCallback((signal?: AbortSignal) => {
    Promise.all([getScoreWeights(signal), getMarketTopConfig(signal)])
      .then(([weights, config]) => {
        setData({ weights, config });
        setError(null);
      })
      .catch((err: unknown) => {
        if (signal?.aborted) return;
        setError(messageOf(err, '方法论数据加载失败'));
      })
      .finally(() => {
        if (!signal?.aborted) setLoading(false);
      });
  }, []);

  useEffect(() => {
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    load(ctrl.signal);
    return () => ctrl.abort();
  }, [load]);

  const handleRetry = () => {
    setLoading(true);
    setError(null);
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    load(ctrl.signal);
  };

  return (
    <main className="mx-auto w-full max-w-4xl p-4 sm:p-6" data-testid="market-top-methodology-page">
      <header className="mb-4">
        <h1 className="text-xl font-medium">全市场推荐 · 方法论</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          榜单口径全解：四层漏斗 / 五维因子定义与当前权重（实时读引擎配置）/ 合成公式 / 降级语义。
          <a
            href="#/market-top"
            data-testid="market-top-methodology-back"
            className="ml-1 text-primary underline underline-offset-2"
          >
            返回榜单
          </a>
        </p>
      </header>

      {loading ? (
        <div className="flex flex-col gap-2" data-testid="market-top-methodology-loading">
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-40 w-full" />
        </div>
      ) : error ? (
        <div className="flex flex-col items-start gap-2" data-testid="market-top-methodology-error">
          <p className="text-sm text-destructive" role="alert">
            {error}
          </p>
          <Button
            variant="outline"
            size="sm"
            onClick={handleRetry}
            data-testid="market-top-methodology-retry"
          >
            重试
          </Button>
        </div>
      ) : data ? (
        <div className="flex flex-col gap-3">
          {/* 一 · 四层漏斗图解（层数实时读 market.top 配置——热改即反映） */}
          <Card data-testid="market-top-methodology-funnel">
            <CardContent className="flex flex-col gap-2 p-4">
              <h2 className="text-sm font-medium">四层漏斗</h2>
              <div className="flex flex-wrap items-center gap-1.5">
                <Badge variant="outline">全量快照</Badge>
                <span className="text-xs text-muted-foreground">›</span>
                <Badge variant="outline" data-testid="market-top-methodology-pool">
                  粗筛池 ~{data.config.poolSize}
                </Badge>
                <span className="text-xs text-muted-foreground">›</span>
                <Badge variant="outline" data-testid="market-top-methodology-dive">
                  LLM 深析 {data.config.deepDiveLimit}
                </Badge>
                <span className="text-xs text-muted-foreground">›</span>
                <Badge variant="outline">Top10</Badge>
              </div>
              <p className="text-xs text-muted-foreground">
                全量快照 → 规则排除（ST / 无信号）+ 四键排序切粗筛池（5%~10%）→ 逐股 LLM 深析 → 合成取恰
                10（不足 10 如实标注）；深析成本子预算占比 {(data.config.deepDiveCostCapRatio * 100).toFixed(0)}%
                触顶当日停剩余。
              </p>
            </CardContent>
          </Card>

          {/* 二 · 五维因子定义与公式（权重/窗口/阈值全量实时读 weights 端点） */}
          <Card data-testid="market-top-methodology-factors">
            <CardContent className="flex flex-col gap-2 p-4">
              <h2 className="text-sm font-medium">五维因子与当前权重</h2>
              <div className="flex flex-col gap-1.5">
                {FACTOR_DEFINITIONS.map((factor) => (
                  <p
                    key={factor.key}
                    className="text-xs text-muted-foreground"
                    data-testid={`market-top-methodology-weight-${factor.key}`}
                  >
                    <span className="font-medium text-foreground">{factor.name}</span>
                    （权重 {data.weights[factor.key].toFixed(2)}）：{factor.definition}
                    <span className="block">数据源：{factor.dataSource}</span>
                  </p>
                ))}
              </div>
              <p className="text-xs" data-testid="market-top-methodology-formula">
                总分 = Σ 权重 × 因子分；催化窗口 {data.weights.catalystWindowDays} 天（半衰期{' '}
                {data.weights.halfLifeDays} 天）；「有突破」= 事件催化 ≥ {data.weights.btCatalystMin} ·
                行业传导 ≥ {data.weights.btConductionMin} · 风险安全 ≥ {data.weights.btRiskMin} 三条件同时满足。
              </p>
            </CardContent>
          </Card>

          {/* 三 · 合成口径 */}
          <Card data-testid="market-top-methodology-synthesis">
            <CardContent className="flex flex-col gap-1 p-4">
              <h2 className="text-sm font-medium">合成公式</h2>
              <p className="font-mono text-xs">final = max(总分, 0.8 × 总分 + 0.2 × 深析结构分)</p>
              <p className="text-xs text-muted-foreground">
                深析结构分从五步校验链（解析/结构/引用对账/违禁扫描/兜底）通过后的输出结构派生；未深析标的按因子分排序，与深析标的同序可比。
              </p>
            </CardContent>
          </Card>

          {/* 四 · 降级语义 */}
          <Card data-testid="market-top-methodology-degraded">
            <CardContent className="flex flex-col gap-1 p-4">
              <h2 className="text-sm font-medium">降级语义</h2>
              <ul className="flex list-disc flex-col gap-0.5 pl-4 text-xs text-muted-foreground">
                <li>单次 LLM 失败：该标的切确定性模板兜底，不重试；</li>
                <li>连续 ≥5 次失败：中止剩余深析（LLM_FAILURE），榜单按因子分排序；</li>
                <li>深析成本触顶：当日停剩余深析（COST_CAP），次日自动恢复；管道护栏 DEGRADED/FUSED 同样跳过深析。</li>
              </ul>
              <p className="text-xs text-muted-foreground">
                降级态在榜单页顶部横幅明示，主价值链（因子评分与榜单产出）对深析不设单点依赖。
              </p>
            </CardContent>
          </Card>

          {/* 五 · 免责与合规 + 版本对齐脚注 */}
          <Card data-testid="market-top-methodology-disclaimer">
            <CardContent className="flex flex-col gap-1 p-4">
              <h2 className="text-sm font-medium">免责与合规</h2>
              <p className="text-xs text-muted-foreground">
                本榜单为个人自用的多因子信息整理与 AI 摘要，深析经引用对账与违禁扫描仍可能存在叙述偏差；评分为历史信息整理，不预测未来收益，不构成投资建议。
              </p>
              <p className="text-xs text-muted-foreground" data-testid="market-top-methodology-hits-basis">
                历史表现统计（页底「历史表现」区块）口径 hits-v1：榜单日取当日最大 version 的
                Top10，统计 T+1 / T+5 / T+20 上涨家数占比与中位数涨跌幅（价格源 market_daily_snapshot
                日快照，停牌/无价样本剔除并标注）；样本不足如实呈现「样本积累中」。历史统计不构成收益承诺。
              </p>
              <p className="text-[10px] text-muted-foreground" data-testid="market-top-methodology-basis">
                {`当前引擎参数指纹 ${data.weights.basis}（更新于 ${formatDateTime(data.weights.updatedAt)}）`}
              </p>
            </CardContent>
          </Card>
        </div>
      ) : null}
    </main>
  );
}

/** 全市场推荐页入口（第 20 页，「分析」组）：方法论子路由按 hash 页内切换（导航仍 1 项——裁决 7）。 */
export function MarketTop() {
  const route = useHashRoute();
  if (isMethodologyRoute(route)) {
    return <MarketTopMethodology />;
  }
  return <MarketTopRankPage />;
}

/** 页内 hash 路由（与 App.useHashRoute 同款轻量实现——子路由切换不重挂载外层）。 */
function useHashRoute(): string {
  const [hash, setHash] = useState(() =>
    typeof window === 'undefined' ? '' : window.location.hash,
  );
  useEffect(() => {
    const onChange = () => setHash(window.location.hash);
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);
  return hash.replace(/^#/, '');
}
