import { useCallback, useEffect, useRef, useState } from 'react';
import { getFeedDashboard } from '@/api/feedDashboard';
import { getPipelineStatus } from '@/api/pipelineStatus';
import { Card } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';
import { statusTextClass } from '@/lib/format';
import type { FeedDashboardView } from '@/types/feedDashboard';
import type { PipelineStatusView } from '@/types/pipelineStatus';
import type { OverviewLlmStatus } from '@/types/overview';

// 平台健康状态条（M25 T223，V3.0 概览重组 §2）：运维信息降为一行五段状态条——
// 管道 / 源在线 / 今日入库 / 成本水位 / 任务失败，每段可点跳对应运维页；
// 全绿 text-muted-foreground 低存在感，异常段语义色高亮（余量告急→琥珀、失败/熔断→红）。
// - 自管 GET /pipeline/status + GET /feed-dashboard（30 秒刷新 + document.hidden 暂停，继承大盘机制）；
//   成本水位段颜色由概览聚合 llmToday.status 驱动、任务失败段取 jobHealth——经 props 注入同源对账，
//   不二次请求 /overview。
// - 单段数据失败该段显示「—」不拖垮整条（沿 WorkbenchPanel 单块独立降级语义）。

/** 自动刷新间隔（沿大盘 30 秒机制）。 */
const AUTO_REFRESH_MILLIS = 30_000;

/** 概览聚合注入段（llmToday：成本水位段颜色；jobHealth：任务失败段数值）。 */
export interface HealthStatusStripProps {
  /** null = 概览未就绪或取数失败 → 相关段降级「—」。 */
  llmToday?: { status: OverviewLlmStatus; error?: string | null } | null;
  jobHealth?: { failed: number; error?: string | null } | null;
}

/** 块状态速记（pipeline / dashboard 两路独立三态）。 */
interface BlockState<T> {
  data: T | null;
  loading: boolean;
}

const initialBlock = <T,>(): BlockState<T> => ({ data: null, loading: true });

/** 微元预算百分比（预算 0 防除零）。 */
function pctOf(used: number, budget: number): number {
  if (budget <= 0) return 0;
  return Math.min(100, Math.round((used / budget) * 100));
}

/** 管道等级文案（NORMAL 正常 / DEGRADED 降级 / FUSED 熔断）。 */
function pipelineLabel(level: PipelineStatusView['level']): string {
  if (level === 'DEGRADED') return '管道降级';
  if (level === 'FUSED') return '管道熔断';
  return '管道正常';
}

/** 单段语义色（正常 = muted 低存在感；amber 告警 / rose 异常）。 */
type SegmentTone = 'muted' | 'amber' | 'rose';

const TONE_TEXT: Record<SegmentTone, string> = {
  muted: statusTextClass('neutral'),
  amber: statusTextClass('warning'),
  rose: statusTextClass('failure'),
};

const TONE_DOT: Record<SegmentTone, string> = {
  muted: 'bg-muted-foreground/40',
  amber: 'bg-amber-400',
  rose: 'bg-rose-400',
};

/** 状态条单段（圆点 + 文案；整段可点跳对应运维页）。 */
function StripSegment({
  testId,
  href,
  label,
  tone = 'muted',
  title,
}: {
  testId: string;
  href: string;
  label: string;
  tone?: SegmentTone;
  title?: string;
}) {
  return (
    <a
      href={href}
      data-testid={testId}
      title={title}
      className={cn(
        'flex items-center gap-1.5 text-xs transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50',
        TONE_TEXT[tone],
      )}
    >
      <span className={cn('size-1.5 shrink-0 rounded-full', TONE_DOT[tone])} aria-hidden="true" />
      {label}
    </a>
  );
}

/**
 * 平台健康状态条（自管 pipeline/dashboard 两路 + props 注入概览聚合段）。
 * sm/md 断点 flex-wrap 允许换行；首查在途整行 shimmer 占位。
 */
export function HealthStatusStrip({ llmToday = null, jobHealth = null }: HealthStatusStripProps) {
  const [pipeline, setPipeline] = useState<BlockState<PipelineStatusView>>(initialBlock);
  const [dashboard, setDashboard] = useState<BlockState<FeedDashboardView>>(initialBlock);
  const abortRef = useRef<AbortController | null>(null);

  const load = useCallback(async (silent: boolean) => {
    abortRef.current?.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    void (async () => {
      try {
        const data = await getPipelineStatus(ctrl.signal);
        if (!ctrl.signal.aborted) setPipeline({ data, loading: false });
      } catch {
        // 单段降级「—」：静默轮询失败保留旧数据，不拖垮整条
        if (!ctrl.signal.aborted && !silent) setPipeline({ data: null, loading: false });
      }
    })();
    void (async () => {
      try {
        const data = await getFeedDashboard(ctrl.signal);
        if (!ctrl.signal.aborted) setDashboard({ data, loading: false });
      } catch {
        if (!ctrl.signal.aborted && !silent) setDashboard({ data: null, loading: false });
      }
    })();
  }, []);

  useEffect(() => {
    void load(false);
    return () => abortRef.current?.abort();
  }, [load]);

  // 30 秒自动刷新：document.hidden 暂停（继承机制）
  useEffect(() => {
    const timer = window.setInterval(() => {
      if (document.hidden) return;
      void load(true);
    }, AUTO_REFRESH_MILLIS);
    return () => window.clearInterval(timer);
  }, [load]);

  const loading = pipeline.loading || dashboard.loading;
  const pipeData = pipeline.data;
  const dashData = dashboard.data;
  const enabledCount = dashData
    ? dashData.sources.filter((row) => row.enabled && !row.deleted).length
    : 0;

  // 五段语义色裁决：管道 DEGRADED→amber / FUSED→rose；源不在线→amber；成本余量告急→amber（llm WARNING）/
  // 耗尽→rose（EXHAUSTED）；任务失败>0→rose；其余 muted（全绿低存在感）
  const pipelineTone: SegmentTone =
    pipeData == null
      ? 'muted'
      : pipeData.level === 'FUSED'
        ? 'rose'
        : pipeData.level === 'DEGRADED'
          ? 'amber'
          : 'muted';
  const sourcesTone: SegmentTone =
    dashData != null && dashData.global.activeSourceCount < enabledCount ? 'amber' : 'muted';
  const costTone: SegmentTone =
    llmToday == null || llmToday.error != null
      ? 'muted'
      : llmToday.status === 'EXHAUSTED'
        ? 'rose'
        : llmToday.status === 'WARNING'
          ? 'amber'
          : 'muted';
  const jobsTone: SegmentTone =
    jobHealth != null && jobHealth.error == null && jobHealth.failed > 0 ? 'rose' : 'muted';

  if (loading) {
    return (
      <div className="rounded-xl border bg-card px-4 py-2.5" data-testid="health-strip-loading">
        <Skeleton className="h-4 w-full" />
      </div>
    );
  }

  // 单行状态条：sm/md 断点 flex-wrap 允许换行（§2.2）；Card 横排覆写默认 flex-col
  return (
    <Card
      size="sm"
      className="flex-row flex-wrap items-center gap-x-5 gap-y-1.5 px-4 py-2.5"
      data-testid="health-strip"
    >
      <StripSegment
        testId="health-strip-pipeline"
        href="#/feed-dashboard"
        label={pipeData ? pipelineLabel(pipeData.level) : '管道 —'}
        tone={pipelineTone}
      />
      <StripSegment
        testId="health-strip-sources"
        href="#/feed-dashboard"
        label={dashData ? `源在线 ${dashData.global.activeSourceCount}/${enabledCount}` : '源在线 —'}
        tone={sourcesTone}
        title={dashData ? '抓取大盘：源在线/启用数' : undefined}
      />
      <StripSegment
        testId="health-strip-intake"
        href="#/feed-dashboard"
        label={dashData ? `今日入库 ${dashData.global.todayNewCount} 条` : '今日入库 —'}
      />
      <StripSegment
        testId="health-strip-cost"
        href="#/cost-report"
        label={
          pipeData
            ? `成本水位 ${pctOf(pipeData.todayCostMicros, pipeData.budgetMicros)}%${
                llmToday != null && llmToday.error == null && llmToday.status !== 'OK'
                  ? llmToday.status === 'WARNING'
                    ? '（余量告急）'
                    : '（已耗尽）'
                  : ''
              }`
            : '成本水位 —'
        }
        tone={costTone}
        title="LLM 成本与预算水位（成本报表）"
      />
      <StripSegment
        testId="health-strip-jobs"
        href="#/task-center"
        label={
          jobHealth != null && jobHealth.error == null
            ? `任务失败 ${jobHealth.failed}`
            : '任务失败 —'
        }
        tone={jobsTone}
      />
    </Card>
  );
}

export default HealthStatusStrip;
