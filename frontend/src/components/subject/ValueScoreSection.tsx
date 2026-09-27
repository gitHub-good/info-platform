import { useCallback, useEffect, useRef, useState } from 'react';
import { getSubjectValueScore } from '@/api/valueScore';
import { ApiError } from '@/api/http';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardAction, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { formatDateTime } from '@/lib/format';
import { labelOf, DIRECTION_LABELS } from '@/types/industryHeat';
import type { ValueScoreEntry, ValueScoreFactor, ValueScoreView } from '@/types/valueScore';

/** 后端错误码：该标的无任何评分快照（VALUE_SCORE_NOT_FOUND——Job 未跑过/标的不存在）。 */
const CODE_VALUE_SCORE_NOT_FOUND = 30086;

/** 空态引导口径（方案 §4.6：FACTOR_SNAPSHOT CRON 17:30 盘后日频）。 */
const EMPTY_HINT = '评分快照每日盘后 17:30 生成，生成后此处展示总分与五维分解。';

/** 依据事件最多展示条数（明细 cap 10 的展示裁剪，下钻走事件流）。 */
const MAX_ENTRIES = 5;

function messageOf(err: unknown, fallback: string): string {
  return err instanceof ApiError ? err.msg : fallback;
}

/** 「有突破」三阈值从 weight_basis 指纹串回读（bt=60|50|80；解析失败回退缺省——展示层不硬依赖）。 */
function breakthroughThresholds(basis: string): [number, number, number] {
  const match = /bt=(\d+)\|(\d+)\|(\d+)/.exec(basis);
  return match
    ? [Number(match[1]), Number(match[2]), Number(match[3])]
    : [60, 50, 80];
}

function formatNumber(value: number): string {
  return Math.round(value * 10) / 10 === value ? String(value) : value.toFixed(1);
}

/** 五维分解条（名称/分数条/权重；估值维缺数中性或权重 0 时弱化 + 标注态）。 */
function FactorBar({ factor }: { factor: ValueScoreFactor }) {
  const dimmed = factor.neutral || factor.weight === 0;
  return (
    <div
      className={`flex items-center gap-2 ${dimmed ? 'opacity-60' : ''}`}
      data-testid={`value-score-factor-${factor.key}`}
    >
      <span className="w-20 shrink-0 text-xs text-muted-foreground">{factor.name}</span>
      <div className="h-2 flex-1 overflow-hidden rounded bg-muted" data-testid={`value-score-factor-${factor.key}-track`}>
        <div
          className="h-full rounded bg-primary/70"
          style={{ width: `${Math.max(0, Math.min(100, factor.score))}%` }}
        />
      </div>
      <span className="w-10 shrink-0 text-right text-xs tabular-nums">
        {formatNumber(factor.score)}
      </span>
      <span className="w-24 shrink-0 text-right text-xs text-muted-foreground">
        {factor.neutral ? (
          <Badge variant="outline" className="px-1 py-0 text-[10px]" data-testid={`value-score-factor-${factor.key}-neutral`}>
            缺数中性
          </Badge>
        ) : factor.weight === 0 ? (
          <Badge variant="outline" className="px-1 py-0 text-[10px]" data-testid={`value-score-factor-${factor.key}-disabled`}>
            未启用
          </Badge>
        ) : (
          `权重 ${factor.weight.toFixed(2)}`
        )}
      </span>
    </div>
  );
}

/** 依据事件条目（eventId 跳事件流 focus——trace-v1 下钻，事件卡内含原文引用与外链）。 */
function EntryItem({ entry }: { entry: ValueScoreEntry }) {
  return (
    <li className="flex flex-col gap-0.5 border-b border-border pb-2 last:border-0 last:pb-0">
      <div className="flex flex-wrap items-baseline gap-x-2">
        <span className="min-w-0 flex-1 truncate text-xs" title={entry.summary}>
          {entry.summary}
        </span>
        <span className="shrink-0 text-[10px] text-muted-foreground">{entry.eventDate}</span>
        <span className="shrink-0 text-[10px] text-muted-foreground">
          {labelOf(DIRECTION_LABELS, entry.direction)}
        </span>
        <a
          href={`#/events?focus=${entry.eventId}`}
          className="shrink-0 text-xs text-primary underline underline-offset-2"
          data-testid={`value-score-entry-${entry.eventId}`}
        >
          查看事件
        </a>
      </div>
    </li>
  );
}

interface ValueScoreSectionProps {
  /** 数字主键（by-code 解析后；null 时分区静默不渲染——解析失败页面整体已错误态）。 */
  subjectId: number | null;
}

/**
 * 标的详情「价值评分」区块（M20 T173，方案 §4.8——第 8 分区，独立取数零耦合既有分区契约）：
 * 总分大数字 + 全市场百分位徽章 + 五维分解条（估值缺数中性/权重 0 弱化）+ 「有突破」徽章（tooltip 三阈值）
 * + 依据事件下钻（eventId 跳事件流 focus，trace-v1）+ 快照时间与 weightBasis 脚注 + 一行免责常驻。
 * 三态：加载骨架 / 空态（30086——每日盘后 17:30 生成引导）/ 错误重试。
 */
export function ValueScoreSection({ subjectId }: ValueScoreSectionProps) {
  const [view, setView] = useState<ValueScoreView | null>(null);
  const [loading, setLoading] = useState(subjectId != null);
  const [empty, setEmpty] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  /** 取数（全部异步 setState——effect 与重试共用；显式 loading 态由调用方事件处理器置位）。 */
  const load = useCallback((id: number) => {
    abortRef.current?.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    getSubjectValueScore(id, ctrl.signal)
      .then((data) => {
        if (ctrl.signal.aborted) return;
        setView(data);
        setEmpty(false);
        setError(null);
      })
      .catch((err: unknown) => {
        if (ctrl.signal.aborted) return;
        if (err instanceof ApiError && err.code === CODE_VALUE_SCORE_NOT_FOUND) {
          setEmpty(true);
          setView(null);
          setError(null);
          return;
        }
        setError(messageOf(err, '价值评分加载失败'));
      })
      .finally(() => {
        if (!ctrl.signal.aborted) setLoading(false);
      });
  }, []);

  useEffect(() => {
    if (subjectId == null) {
      return;
    }
    // 首挂 loading 初值已 true；subjectId 变更时旧内容保持到新快照到达（stale-while-revalidate）
    load(subjectId);
    return () => abortRef.current?.abort();
  }, [subjectId, load]);

  if (subjectId == null) {
    return null;
  }

  /** 重试（事件处理器置 loading，取数零同步 setState；null 守卫之后定义——闭包内类型已收窄）。 */
  const handleRetry = () => {
    setLoading(true);
    setError(null);
    load(subjectId);
  };

  return (
    <Card data-testid="value-score-section">
      <CardHeader>
        <CardTitle>价值评分</CardTitle>
        {view ? (
          <CardAction>
            <span className="text-xs text-muted-foreground" data-testid="value-score-date">
              {`快照 ${view.snapshotDate}`}
            </span>
          </CardAction>
        ) : null}
      </CardHeader>
      <CardContent>
        {loading ? (
          <div className="flex flex-col gap-2" data-testid="value-score-loading">
            <Skeleton className="h-10 w-40" />
            <Skeleton className="h-2 w-full" />
            <Skeleton className="h-2 w-full" />
          </div>
        ) : empty ? (
          <div className="py-6 text-center text-sm text-muted-foreground" data-testid="value-score-empty">
            {EMPTY_HINT}
          </div>
        ) : error ? (
          <div className="flex flex-col items-start gap-2" data-testid="value-score-error">
            <p className="text-sm text-destructive" role="alert">
              {error}
            </p>
            <Button
              variant="outline"
              size="sm"
              onClick={handleRetry}
              data-testid="value-score-retry"
            >
              重试
            </Button>
          </div>
        ) : view ? (
          <div className="flex flex-col gap-3">
            <div className="flex flex-wrap items-center gap-3">
              <span
                className="text-3xl font-semibold tabular-nums"
                data-testid="value-score-total"
              >
                {formatNumber(view.totalScore)}
              </span>
              {view.breakthrough ? (
                <Badge
                  className="bg-emerald-500/15 font-medium text-emerald-400"
                  data-testid="value-score-breakthrough"
                  title={(() => {
                    const [btCatalyst, btConduction, btRisk] = breakthroughThresholds(view.weightBasis);
                    return `三条件同时满足：事件催化 ≥ ${btCatalyst} · 行业传导 ≥ ${btConduction} · 风险安全 ≥ ${btRisk}`;
                  })()}
                >
                  有突破
                </Badge>
              ) : null}
              <Badge variant="secondary" data-testid="value-score-percentile">
                {`超过全市场 ${view.percentile}%`}
              </Badge>
              <span className="text-xs text-muted-foreground" data-testid="value-score-rank">
                {`第 ${view.rank} 名`}
              </span>
            </div>
            <div className="flex flex-col gap-1.5" data-testid="value-score-factors">
              {view.factors.map((factor) => (
                <FactorBar key={factor.key} factor={factor} />
              ))}
            </div>
            {view.detail?.catalyst?.entries?.length ? (
              <div className="flex flex-col gap-1.5">
                <span className="text-xs font-medium text-muted-foreground">依据事件（事件催化维，可下钻事件流）</span>
                <ul className="flex flex-col gap-2" data-testid="value-score-entries">
                  {view.detail.catalyst.entries.slice(0, MAX_ENTRIES).map((entry) => (
                    <EntryItem key={entry.eventId} entry={entry} />
                  ))}
                </ul>
              </div>
            ) : null}
            <div className="flex flex-col gap-0.5 border-t border-border pt-2">
              <p className="text-[10px] text-muted-foreground" data-testid="value-score-basis">
                {`计算于 ${formatDateTime(view.computedAt)} · 参数指纹 ${view.weightBasis}`}
                {' · '}
                <a
                  href="#/market-top/methodology"
                  data-testid="value-score-link-methodology"
                  className="text-primary underline underline-offset-2"
                >
                  方法论
                </a>
              </p>
              <p className="text-[10px] text-muted-foreground" data-testid="value-score-disclaimer">
                {view.disclaimer}
              </p>
            </div>
          </div>
        ) : null}
      </CardContent>
    </Card>
  );
}

export default ValueScoreSection;
