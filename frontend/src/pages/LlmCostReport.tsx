import { useCallback, useEffect, useRef, useState } from 'react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { ApiError } from '@/api/http';
import { getLlmCostReport } from '@/api/llmCostReport';
import type { LlmBudgetStatus, LlmCostReport, LlmCostWindow } from '@/types/llmCostReport';

/** 非 ApiError 兜底文案。 */
function messageOf(err: unknown, fallback: string): string {
  return err instanceof ApiError ? err.msg : fallback;
}

/** 微元 → 元字符串（4 位小数；0 显示 0.0000 保持对齐）。 */
function formatYuan(costMicros: number): string {
  return (costMicros / 1_000_000).toFixed(4);
}

/** 0~1 比率 → 百分比（两位小数；空窗口 0 显示 —）。 */
function formatPercent(rate: number, denominatorCalls: number): string {
  if (denominatorCalls === 0) return '—';
  return `${(rate * 100).toFixed(2)}%`;
}

/** token 数千分位可读化。 */
function formatTokens(tokens: number): string {
  return tokens.toLocaleString('zh-CN');
}

/** 时间窗切换项。 */
const WINDOWS: { key: LlmCostWindow; label: string }[] = [
  { key: 'today', label: '今日' },
  { key: '7d', label: '近 7 天' },
  { key: '30d', label: '近 30 天' },
];

/** 场景键 → 中文名（对齐 briefType 语义；未知键原样展示）。 */
const SCENE_LABELS: Record<string, string> = {
  '1': '个股简报',
  '2': '事件归因',
  '3': '政策解读',
  '4': '每日推荐',
};

/** 预算状态徽章：NORMAL 灰 / WARNING 黄 / EXHAUSTED 红。 */
const BUDGET_STATUS_META: Record<LlmBudgetStatus, { label: string; className: string }> = {
  NORMAL: { label: '正常', className: 'bg-muted text-muted-foreground' },
  WARNING: { label: '余量告急', className: 'bg-amber-500/15 text-amber-400' },
  EXHAUSTED: { label: '已耗尽', className: 'bg-rose-500/15 text-rose-400' },
};

interface SummaryCardProps {
  title: string;
  value: string;
  hint?: string;
  testId: string;
}

function SummaryCard({ title, value, hint, testId }: SummaryCardProps) {
  return (
    <Card size="sm" data-testid={testId}>
      <CardHeader>
        <div className="text-xs text-muted-foreground">{title}</div>
      </CardHeader>
      <CardContent>
        <div className="text-xl font-medium tabular-nums">{value}</div>
        {hint ? <div className="mt-1 text-xs text-muted-foreground">{hint}</div> : null}
      </CardContent>
    </Card>
  );
}

interface WindowSwitcherProps {
  value: LlmCostWindow;
  onChange: (window: LlmCostWindow) => void;
  disabled?: boolean;
}

/** 时间窗切换（按钮组，跟随暗色 token，不引新依赖）。 */
function WindowSwitcher({ value, onChange, disabled }: WindowSwitcherProps) {
  return (
    <div className="flex items-center gap-1" data-testid="cost-window-switcher">
      {WINDOWS.map(({ key, label }) => (
        <Button
          key={key}
          variant={key === value ? 'default' : 'outline'}
          size="sm"
          disabled={disabled}
          onClick={() => onChange(key)}
          data-testid={`cost-window-${key}`}
        >
          {label}
        </Button>
      ))}
    </div>
  );
}

interface BudgetStatusBadgeProps {
  status: LlmBudgetStatus;
}

function BudgetStatusBadge({ status }: BudgetStatusBadgeProps) {
  const meta = BUDGET_STATUS_META[status] ?? {
    label: status,
    className: 'bg-muted text-muted-foreground',
  };
  return (
    <Badge variant="ghost" className={meta.className} data-testid={`budget-status-${status}`}>
      {meta.label}
    </Badge>
  );
}

/**
 * LLM 成本报表页（T30，系统管理区，与 Job 执行日志页同级）。
 * - 汇总卡片：估算总成本（元）/ 调用次数（含成功率）/ 缓存命中率 / token 用量（输入+输出）。
 * - 分布：provider 成本分布表 + 场景成本分布表（成本降序，后端排序）。
 * - 预算：今日各用户 token 用量余量与告警状态（NORMAL/WARNING/EXHAUSTED，阈值后端配置）。
 * - 切换：今日 / 近 7 天 / 近 30 天，切换时中止在途请求防串台。
 * 三态：加载骨架 / 空数据引导 / 错误重试；受保护接口 401 由 http 层统一跳 /login。
 */
export function LlmCostReport() {
  const [window, setWindow] = useState<LlmCostWindow>('7d');
  const [report, setReport] = useState<LlmCostReport | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // 切窗时取消在途请求，避免旧响应覆盖新结果
  const abortRef = useRef<AbortController | null>(null);

  const load = useCallback(async (next: LlmCostWindow) => {
    abortRef.current?.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    setLoading(true);
    setError(null);
    try {
      const data = await getLlmCostReport(next, ctrl.signal);
      if (ctrl.signal.aborted) return;
      setReport(data);
    } catch (err) {
      if (ctrl.signal.aborted) return;
      setError(messageOf(err, '成本报表加载失败'));
    } finally {
      if (!ctrl.signal.aborted) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load('7d');
    return () => abortRef.current?.abort();
  }, [load]);

  const handleWindowChange = (next: LlmCostWindow) => {
    setWindow(next);
    void load(next);
  };

  const empty = report != null && report.totalCalls === 0;

  return (
    <main className="mx-auto w-full max-w-6xl p-4 sm:p-6" data-testid="cost-report-page">
      <header className="mb-4">
        <h1 className="text-xl font-medium">LLM 成本报表</h1>
      </header>

      <div className="mb-4 flex items-center justify-between gap-2">
        <WindowSwitcher value={window} onChange={handleWindowChange} disabled={loading} />
        {report && !loading ? (
          <span className="text-xs text-muted-foreground" data-testid="cost-report-window-start">
            窗口起点 {new Date(report.windowStart).toLocaleString('zh-CN')}
          </span>
        ) : null}
      </div>

      <section data-testid="cost-report-section">
        {loading ? (
          <div className="flex flex-col gap-2" data-testid="cost-report-loading">
            <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
              {Array.from({ length: 4 }, (_, i) => (
                <Skeleton key={i} className="h-24 w-full" />
              ))}
            </div>
            <Skeleton className="h-40 w-full" />
          </div>
        ) : error ? (
          <div className="flex flex-col items-start gap-2" data-testid="cost-report-error">
            <p className="text-sm text-destructive" role="alert">
              {error}
            </p>
            <Button
              variant="outline"
              size="sm"
              onClick={() => void load(window)}
              data-testid="cost-report-retry"
            >
              重试
            </Button>
          </div>
        ) : empty ? (
          <div
            className="py-10 text-center text-sm text-muted-foreground"
            data-testid="cost-report-empty"
          >
            所选时间窗内暂无 LLM 调用记录
          </div>
        ) : report ? (
          <div className="flex flex-col gap-4">
            <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
              <SummaryCard
                title="估算总成本（元）"
                value={`¥${formatYuan(report.costMicros)}`}
                hint="按 provider 单价估算，未配单价计 0"
                testId="cost-summary-cost"
              />
              <SummaryCard
                title={`调用次数（${WINDOWS.find((w) => w.key === report.window)?.label ?? report.window}）`}
                value={formatTokens(report.totalCalls)}
                hint={`成功 ${report.successCalls} · 失败 ${report.failedCalls} · 预算拒绝 ${report.rejectedCalls}`}
                testId="cost-summary-calls"
              />
              <SummaryCard
                title="成功率 / 缓存命中率"
                value={`${formatPercent(report.successRate, report.successCalls + report.failedCalls)} / ${formatPercent(report.cacheHitRate, report.totalCalls)}`}
                hint="拒绝不计入成功率分母"
                testId="cost-summary-rates"
              />
              <SummaryCard
                title="Token 用量"
                value={formatTokens(report.totalTokens)}
                hint={`输入 ${formatTokens(report.promptTokens)} · 输出 ${formatTokens(report.completionTokens)}`}
                testId="cost-summary-tokens"
              />
            </div>

            <Card size="sm">
              <CardHeader>
                <CardTitle className="text-base">Provider 成本分布</CardTitle>
              </CardHeader>
              <CardContent>
                <Table data-testid="cost-provider-table">
                  <TableHeader>
                    <TableRow>
                      <TableHead>Provider</TableHead>
                      <TableHead className="text-right">调用</TableHead>
                      <TableHead className="text-right">成功/失败</TableHead>
                      <TableHead className="text-right">Token</TableHead>
                      <TableHead className="text-right">成本（元）</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {report.providers.map((p) => (
                      <TableRow key={p.provider} data-testid={`cost-provider-row-${p.provider}`}>
                        <TableCell className="font-medium">{p.provider}</TableCell>
                        <TableCell className="text-right tabular-nums">{p.calls}</TableCell>
                        <TableCell className="text-right tabular-nums">
                          <span>{p.successCalls}</span>
                          {' / '}
                          <span className={p.failedCalls > 0 ? 'text-rose-400' : 'text-muted-foreground'}>
                            {p.failedCalls}
                          </span>
                        </TableCell>
                        <TableCell className="text-right tabular-nums">
                          {formatTokens(p.totalTokens)}
                        </TableCell>
                        <TableCell className="text-right tabular-nums">
                          {formatYuan(p.costMicros)}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </CardContent>
            </Card>

            <Card size="sm">
              <CardHeader>
                <CardTitle className="text-base">场景成本分布</CardTitle>
              </CardHeader>
              <CardContent>
                <Table data-testid="cost-scene-table">
                  <TableHeader>
                    <TableRow>
                      <TableHead>场景</TableHead>
                      <TableHead className="text-right">调用</TableHead>
                      <TableHead className="text-right">Token</TableHead>
                      <TableHead className="text-right">成本（元）</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {report.scenes.map((s) => (
                      <TableRow key={s.scene} data-testid={`cost-scene-row-${s.scene}`}>
                        <TableCell className="font-medium">
                          {SCENE_LABELS[s.scene] ?? `场景 ${s.scene}`}
                        </TableCell>
                        <TableCell className="text-right tabular-nums">{s.calls}</TableCell>
                        <TableCell className="text-right tabular-nums">
                          {formatTokens(s.totalTokens)}
                        </TableCell>
                        <TableCell className="text-right tabular-nums">
                          {formatYuan(s.costMicros)}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </CardContent>
            </Card>

            <Card size="sm">
              <CardHeader>
                <CardTitle className="text-base">
                  今日用户预算（上限 {formatTokens(report.dailyBudgetTokens)} token / 人，告警线{' '}
                  {Math.round(report.budgetWarnRatio * 100)}%）
                </CardTitle>
              </CardHeader>
              <CardContent>
                {report.topUserBudgets.length === 0 ? (
                  <p className="py-4 text-center text-sm text-muted-foreground" data-testid="cost-budget-empty">
                    今日暂无用户用量
                  </p>
                ) : (
                  <Table data-testid="cost-budget-table">
                    <TableHeader>
                      <TableRow>
                        <TableHead>用户</TableHead>
                        <TableHead className="text-right">今日用量</TableHead>
                        <TableHead className="text-right">余量</TableHead>
                        <TableHead>状态</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {report.topUserBudgets.map((u) => (
                        <TableRow key={u.userId} data-testid={`cost-budget-row-${u.userId}`}>
                          <TableCell className="font-medium">{u.userId}</TableCell>
                          <TableCell className="text-right tabular-nums">
                            {formatTokens(u.usedTokens)} / {formatTokens(u.budgetTokens)}
                          </TableCell>
                          <TableCell className="text-right tabular-nums">
                            {formatTokens(u.remainingTokens)}
                          </TableCell>
                          <TableCell>
                            <BudgetStatusBadge status={u.status} />
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                )}
              </CardContent>
            </Card>
          </div>
        ) : null}
      </section>
    </main>
  );
}

export default LlmCostReport;
