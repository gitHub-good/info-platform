import { useCallback, useEffect, useRef, useState } from 'react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { StatCard } from '@/components/overview/StatCard';
import { ApiError } from '@/api/http';
import { getOverview } from '@/api/overview';
import type { OverviewLlmStatus, OverviewView } from '@/types/overview';

/** 非 ApiError 兜底文案。 */
function messageOf(err: unknown, fallback: string): string {
  return err instanceof ApiError ? err.msg : fallback;
}

/** 微元 → 元字符串（4 位小数，对齐成本报表页口径）。 */
function formatYuan(costMicros: number): string {
  return (costMicros / 1_000_000).toFixed(4);
}

/** token 数千分位可读化。 */
function formatTokens(tokens: number): string {
  return tokens.toLocaleString('zh-CN');
}

/** 用量占比（0~100 整数；预算为 0 防除零给 0）。 */
function percentOf(used: number, budget: number): number {
  if (budget <= 0) return 0;
  return Math.min(100, Math.round((used / budget) * 100));
}

/** 预算状态徽章：OK 灰 / WARNING 黄 / EXHAUSTED 红（三态带文字，对齐成本报表惯例）。 */
const LLM_STATUS_META: Record<OverviewLlmStatus, { label: string; className: string }> = {
  OK: { label: '正常', className: 'bg-muted text-muted-foreground' },
  WARNING: { label: '余量告急', className: 'bg-amber-500/15 text-amber-400' },
  EXHAUSTED: { label: '已耗尽', className: 'bg-rose-500/15 text-rose-400' },
};

/** 源编码 → 中文名（对齐后端 SOURCE_LABELS；未知名原样展示）。 */
const SOURCE_LABELS: Record<string, string> = {
  QUOTE: '行情源',
  FINANCE: '财务源',
  VALUATION: '估值源',
  ANNOUNCE: '公告源',
  NEWS: '新闻源',
  POLICY: '政策源',
  EVENT: '事件源',
};

function sourceLabel(code: string): string {
  return SOURCE_LABELS[code] ?? code;
}

/** 最近事件时间 → 本地可读短格式（无记录返回空串）。 */
function formatTime(iso: string | null): string {
  if (!iso) return '';
  return new Date(iso).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/**
 * 概览仪表盘页（T42，UI 方案 §3.1，登录后默认落地页）。
 * - 五卡片网格（mobile 1 列 → sm 2 列 → lg 3 列，成本水位卡 lg 占 2 列含进度条）。
 * - 整卡可点跳转：成本→#/cost-report、异动→#/watchlists、政策→#/policies、任务→#/task-center、数据源→#/datasource-config。
 * - 三态：骨架×5 / 空态数值 0 或「—」不报错 / 单卡错误独立重试（卡级 error 字段）+ 整页错误重试。
 * - 窗口口径标注在卡片副标题（异动/成本「今日」日界，政策/任务/数据源「近 24h」滚动，方案 §4.6 有意裁定）。
 */
export function Overview() {
  const [data, setData] = useState<OverviewView | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // 卸载/重挂载时中止在途请求，避免旧响应覆盖新结果
  const abortRef = useRef<AbortController | null>(null);

  const load = useCallback(async () => {
    abortRef.current?.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    setLoading(true);
    setError(null);
    try {
      const view = await getOverview(ctrl.signal);
      if (ctrl.signal.aborted) return;
      setData(view);
    } catch (err) {
      if (ctrl.signal.aborted) return;
      setError(messageOf(err, '概览数据加载失败'));
    } finally {
      if (!ctrl.signal.aborted) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
    return () => abortRef.current?.abort();
  }, [load]);

  // 数据源健康摘要：成功 n/总数 + 最差源提示行（仅异常源；全绿「全部正常」；无任何记录「暂无抓取记录」）
  const sources = data?.sourceHealth ?? [];
  const okCount = sources.filter((s) => s.lastEventType === 'OK').length;
  const worst = sources.find((s) => s.lastEventType !== 'OK' && s.lastEventType !== null);
  const noRecords = sources.length > 0 && sources.every((s) => s.lastEventType === null);
  const sourceHint = noRecords
    ? '暂无抓取记录'
    : worst
      ? `${sourceLabel(worst.sourceCode)} ${formatTime(worst.lastEventAt)} ${worst.lastEventType}${worst.errors24h > 0 ? ` · 24h 异常 ${worst.errors24h} 次` : ''}`
      : '全部正常';

  const llm = data?.llmToday;
  const llmStatus = llm?.status ?? 'OK';
  const llmMeta = LLM_STATUS_META[llmStatus] ?? {
    label: llmStatus,
    className: 'bg-muted text-muted-foreground',
  };

  return (
    <main className="mx-auto w-full max-w-6xl p-4 sm:p-6" data-testid="overview-page">
      <header className="mb-4">
        <h1 className="text-xl font-medium">概览</h1>
        <p className="mt-1 text-sm text-muted-foreground">一屏看清平台健康度与今日动态</p>
      </header>

      {loading ? (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3" data-testid="overview-loading">
          <Skeleton className="h-28 w-full lg:col-span-2" />
          {Array.from({ length: 4 }, (_, i) => (
            <Skeleton key={i} className="h-28 w-full" />
          ))}
        </div>
      ) : error ? (
        <div className="flex flex-col items-start gap-2" data-testid="overview-error">
          <p className="text-sm text-destructive" role="alert">
            {error}
          </p>
          <Button variant="outline" size="sm" onClick={() => void load()} data-testid="overview-retry">
            重试
          </Button>
        </div>
      ) : data ? (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          <StatCard
            title="今日成本水位"
            subtitle="今日"
            value={`¥${formatYuan(llm?.costMicros ?? 0)}`}
            hint={`已用 ${formatTokens(llm?.tokenUsed ?? 0)} / 预算 ${formatTokens(llm?.budgetTokens ?? 0)} token`}
            badge={
              <Badge variant="ghost" className={llmMeta.className} data-testid={`llm-status-${llmStatus}`}>
                {llmMeta.label}
              </Badge>
            }
            progressPercent={percentOf(llm?.tokenUsed ?? 0, llm?.budgetTokens ?? 0)}
            href="#/cost-report"
            error={llm?.error ?? null}
            onRetry={() => void load()}
            testId="llm-today"
          />
          <StatCard
            title="今日异动"
            subtitle="今日"
            value={`${data.anomalyToday.count} 条`}
            href="#/watchlists"
            error={data.anomalyToday.error}
            onRetry={() => void load()}
            testId="anomaly"
          />
          <StatCard
            title="政策动态"
            subtitle="近 24h"
            value={`${data.policy24h.count} 条`}
            hint={
              data.policy24h.latest.length > 0
                ? `最新：${data.policy24h.latest[0].title}`
                : '近 24h 无新入库政策'
            }
            href="#/policies"
            error={data.policy24h.error}
            onRetry={() => void load()}
            testId="policy"
          />
          <StatCard
            title="任务健康"
            subtitle="近 24h"
            value={`失败 ${data.jobHealth.windowFailed} 次`}
            hint={
              data.jobHealth.unhealthyJobs.length > 0
                ? `涉及 ${data.jobHealth.unhealthyJobs.join('、')}`
                : `执行 ${formatTokens(data.jobHealth.windowRuns)} 次，无失败任务`
            }
            badge={
              data.jobHealth.windowFailed > 0 ? (
                <Badge variant="ghost" className="bg-rose-500/15 text-rose-400" data-testid="job-health-failed">
                  {data.jobHealth.unhealthyJobs.length} 任务异常
                </Badge>
              ) : (
                <Badge variant="ghost" className="bg-emerald-500/15 text-emerald-400" data-testid="job-health-ok">
                  健康
                </Badge>
              )
            }
            href="#/task-center"
            error={data.jobHealth.error}
            onRetry={() => void load()}
            testId="job-health"
          />
          <StatCard
            title="数据源健康"
            subtitle="近 24h"
            value={`抓取成功 ${okCount}/${sources.length || '—'}`}
            hint={sourceHint}
            href="#/datasource-config"
            error={data.sourceHealthError}
            onRetry={() => void load()}
            testId="source-health"
          />
        </div>
      ) : null}
    </main>
  );
}

export default Overview;
