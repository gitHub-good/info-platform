import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { ChevronDown, RefreshCw, Settings2 } from 'lucide-react';
import { ApiError } from '@/api/http';
import {
  getIndustryHeatMap,
  getIndustryMainline,
  getIndustryMainlineConfig,
  getIndustryMainlineDetail,
  patchIndustryMainlineConfig,
  recomputeIndustryMainline,
} from '@/api/industryMainline';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Dialog } from '@/components/ui/dialog';
import { EmptyState } from '@/components/ui/EmptyState';
import { Input } from '@/components/ui/input';
import { PageHeader } from '@/components/ui/PageHeader';
import { Skeleton } from '@/components/ui/skeleton';
import {
  formatDateTime,
  formatMoney,
  formatPct,
  heatCellShade,
  heatScaleGradientCss,
  HEAT_SCALE_MAX_PCT,
} from '@/lib/format';
import { cn } from '@/lib/utils';
import type {
  IndustryHeatMapCell,
  IndustryHeatMapView,
  IndustryMainlineConfigView,
  IndustryMainlineDetailView,
  IndustryMainlineView,
  MainlineDim,
  MainlineItem,
  MainlineLeaderCard,
} from '@/types/industryMainline';

// 行业主线页（M27 T245，#/industry-mainline 全站第 18 页——方案 §4.6 + REQ 故事 1/2/3）。
// 四区块：① 行业热力图（31 格等分 CSS Grid，红涨绿跌色深线性 + hover 全信息 + stale 黄标 + 盘中轮询）
// ② 主线行业榜单（Top 3~5 卡：三维迷你条 / 持续性 / 背离标注 / 依据互链）③ 龙头卡（龙一/二/三 展开）
// ④ 页脚数据源标注；热力图与榜单两区块独立三态互不拖垮；重算 + 配置入口沿任务中心 Dialog 先例。

/** 热力图盘中轮询间隔（方案 §4.6：页面节奏 60s ≠ 源采集 30min；测试可缩短加速）。 */
const DEFAULT_REFRESH_MS = 60_000;
/** 快照全空错误码（30093/404——空态由前端 EmptyState 呈现，非错误）。 */
const CODE_HEAT_EMPTY = 30_093;
/** 全库无榜单错误码（30094/404——榜单空态引导）。 */
const CODE_MAINLINE_NOT_FOUND = 30_094;
/** 龙头卡免责兜底常量（后端逐卡携带，历史行缺失时兜底——合规常驻红线）。 */
const LEADER_DISCLAIMER = '关注度排名，非投资建议，不构成买卖依据';

function messageOf(err: unknown, fallback: string): string {
  return err instanceof ApiError ? err.msg : fallback;
}

/** 带符号百分比（A 股惯例红涨绿跌，配色走方向轨）。 */
function signedPct(value: number | null | undefined): string {
  if (value == null || Number.isNaN(value)) return '--';
  return `${value > 0 ? '+' : ''}${formatPct(value)}`;
}

/** 行情源标注（降级时如实标注——方案 §4.6 ④）。 */
const HEAT_SOURCE_LABELS: Record<string, string> = {
  'eastmoney-push2': '东方财富板块聚合',
  'tencent-rank': '腾讯 SW31 直出（主通道降级）',
};

function heatSourceLabel(source: string | null | undefined): string {
  if (!source) return '--';
  return HEAT_SOURCE_LABELS[source] ?? source;
}

/** 背离标注（PRICE_HOT_HEAT_COLD → 黄「价热讯冷」）。 */
const DIVERGENCE_LABELS: Record<string, string> = {
  PRICE_HOT_HEAT_COLD: '价热讯冷',
};

// —— 热力图区块 ——

/** hover 全信息 title（行业/当日/5日/涨跌家数/主力净流入/总市值/领涨股——REQ 故事 1 场景 2）。 */
function heatCellTitle(cell: IndustryHeatMapCell): string {
  const leader = cell.leaderStock
    ? `领涨股 ${cell.leaderStock.name}${cell.leaderStock.pct != null ? ` ${signedPct(cell.leaderStock.pct)}` : ''}`
    : '领涨股 --';
  return [
    cell.industry,
    `当日 ${signedPct(cell.pctDay)}`,
    `5日 ${signedPct(cell.pctD5)}`,
    `涨 ${cell.upCount ?? '--'} 家`,
    `跌 ${cell.downCount ?? '--'} 家`,
    `主力净流入 ${formatMoney(cell.mainNetFlow)}`,
    `总市值 ${formatMoney(cell.totalMv)}`,
    leader,
  ].join(' · ');
}

/** 行业热力图区块（独立三态 + 盘中轮询 + 点击下钻）。 */
function HeatMapSection({ refreshMs, onDrill }: { refreshMs: number; onDrill: (industry: string) => void }) {
  const [view, setView] = useState<IndustryHeatMapView | null>(null);
  const [loading, setLoading] = useState(true);
  const [empty, setEmpty] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  const load = useCallback(async () => {
    abortRef.current?.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    try {
      const data = await getIndustryHeatMap(undefined, ctrl.signal);
      if (ctrl.signal.aborted) return;
      setView(data);
      setEmpty(false);
      setError(null);
    } catch (err) {
      if (ctrl.signal.aborted) return;
      if (err instanceof ApiError && err.code === CODE_HEAT_EMPTY) {
        setEmpty(true); // 全空快照 → 空态引导（非错误）
        setError(null);
        return;
      }
      setError(messageOf(err, '行业热力图加载失败'));
    } finally {
      if (!ctrl.signal.aborted) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
    // 盘中轮询（页面节奏）：页面隐藏跳过，卸载清理
    const timer = window.setInterval(() => {
      if (document.hidden) return;
      void load();
    }, refreshMs);
    return () => {
      window.clearInterval(timer);
      abortRef.current?.abort();
    };
  }, [load, refreshMs]);

  return (
    <section className="flex flex-col gap-2" data-testid="heat-map-section">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-base font-medium">行业热力图</h2>
        {view?.stale ? (
          <Badge
            className="bg-amber-500/15 text-amber-400"
            title="行情源双通道采集失败，展示最近一次成功快照"
            data-testid="heat-stale-badge"
          >
            数据截至 {formatDateTime(view.quoteTime)}
          </Badge>
        ) : null}
        <span className="text-xs text-muted-foreground" data-testid="heat-meta">
          快照 {view?.snapshotDate ?? '--'} · {heatSourceLabel(view?.source)} · 报价{' '}
          {formatDateTime(view?.quoteTime)}
        </span>
        {/* 色阶图例：-5% 绿 → 0 → +5% 红（A 股惯例） */}
        <span className="ml-auto flex items-center gap-1.5 text-xs text-muted-foreground" data-testid="heat-legend">
          -{HEAT_SCALE_MAX_PCT}%
          <span
            className="h-2 w-24 rounded-full border border-border"
            style={{ backgroundImage: heatScaleGradientCss() }}
            aria-hidden="true"
          />
          +{HEAT_SCALE_MAX_PCT}%
        </span>
      </div>

      {loading ? (
        <div className="grid grid-cols-4 gap-1 sm:grid-cols-6 lg:grid-cols-8" data-testid="heat-loading">
          {Array.from({ length: 12 }, (_, i) => (
            <Skeleton key={i} className="h-14" />
          ))}
        </div>
      ) : empty ? (
        <EmptyState
          title="暂无行业行情快照"
          description="行情采集 Job 每交易日多轮采集，首个数据日即出热力图；可在任务中心查看采集轮次。"
          size="compact"
          testId="heat-empty"
        />
      ) : error && !view ? (
        <div className="flex flex-col items-start gap-2" data-testid="heat-error">
          <p className="text-sm text-destructive" role="alert">
            {error}
          </p>
          <Button variant="outline" size="sm" onClick={() => void load()} data-testid="heat-retry">
            重试
          </Button>
        </div>
      ) : (
        <div
          className="grid grid-cols-4 gap-1 sm:grid-cols-6 lg:grid-cols-8"
          data-testid="heat-map-grid"
        >
          {view?.industries.map((cell) => {
            const shade = heatCellShade(cell.pctDay);
            return (
              <button
                key={cell.industry}
                type="button"
                title={heatCellTitle(cell)}
                onClick={() => onDrill(cell.industry)}
                style={{ backgroundColor: shade.backgroundColor, color: shade.color }}
                className="flex h-14 flex-col items-center justify-center gap-0.5 rounded-md px-1 text-center transition-transform hover:scale-[1.03] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
                data-testid={`heat-cell-${cell.industry}`}
              >
                <span className="w-full truncate text-xs font-medium">{cell.industry}</span>
                <span className="text-xs font-semibold tabular-nums">{signedPct(cell.pctDay)}</span>
              </button>
            );
          })}
        </div>
      )}
      {error && view ? (
        <p className="text-xs text-destructive" role="alert">
          {error}
        </p>
      ) : null}
    </section>
  );
}

// —— 龙头卡 ——

/** 单维分数行（score 0~100 迷你条 + 维内名次）。 */
function DimScoreRow({
  testId,
  label,
  score,
  detail,
}: {
  testId: string;
  label: string;
  score: number | null | undefined;
  detail: ReactNode;
}) {
  const width = score != null ? Math.max(0, Math.min(100, score)) : 0;
  return (
    <div className="flex items-center gap-2 text-xs" data-testid={testId}>
      <span className="w-16 shrink-0 text-muted-foreground">{label}</span>
      <span className="h-1.5 w-16 shrink-0 overflow-hidden rounded-full bg-muted">
        <span className="block h-full rounded-full bg-primary/70" style={{ width: `${width}%` }} />
      </span>
      <span className="w-12 shrink-0 text-right tabular-nums">
        {score != null ? score.toFixed(1) : '--'}
      </span>
      <span className="min-w-0 truncate text-muted-foreground">{detail}</span>
    </div>
  );
}

/** 龙头卡（§4.4.3 全量：名次/综合分/三维分解/主力徽章/事件引用/免责常驻）。 */
function LeaderCardView({ leader }: { leader: MainlineLeaderCard }) {
  const dim = leader.dim;
  const attention = leader.attention;
  const unavailable = !attention || attention.state !== 'OK';
  return (
    <div className="flex flex-col gap-1.5 rounded-lg border px-3 py-2" data-testid={`leader-card-${leader.rank}`}>
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <Badge className="bg-violet-500/15 text-violet-400" data-testid={`leader-rank-${leader.rank}`}>
          {leader.rankLabel}
        </Badge>
        <a
          href={`#/subjects/${leader.subjectCode}`}
          className="font-medium underline decoration-border underline-offset-2 hover:text-foreground"
          data-testid={`leader-subject-${leader.rank}`}
          title="跳转标的完整详情"
        >
          {leader.subjectName}
        </a>
        <span className="font-mono text-xs text-muted-foreground">{leader.subjectCode}</span>
        <span className="ml-auto tabular-nums">
          综合分 <span className="font-semibold">{leader.score.toFixed(1)}</span>
        </span>
      </div>
      {dim ? (
        <div className="flex flex-col gap-1">
          <DimScoreRow
            testId={`leader-dim-attention-${leader.rank}`}
            label="资讯关注度"
            score={dim.attention?.score}
            detail={
              dim.attention
                ? `提及 ${dim.attention.mentions} · 事件 ${dim.attention.eventCount}（加权 ${dim.attention.eventWeighted}）`
                : '--'
            }
          />
          <DimScoreRow
            testId={`leader-dim-value-${leader.rank}`}
            label="价值评分"
            score={dim.value?.score}
            detail={dim.value ? `因子总分 ${dim.value.totalScore ?? '--'}（${dim.value.snapshotDate || '--'}）` : '--'}
          />
          <DimScoreRow
            testId={`leader-dim-price-${leader.rank}`}
            label="价格动量"
            score={dim.price?.score}
            detail={
              dim.price
                ? `当日 ${signedPct(dim.price.pctDay)} · 5日 ${signedPct(dim.price.pctD5)}${dim.price.flag ? ` · ${dim.price.flag}` : ''}`
                : '--'
            }
          />
        </div>
      ) : null}
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs" data-testid={`leader-attention-${leader.rank}`}>
        <span className="text-muted-foreground">主力动向：</span>
        {unavailable ? (
          <span className="text-muted-foreground" title="datacenter 龙虎榜/增减持查询失败或未配置——增强件不阻塞榜单">
            暂无数据
          </span>
        ) : (
          <>
            <span className="tabular-nums">
              龙虎榜(30日) {attention.lhb30d ?? 0} 次
              {attention.lhbLatest?.date ? `（最近 ${attention.lhbLatest.date}）` : ''}
            </span>
            <span className="tabular-nums">
              {attention.chgDirection ?? '--'} {attention.chgCount ?? 0} 次
            </span>
          </>
        )}
        {leader.basis && leader.basis.riskEvents > 0 ? (
          <Badge className="bg-amber-500/15 text-amber-400">风险事件 {leader.basis.riskEvents}</Badge>
        ) : null}
      </div>
      {leader.basis && leader.basis.eventIds.length > 0 ? (
        <div className="flex flex-wrap items-center gap-1 text-xs text-muted-foreground">
          <span>事件引用：</span>
          {leader.basis.eventIds.map((eventId) => (
            <a
              key={eventId}
              href={`#/events?focus=${eventId}`}
              className="underline underline-offset-2 hover:text-foreground"
              data-testid={`leader-event-link-${leader.rank}-${eventId}`}
              title="跳转事件流定位该事件"
            >
              #{eventId}
            </a>
          ))}
        </div>
      ) : null}
      <p className="text-[11px] leading-none text-muted-foreground" data-testid={`leader-disclaimer-${leader.rank}`}>
        {leader.disclaimer ?? LEADER_DISCLAIMER}
      </p>
    </div>
  );
}

// —— 榜单区块 ——

/** 榜单卡三维迷你条标签。 */
const ITEM_DIM_META: { key: 'price' | 'heat' | 'event'; label: string }[] = [
  { key: 'price', label: '价格动量' },
  { key: 'heat', label: '资讯热度' },
  { key: 'event', label: '事件密度' },
];

/** 名次徽章色系（Top3 金银铜 / 4+ 灰，MarketTop 先例——非方向轨）。 */
function rankBadgeClass(rankNo: number): string {
  if (rankNo === 1) return 'bg-amber-500/20 text-amber-300';
  if (rankNo === 2) return 'bg-slate-500/20 text-slate-300';
  if (rankNo === 3) return 'bg-orange-500/15 text-orange-400';
  return 'bg-muted text-muted-foreground';
}

/** 单张主线卡（排名/行业/主线分/三维/持续性/热度排名/背离/龙头展开）。 */
function MainlineCard({ item, onDrill }: { item: MainlineItem; onDrill: (industry: string) => void }) {
  const [expanded, setExpanded] = useState(false);
  const leaders = item.leaders ?? [];
  return (
    <Card data-testid={`mainline-card-${item.rankNo}`}>
      <CardContent className="flex flex-col gap-2 p-4">
        <div className="flex flex-wrap items-center gap-2">
          <Badge className={cn('tabular-nums', rankBadgeClass(item.rankNo))} data-testid={`mainline-rank-${item.rankNo}`}>
            {item.rankNo}
          </Badge>
          <button
            type="button"
            onClick={() => onDrill(item.industry)}
            className="text-sm font-medium underline decoration-border underline-offset-2 hover:text-foreground"
            data-testid={`mainline-industry-link-${item.rankNo}`}
            title="下钻该行业详情（行情行 + 板块/成分股 + 龙头）"
          >
            {item.industry}
          </button>
          <span className="text-sm tabular-nums">
            主线分 <span className="text-base font-semibold">{item.mainScore.toFixed(1)}</span>
          </span>
          <Badge variant="secondary" data-testid={`mainline-persistent-${item.rankNo}`}>
            持续 {item.persistentDays} 天
          </Badge>
          {item.heatRank != null ? (
            <Badge
              variant="secondary"
              data-testid={`mainline-heat-rank-${item.rankNo}`}
              title="当前资讯热度榜名次（对照维度）"
            >
              热度第 {item.heatRank}
            </Badge>
          ) : null}
          {item.divergence ? (
            <Badge
              className="bg-amber-500/15 text-amber-400"
              title="价格动量强而资讯热度弱——警惕情绪先行、基本面待确认"
              data-testid={`mainline-divergence-${item.rankNo}`}
            >
              {DIVERGENCE_LABELS[item.divergence] ?? item.divergence}
            </Badge>
          ) : null}
          {leaders.length > 0 ? (
            <Button
              variant="ghost"
              size="sm"
              className="ml-auto h-7 px-2 text-xs"
              aria-expanded={expanded}
              onClick={() => setExpanded((prev) => !prev)}
              data-testid={`mainline-leaders-toggle-${item.rankNo}`}
            >
              龙头 {leaders.length} 只
              <ChevronDown className={cn('size-3.5 transition-transform', expanded && 'rotate-180')} aria-hidden="true" />
            </Button>
          ) : null}
        </div>
        <div className="flex flex-col gap-1">
          {ITEM_DIM_META.map(({ key, label }) => {
            const dim: MainlineDim | null | undefined = item.dimDetail?.[key];
            return (
              <DimScoreRow
                key={key}
                testId={`mainline-dim-${item.rankNo}-${key}`}
                label={label}
                score={dim?.score}
                detail={dim ? `第 ${dim.rank} 名${dim.raw != null ? ` · 原始 ${dim.raw}` : ''}` : '--'}
              />
            );
          })}
        </div>
        {expanded ? (
          <div className="flex flex-col gap-2" data-testid={`mainline-leaders-${item.rankNo}`}>
            {leaders.map((leader) => (
              <LeaderCardView key={`${leader.rank}-${leader.subjectCode}`} leader={leader} />
            ))}
          </div>
        ) : null}
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
          <a
            href="#/industry-heat"
            className="underline underline-offset-2 hover:text-foreground"
            data-testid={`mainline-link-heat-${item.rankNo}`}
            title="跳转行业热度榜（资讯热度维度对照）"
          >
            热度榜对照
          </a>
          <span className="min-w-0 truncate" title={item.basis ?? undefined}>
            口径 {item.basis ?? '--'}
          </span>
        </div>
      </CardContent>
    </Card>
  );
}

/** 榜单区块（独立三态：加载 / 空态 30094 / 错误重试；reloadSignal 随重算触发重拉）。 */
function MainlineSection({
  reloadSignal,
  onDrill,
}: {
  reloadSignal: number;
  onDrill: (industry: string) => void;
}) {
  const [view, setView] = useState<IndustryMainlineView | null>(null);
  const [loading, setLoading] = useState(true);
  const [empty, setEmpty] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  const load = useCallback(async () => {
    abortRef.current?.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    try {
      const data = await getIndustryMainline({}, ctrl.signal);
      if (ctrl.signal.aborted) return;
      setView(data);
      setEmpty(false);
      setError(null);
    } catch (err) {
      if (ctrl.signal.aborted) return;
      if (err instanceof ApiError && err.code === CODE_MAINLINE_NOT_FOUND) {
        setEmpty(true);
        setError(null);
        return;
      }
      setError(messageOf(err, '主线榜单加载失败'));
    } finally {
      if (!ctrl.signal.aborted) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
    return () => abortRef.current?.abort();
  }, [load]);

  // 重算受理后重拉（reloadSignal 单调递增；首轮挂载 signal=0 跳过避免双拉）
  useEffect(() => {
    if (reloadSignal > 0) void load();
  }, [reloadSignal, load]);

  return (
    <section className="flex flex-col gap-2" data-testid="mainline-section">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-base font-medium">主线行业榜单</h2>
        {view?.degraded ? (
          <Badge
            className="bg-amber-500/15 text-amber-400"
            title="榜单计算存在缺维降级（如冷启动无 5 日行情）——该维按中性 50 计"
            data-testid="mainline-degraded"
          >
            降级 · {view.degradedReason ?? '--'}
          </Badge>
        ) : null}
        <span className="text-xs text-muted-foreground" data-testid="mainline-meta">
          榜单日 {view?.rankDate ?? '--'} · v{view?.version ?? '--'} ·{' '}
          {view?.triggerSource === 'MANUAL' ? '手动重算' : '盘后定时'} · 计算{' '}
          {formatDateTime(view?.computedAt)}
        </span>
      </div>

      {loading ? (
        <div className="flex flex-col gap-3" data-testid="mainline-loading">
          <Skeleton className="h-28 w-full" />
          <Skeleton className="h-28 w-full" />
        </div>
      ) : empty ? (
        <EmptyState
          title="暂无主线榜单"
          description="主线榜单每交易日 18:30 盘后计算（行情 × 热度 × 事件三维）；可点右上「重算」手动触发首榜。"
          size="compact"
          testId="mainline-empty"
        />
      ) : error && !view ? (
        <div className="flex flex-col items-start gap-2" data-testid="mainline-error">
          <p className="text-sm text-destructive" role="alert">
            {error}
          </p>
          <Button variant="outline" size="sm" onClick={() => void load()} data-testid="mainline-retry">
            重试
          </Button>
        </div>
      ) : (
        <div className="flex flex-col gap-3" data-testid="mainline-list">
          {view?.items.map((item) => (
            <MainlineCard key={item.industry} item={item} onDrill={onDrill} />
          ))}
        </div>
      )}
    </section>
  );
}

// —— 行业下钻 Dialog ——

/** 行情行（热力图格 / 下钻共用口径）。 */
function QuoteRow({ cell }: { cell: IndustryMainlineDetailView }) {
  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm" data-testid="detail-quote">
      <span className="tabular-nums">当日 {signedPct(cell.pctDay)}</span>
      <span className="tabular-nums text-muted-foreground">5日 {signedPct(cell.pctD5)}</span>
      <span className="text-xs text-muted-foreground">
        涨 {cell.upCount ?? '--'} · 跌 {cell.downCount ?? '--'}
      </span>
      <span className="text-xs text-muted-foreground">主力净流入 {formatMoney(cell.mainNetFlow)}</span>
      <span className="text-xs text-muted-foreground">总市值 {formatMoney(cell.totalMv)}</span>
      {cell.leaderStock ? (
        <span className="text-xs">
          领涨股 {cell.leaderStock.name}
          {cell.leaderStock.pct != null ? ` ${signedPct(cell.leaderStock.pct)}` : ''}
        </span>
      ) : null}
    </div>
  );
}

/** 行业下钻 Dialog（行情行 + 通道 A 板块列表 / 通道 B 成分股 + 龙头完整信息 + 成员统计）。 */
function IndustryDetailDialog({ industry, onClose }: { industry: string; onClose: () => void }) {
  const [detail, setDetail] = useState<IndustryMainlineDetailView | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setDetail(await getIndustryMainlineDetail(industry));
    } catch (err) {
      setError(messageOf(err, '行业详情加载失败'));
    } finally {
      setLoading(false);
    }
  }, [industry]);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <Dialog
      open
      title={`行业详情 · ${industry}`}
      description="当日行情聚合 + 板块/成分股明细 + 龙头完整信息（数据源随当日快照通道形态）"
      onClose={onClose}
      contentClassName="max-w-2xl"
    >
      <div data-testid="mainline-detail-dialog" className="flex max-h-[70vh] flex-col gap-3 overflow-y-auto">
        {loading ? (
          <div className="flex flex-col gap-2" data-testid="detail-loading">
            <Skeleton className="h-8 w-full" />
            <Skeleton className="h-24 w-full" />
          </div>
        ) : error ? (
          <div className="flex flex-col items-start gap-2" data-testid="detail-error">
            <p className="text-sm text-destructive" role="alert">
              {error}
            </p>
            <Button variant="outline" size="sm" onClick={() => void load()} data-testid="detail-retry">
              重试
            </Button>
          </div>
        ) : detail ? (
          <>
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-sm font-medium" data-testid="detail-industry">
                {detail.industry}
              </span>
              <span className="text-xs text-muted-foreground tabular-nums">
                成员 {detail.memberCount ?? '--'} 只
              </span>
            </div>
            <QuoteRow cell={detail} />
            {detail.stale ? (
              <p className="text-xs text-amber-400">数据截至 {formatDateTime(detail.quoteTime)}（旧快照）</p>
            ) : null}
            {detail.boards && detail.boards.length > 0 ? (
              <div className="flex flex-col gap-1" data-testid="detail-boards">
                <p className="text-xs font-medium text-muted-foreground">板块明细（通道 A · {detail.aggMethod}）</p>
                {detail.boards.map((board) => (
                  <div
                    key={board.boardName}
                    className="flex flex-wrap items-center gap-x-3 gap-y-0.5 rounded-md border px-2 py-1 text-sm"
                    data-testid={`detail-board-${board.boardName}`}
                  >
                    <span className="w-20 shrink-0 truncate font-medium">{board.boardName}</span>
                    <span className="w-16 shrink-0 text-right tabular-nums">{signedPct(board.pctDay)}</span>
                    <span className="text-xs text-muted-foreground">
                      涨 {board.upCount ?? '--'} · 跌 {board.downCount ?? '--'}
                    </span>
                    <span className="text-xs text-muted-foreground">主力 {formatMoney(board.mainNetFlow)}</span>
                  </div>
                ))}
              </div>
            ) : null}
            {detail.constituents && detail.constituents.length > 0 ? (
              <div className="flex flex-col gap-1" data-testid="detail-constituents">
                <p className="text-xs font-medium text-muted-foreground">
                  成分股涨跌（通道 B · {detail.aggMethod}）
                </p>
                <div className="flex flex-wrap gap-1">
                  {detail.constituents.map((stock) => (
                    <Badge key={stock.code} variant="secondary" className="font-mono text-xs tabular-nums">
                      {stock.name} {signedPct(stock.pctChange)}
                    </Badge>
                  ))}
                </div>
              </div>
            ) : null}
            {detail.leaders && detail.leaders.length > 0 ? (
              <div className="flex flex-col gap-2">
                <p className="text-xs font-medium text-muted-foreground">龙头（leader-v1 完整信息）</p>
                {detail.leaders.map((leader) => (
                  <LeaderCardView key={`${leader.rank}-${leader.subjectCode}`} leader={leader} />
                ))}
              </div>
            ) : null}
            <p className="text-xs text-muted-foreground" data-testid="detail-source">
              快照 {detail.snapshotDate ?? '--'} · {heatSourceLabel(detail.source)} · 报价{' '}
              {formatDateTime(detail.quoteTime)}
            </p>
          </>
        ) : null}
      </div>
    </Dialog>
  );
}

// —— 配置 Dialog（三维权重 + TopN + 持续性阈值——任务中心权重 Dialog 先例） ——

/** 可编辑字段元数据（对齐后端 IndustryMainlineConfigValidator 30096 拦截口径）。 */
const CONFIG_WEIGHT_FIELDS: { field: 'wp' | 'wh' | 'we'; label: string }[] = [
  { field: 'wp', label: '价格动量权重' },
  { field: 'wh', label: '资讯热度权重' },
  { field: 'we', label: '事件密度权重' },
];
const CONFIG_INT_FIELDS: {
  field: 'topN' | 'persistMinDays' | 'persistWindowDays';
  label: string;
  min: number;
  max: number;
}[] = [
  { field: 'topN', label: '榜单 Top N', min: 3, max: 5 },
  { field: 'persistMinDays', label: '持续性最小天数', min: 1, max: 5 },
  { field: 'persistWindowDays', label: '持续性窗口天数', min: 3, max: 10 },
];
/** 三维权重和容差（|Σw − 1| ≤ 0.001，对齐后端 requireSum）。 */
const WEIGHT_SUM_TOLERANCE = 0.001;

type ConfigField = (typeof CONFIG_WEIGHT_FIELDS)[number]['field'] | (typeof CONFIG_INT_FIELDS)[number]['field'];

function configErrorOf(values: Record<ConfigField, string>): string | null {
  for (const { field, label } of CONFIG_WEIGHT_FIELDS) {
    const n = Number(values[field]);
    if (!Number.isFinite(n) || n < 0 || n > 1) return `${label}须为 0 ~ 1 的数值`;
  }
  const sum =
    Number(values.wp) + Number(values.wh) + Number(values.we);
  if (Math.abs(sum - 1) > WEIGHT_SUM_TOLERANCE) {
    return `三维权重和须为 1（当前 ${Math.round(sum * 1000) / 1000}）`;
  }
  for (const { field, label, min, max } of CONFIG_INT_FIELDS) {
    const n = Number(values[field]);
    if (!Number.isInteger(n) || n < min || n > max) return `${label}须为 ${min} ~ ${max} 的整数`;
  }
  if (Number(values.persistMinDays) > Number(values.persistWindowDays)) {
    return '持续性最小天数不得大于窗口天数（门槛恒不可达）';
  }
  return null;
}

/** 主线配置 Dialog（GET 预填 → 编辑面六字段 → PATCH 全量替换 + expectedUpdatedAt 防呆）。 */
function MainlineConfigDialog({ onClose }: { onClose: () => void }) {
  const [view, setView] = useState<IndustryMainlineConfigView | null>(null);
  const [values, setValues] = useState<Record<ConfigField, string> | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let cancelled = false;
    getIndustryMainlineConfig()
      .then((data) => {
        if (cancelled) return;
        setView(data);
        setValues({
          wp: String(data.mainline.wp),
          wh: String(data.mainline.wh),
          we: String(data.mainline.we),
          topN: String(data.mainline.topN),
          persistMinDays: String(data.mainline.persistMinDays),
          persistWindowDays: String(data.mainline.persistWindowDays),
        });
      })
      .catch((err: unknown) => {
        if (!cancelled) setLoadError(messageOf(err, '配置加载失败'));
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const handleSave = async () => {
    if (!view || !values) return;
    const invalid = configErrorOf(values);
    if (invalid) {
      setSaveError(invalid);
      return;
    }
    setSaving(true);
    try {
      await patchIndustryMainlineConfig({
        ...view.mainline,
        wp: Number(values.wp),
        wh: Number(values.wh),
        we: Number(values.we),
        topN: Number(values.topN),
        persistMinDays: Number(values.persistMinDays),
        persistWindowDays: Number(values.persistWindowDays),
        wa: view.leader.wa,
        wv: view.leader.wv,
        wq: view.leader.wq,
        mentionDays: view.leader.mentionDays,
        leaderTopN: view.leader.topN,
        qDay: view.leader.qDay,
        qD5: view.leader.qD5,
        expectedUpdatedAt: view.mainline.updatedAt ?? undefined,
      });
      onClose();
    } catch (err) {
      // 30096 字段级 / 30065 并发冲突：Dialog 保持打开、输入保留，就地重试
      setSaveError(messageOf(err, '保存失败，请稍后再试'));
    } finally {
      setSaving(false);
    }
  };

  const error = saveError;

  return (
    <Dialog
      open
      title="主线计算配置"
      description="三维权重 / Top N / 持续性阈值——保存即热生效（下一轮 18:30 盘后计算或手动重算按新参数）。"
      onClose={onClose}
      footer={
        <>
          <Button variant="outline" size="sm" onClick={onClose}>
            取消
          </Button>
          <Button size="sm" disabled={!values || saving} onClick={() => void handleSave()} data-testid="mainline-config-save">
            {saving ? '保存中…' : '保存'}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3" data-testid="mainline-config-dialog">
        {loadError ? (
          <p className="text-sm text-destructive" role="alert">
            {loadError}
          </p>
        ) : !values ? (
          <Skeleton className="h-32 w-full" />
        ) : (
          <>
            {CONFIG_WEIGHT_FIELDS.map(({ field, label }) => (
              <label key={field} className="flex items-center gap-2 text-sm">
                <span className="w-28 shrink-0">{label}</span>
                <Input
                  value={values[field]}
                  onChange={(e) => setValues({ ...values, [field]: e.target.value })}
                  inputMode="decimal"
                  data-testid={`mainline-config-${field}`}
                />
              </label>
            ))}
            {CONFIG_INT_FIELDS.map(({ field, label }) => (
              <label key={field} className="flex items-center gap-2 text-sm">
                <span className="w-28 shrink-0">{label}</span>
                <Input
                  value={values[field]}
                  onChange={(e) => setValues({ ...values, [field]: e.target.value })}
                  inputMode="numeric"
                  data-testid={`mainline-config-${field}`}
                />
              </label>
            ))}
            {error ? (
              <p className="text-xs text-destructive" role="alert" data-testid="mainline-config-error">
                {error}
              </p>
            ) : null}
          </>
        )}
      </div>
    </Dialog>
  );
}

// —— 页面 ——

interface IndustryMainlineProps {
  /** 热力图盘中轮询间隔（默认 60s，方案 §4.6 页面节奏；测试可缩短加速）。 */
  refreshMs?: number;
}

/** 行业主线页（第 18 页，分析组第 8 项：热力图 + 主线榜单 + 龙头 + 下钻 + 重算/配置）。 */
export function IndustryMainline({ refreshMs = DEFAULT_REFRESH_MS }: IndustryMainlineProps = {}) {
  const [drillIndustry, setDrillIndustry] = useState<string | null>(null);
  const [configOpen, setConfigOpen] = useState(false);
  const [reloadSignal, setReloadSignal] = useState(0);
  const [recomputeBusy, setRecomputeBusy] = useState(false);
  const [recomputeDone, setRecomputeDone] = useState<string | null>(null);
  const [recomputeError, setRecomputeError] = useState<string | null>(null);

  const handleRecompute = async () => {
    setRecomputeBusy(true);
    setRecomputeError(null);
    setRecomputeDone(null);
    try {
      const res = await recomputeIndustryMainline();
      setRecomputeDone(`重算完成 · Top ${res.topSize} · ${res.detail}`);
      setReloadSignal((s) => s + 1); // 受理即重拉榜单（后端同步执行）
    } catch (err) {
      setRecomputeError(messageOf(err, '重算请求失败，请稍后再试'));
    } finally {
      setRecomputeBusy(false);
    }
  };

  return (
    <main className="mx-auto w-full max-w-5xl p-4 sm:p-6" data-testid="industry-mainline-page">
      <PageHeader
        title="行业主线"
        subtitle="31 申万行业热力图（红涨绿跌 · 盘中轮询）+ 主线榜单（价格动量 × 资讯热度 × 事件密度）+ 主线内龙头（龙一/二/三）。"
        actions={
          <>
            <Button
              variant="outline"
              size="sm"
              disabled={recomputeBusy}
              onClick={() => void handleRecompute()}
              data-testid="mainline-recompute"
            >
              <RefreshCw className={cn('size-4', recomputeBusy && 'animate-spin')} aria-hidden="true" />
              {recomputeBusy ? '重算中…' : '重算'}
            </Button>
            <Button variant="outline" size="sm" onClick={() => setConfigOpen(true)} data-testid="mainline-config-open">
              <Settings2 className="size-4" aria-hidden="true" />
              配置
            </Button>
          </>
        }
      />

      {recomputeDone ? (
        <p className="mb-3 rounded bg-emerald-500/10 px-3 py-2 text-xs text-emerald-400" data-testid="mainline-recompute-done">
          {recomputeDone}（版本留痕见任务中心）
        </p>
      ) : null}
      {recomputeError ? (
        <p className="mb-3 rounded bg-rose-500/10 px-3 py-2 text-xs text-rose-400" role="alert">
          {recomputeError}
        </p>
      ) : null}

      <div className="flex flex-col gap-6">
        <HeatMapSection refreshMs={refreshMs} onDrill={setDrillIndustry} />
        <MainlineSection reloadSignal={reloadSignal} onDrill={setDrillIndustry} />
      </div>

      {/* 页脚数据源标注（方案 §4.6 ④：行情源如实标注降级，资金面 datacenter） */}
      <p className="mt-6 text-xs text-muted-foreground" data-testid="mainline-footnote">
        行情：东方财富板块聚合 / 腾讯 SW31 直出（降级时如实标注）· 资金面：东方财富 datacenter（龙虎榜 / 增减持）·
        主线与龙头为规则化统计（mainline-v1 / leader-v1），零 LLM，不构成投资建议
      </p>

      {drillIndustry ? (
        <IndustryDetailDialog industry={drillIndustry} onClose={() => setDrillIndustry(null)} />
      ) : null}
      {configOpen ? <MainlineConfigDialog onClose={() => setConfigOpen(false)} /> : null}
    </main>
  );
}

export default IndustryMainline;
