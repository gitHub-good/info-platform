import { useCallback, useEffect, useRef, useState } from 'react';
import { ArrowRight } from 'lucide-react';
import { getMarketTopRank } from '@/api/marketTop';
import { ApiError } from '@/api/http';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';
import type { MarketTopItem, MarketTopRankView } from '@/types/marketTop';

// 全市场 Top10 精华卡（M25 T223，V3.0 概览重组 §2）：概览第一屏右位 1/3 宽快览卡。
// - 取数复用 GET /market-top 首查（不传 date/version，与榜单页首查同源同口径——拍板五对账红线），
//   仅取前 5 行；量大再优化列观察项（架构裁决先复用首查）。
// - 三态齐备（§2.4）：骨架 3 行占位 / 错误块内文案 + 重试 / 空态「榜单生成中或样本不足」+ 方法论引导。
// - 30 秒自动刷新 + document.hidden 暂停（继承 WorkbenchPanel 机制，V2.0 工作台拆解吸收）。

/** 自动刷新间隔（沿大盘 30 秒机制）。 */
const AUTO_REFRESH_MILLIS = 30_000;

/** 精华卡取 Top N（拍板五：前 5 名）。 */
const DIGEST_TOP_N = 5;

/** 一位数展示（与榜单页 formatNumber 同口径，对账一致性）。 */
function formatNumber(value: number): string {
  return Math.round(value * 10) / 10 === value ? String(value) : value.toFixed(1);
}

/** 变动徽章配色（与榜单页 CHANGE_BADGES 同系：NEW emerald / UP amber / DOWN rose / SAME 灰）。 */
const CHANGE_TONES: Record<MarketTopItem['changeType'], string> = {
  NEW: 'bg-emerald-500/15 text-emerald-400',
  UP: 'bg-amber-500/15 text-amber-400',
  DOWN: 'bg-rose-500/15 text-rose-400',
  SAME: 'bg-muted text-muted-foreground',
};

/** 变动徽章短文案（精华卡紧凑形态：新 / ↑n / ↓n / 持平；title 带昨日名次对齐榜单页）。 */
function changeLabelOf(item: MarketTopItem): string {
  if (item.changeType === 'NEW' || item.prevRank == null) return '新';
  if (item.changeType === 'UP') return `↑${Math.max(item.prevRank - item.rankNo, 1)}`;
  if (item.changeType === 'DOWN') return `↓${Math.max(item.rankNo - item.prevRank, 1)}`;
  return '持平';
}

function messageOf(err: unknown, fallback: string): string {
  return err instanceof ApiError ? err.msg : fallback;
}

/** Top10 精华快览行（排名 / 名称 / 百分位 / 终分 / 变动徽章；行可点跳完整榜单）。 */
function DigestRow({ item, rank }: { item: MarketTopItem; rank: number }) {
  return (
    <button
      type="button"
      className="flex w-full cursor-pointer items-center gap-2 rounded-sm py-0.5 text-left text-sm transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
      data-testid={`top10-digest-row-${item.subjectCode}`}
      title={`跳转全市场推荐查看「${item.subjectName}」完整榜单行`}
      onClick={() => {
        window.location.hash = '#/market-top';
      }}
    >
      <span className="w-4 shrink-0 text-right text-xs text-muted-foreground tabular-nums">{rank}</span>
      <span className="min-w-0 flex-1 truncate font-medium">{item.subjectName}</span>
      {item.percentile != null ? (
        <span className="shrink-0 text-xs text-muted-foreground tabular-nums">
          超 {formatNumber(item.percentile)}%
        </span>
      ) : null}
      <span className="shrink-0 text-sm tabular-nums">{formatNumber(item.finalScore)}</span>
      <Badge
        variant="ghost"
        className={cn('shrink-0 text-[10px]', CHANGE_TONES[item.changeType] ?? CHANGE_TONES.SAME)}
        title={`昨日第 ${item.prevRank ?? '—'} 名`}
      >
        {changeLabelOf(item)}
      </Badge>
    </button>
  );
}

/**
 * 全市场 Top10 精华卡（自管三态与刷新，页面零数据依赖）。
 * 行级与卡级入口均跳 #/market-top（榜单页承载完整字段面与下钻）。
 */
export function Top10DigestCard() {
  const [view, setView] = useState<MarketTopRankView | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  const load = useCallback(async (silent: boolean) => {
    abortRef.current?.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    if (!silent) {
      setLoading(true);
      setError(null);
    }
    try {
      const data = await getMarketTopRank({}, ctrl.signal);
      if (ctrl.signal.aborted) return;
      // 契约形状防御：items 非数组视为载荷异常（测试桩/网关错报不白屏）
      if (!data || !Array.isArray(data.items)) throw new Error('market-top payload shape mismatch');
      setView(data);
      setError(null);
    } catch (err) {
      if (ctrl.signal.aborted) return;
      // 静默轮询失败：已有数据保留（不闪错误不闪骨架）；仅首查/重试失败进入错误态
      if (!silent) setError(messageOf(err, 'Top10 精华加载失败'));
    } finally {
      if (!ctrl.signal.aborted && !silent) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load(false);
    return () => abortRef.current?.abort();
  }, [load]);

  // 30 秒自动刷新：document.hidden 暂停（继承 WorkbenchPanel 机制）
  useEffect(() => {
    const timer = window.setInterval(() => {
      if (document.hidden) return;
      void load(true);
    }, AUTO_REFRESH_MILLIS);
    return () => window.clearInterval(timer);
  }, [load]);

  const rows = (view?.items ?? []).slice(0, DIGEST_TOP_N);

  return (
    <Card size="sm" className="h-fit" data-testid="top10-digest">
      <CardHeader>
        <CardTitle className="flex items-center justify-between text-sm font-medium">
          全市场 Top10 精华
          <a
            href="#/market-top"
            className="inline-flex items-center gap-0.5 text-xs font-normal text-muted-foreground transition-colors hover:text-foreground"
            data-testid="top10-digest-link"
          >
            查看完整榜单
            <ArrowRight className="size-3" aria-hidden="true" />
          </a>
        </CardTitle>
      </CardHeader>
      <CardContent className="flex min-h-24 flex-col gap-1.5">
        {loading ? (
          <div className="flex flex-col gap-1.5" data-testid="top10-digest-loading">
            <Skeleton className="h-6 w-full" />
            <Skeleton className="h-6 w-full" />
            <Skeleton className="h-6 w-full" />
          </div>
        ) : error && view == null ? (
          <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground" role="status">
            <span data-testid="top10-digest-error">{error}</span>
            <button
              type="button"
              className="text-primary underline underline-offset-4"
              onClick={() => void load(false)}
              data-testid="top10-digest-retry"
            >
              重试
            </button>
          </div>
        ) : rows.length === 0 ? (
          <div
            className="flex flex-col items-center gap-1 py-4 text-center text-sm text-muted-foreground"
            data-testid="top10-digest-empty"
          >
            <span>榜单生成中或样本不足</span>
            <a
              href="#/market-top/methodology"
              className="text-xs text-primary underline underline-offset-4"
              data-testid="top10-digest-empty-methodology"
            >
              了解榜单生成口径
            </a>
          </div>
        ) : (
          <>
            {rows.map((item, index) => (
              <DigestRow key={item.subjectCode} item={item} rank={index + 1} />
            ))}
            <p className="mt-1 text-[10px] text-muted-foreground">每日 18:00 生成 · AI 分析仅供参考</p>
          </>
        )}
      </CardContent>
    </Card>
  );
}

export default Top10DigestCard;
