import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import type { NorthStarStatus, NorthStarView } from '@/types/northStar';

// 北极星区块（M18 T158，REQ 拍板四）：大盘 2.0 顶置「V2.0 北极星」——六指标卡 + 达标徽章 +
// 7 天入库迷你趋势，ns-v1 口径版本串随区块标注。纯展示组件（数据加载/三态归 FeedDashboard 页管）。

/** 达标徽章语义（三态：达标 / 未达标 / 样本不足走首跑校准条款）。 */
const STATUS_META: Record<NorthStarStatus, { label: string; className: string }> = {
  MET: { label: '达标', className: 'bg-emerald-500/15 text-emerald-400' },
  NOT_MET: { label: '未达标', className: 'bg-rose-500/15 text-rose-400' },
  INSUFFICIENT: { label: '样本不足', className: 'bg-amber-500/15 text-amber-400' },
};

/** 感知延迟人读格式：<90s 按秒、其余按分钟（与大盘统计卡同式）。 */
function formatLatency(millis: number | null): string {
  if (millis == null) return '—';
  if (millis < 90_000) return `${Math.max(1, Math.round(millis / 1000))} 秒`;
  return `${Math.round(millis / 60_000)} 分钟`;
}

/** 微元 → 元（4 位小数，对齐成本报表口径）。 */
function formatYuan(costMicros: number): string {
  return (costMicros / 1_000_000).toFixed(4);
}

function StatusBadge({ status, testId }: { status: NorthStarStatus; testId: string }) {
  const meta = STATUS_META[status] ?? STATUS_META.INSUFFICIENT;
  return (
    <Badge className={meta.className} data-testid={testId}>
      {meta.label}
    </Badge>
  );
}

/** 单指标卡（标题 + 主值 + 达标徽章 + 副注；目标线明示防误读）。 */
function MetricCard({
  testId,
  title,
  target,
  value,
  status,
  statusTestId,
  sub,
}: {
  testId: string;
  title: string;
  target: string;
  value: string;
  status: NorthStarStatus;
  statusTestId: string;
  sub: string;
}) {
  return (
    <Card size="sm" data-testid={testId}>
      <CardHeader>
        <CardTitle className="flex items-center justify-between gap-2 text-xs font-normal text-muted-foreground">
          {title}
          <StatusBadge status={status} testId={statusTestId} />
        </CardTitle>
      </CardHeader>
      <CardContent>
        <p className="text-xl font-medium tabular-nums">{value}</p>
        <p className="mt-1 text-xs text-muted-foreground">{sub}</p>
        <p className="mt-1 text-[11px] text-muted-foreground/80">{target}</p>
      </CardContent>
    </Card>
  );
}

/** 7 天入库迷你趋势（纯 div 条形，max 归一化高度；零数据全等高底线）。 */
function IntakeTrend({ points }: { points: { date: string; count: number }[] }) {
  const max = Math.max(1, ...points.map((p) => p.count));
  return (
    <div className="flex h-16 items-end gap-1" data-testid="north-star-trend">
      {points.map((point) => (
        <div
          key={point.date}
          className="flex-1 rounded-sm bg-chart-1/70"
          style={{ height: `${Math.max(6, Math.round((point.count / max) * 100))}%` }}
          title={`${point.date} · ${point.count} 条`}
          data-testid={`north-star-trend-${point.date}`}
          data-count={point.count}
        />
      ))}
    </div>
  );
}

/** 北极星区块（六指标 + 迷你趋势；30 秒刷新沿大盘机制）。 */
export function NorthStarBlock({ view }: { view: NorthStarView }) {
  const { latency, coverage, stableSources, dailyIntake, adoptRate, costGuard } = view;
  return (
    <section className="flex flex-col gap-2" data-testid="north-star-section">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-xs text-muted-foreground">V2.0 北极星</h2>
        <Badge
          className="bg-muted text-muted-foreground"
          data-testid="north-star-basis"
          title="六指标口径版本（ns-v1）：感知延迟增量轮 v1 / 行业覆盖率 L1 日分布 / 稳定源周成功率 / 采纳率 adopt-v1 / 成本 cost-v2"
        >
          {view.basis}
        </Badge>
        <span className="ml-auto text-[11px] text-muted-foreground">
          实时态当日口径 · 验收快照走 V2.0 收口报告
        </span>
      </div>
      <div className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6">
        <MetricCard
          testId="north-star-latency"
          title="感知延迟 P50"
          target="目标 ≤5 分钟（P90 ≤15 分钟参考）"
          value={formatLatency(latency.p50Millis)}
          status={latency.status}
          statusTestId="north-star-latency-status"
          sub={`P90 ${formatLatency(latency.p90Millis)} · 样本 ${latency.sampleCount} 条`}
        />
        <MetricCard
          testId="north-star-coverage"
          title="行业覆盖率"
          target="目标 ≥90%（7 天日序列均值验收）"
          value={
            coverage.coverageRatio == null
              ? '—'
              : `${(coverage.coverageRatio * 100).toFixed(1)}%`
          }
          status={coverage.status}
          statusTestId="north-star-coverage-status"
          sub={`31 行业命中 ${coverage.hitIndustries}/${coverage.totalIndustries} · 当日归类 ${coverage.classifiedToday} 条`}
        />
        <MetricCard
          testId="north-star-stable"
          title="稳定源"
          target="目标 ≥30（7 天成功率 ≥95%）"
          value={String(stableSources.stableCount)}
          status={stableSources.status}
          statusTestId="north-star-stable-status"
          sub={`现役启用 ${stableSources.enabledCount} · ${stableSources.windowDays} 天观察窗`}
        />
        <MetricCard
          testId="north-star-intake"
          title="日净入库"
          target="目标 7 天日均 ≥2000 条"
          value={dailyIntake.avg7d >= 10_000
            ? `${(dailyIntake.avg7d / 10_000).toFixed(1)} 万`
            : String(Math.round(dailyIntake.avg7d))}
          status={dailyIntake.status}
          statusTestId="north-star-intake-status"
          sub={`7 天日均 · 今日 ${dailyIntake.todayNew} 条`}
        />
        <MetricCard
          testId="north-star-adopt"
          title="推荐采纳率"
          target="目标 ≥30%（≥30 张曝光卡样本）"
          value={adoptRate.adoptRate == null ? '—' : `${(adoptRate.adoptRate * 100).toFixed(1)}%`}
          status={adoptRate.status}
          statusTestId="north-star-adopt-status"
          sub={`曝光 ${adoptRate.exposure} · 采纳 ${adoptRate.adopted}（${adoptRate.basis}）`}
        />
        <MetricCard
          testId="north-star-cost"
          title="成本护栏"
          target="两线：日占比 ≤60% · 单条 ≤0.02 元"
          value={`${(costGuard.usageRatio * 100).toFixed(1)}%`}
          status={costGuard.status}
          statusTestId="north-star-cost-status"
          sub={`单条 ¥${
            costGuard.perItemMicros == null ? '—' : formatYuan(costGuard.perItemMicros)
          } · ${costGuard.costBasis}`}
        />
      </div>
      <IntakeTrend points={view.intakeTrend} />
    </section>
  );
}
