import type { ReactNode } from 'react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader } from '@/components/ui/card';
import { cn } from 'cn';

interface StatCardProps {
  /** 卡片标题（如「今日成本水位」）。 */
  title: string;
  /** 副标题（窗口口径标注，如「今日」「近 24h」）。 */
  subtitle?: string;
  /** 主数值（数字/文案节点）。 */
  value: ReactNode;
  /** 数值下方提示行（口径补充/最新条目等）。 */
  hint?: ReactNode;
  /** 右上角状态徽章（预算状态/健康标识，颜色语义带文字）。 */
  badge?: ReactNode;
  /** 水位进度条（0~100；成本水位卡用）。 */
  progressPercent?: number;
  /** hint 下附加区块（如异动卡的数据源异常警示条；体检 P1-4）。 */
  extra?: ReactNode;
  /** 整卡跳转目标 hash 路由（如 '#/cost-report'）；error 态自动失效。 */
  href: string;
  /** 卡级错误文案（非空 = 该卡取数失败，显示错误 + 重试，卡片不可点）。 */
  error?: string | null;
  /** 错误态重试回调（聚合接口整体重拉，其余卡保持既有数据）。 */
  onRetry?: () => void;
  /** 走查与自动化定位（data-testid=stat-card-*）。 */
  testId: string;
}

/**
 * 概览指标卡（T42，UI 方案 §5.2）：Card + 标题/数值/hint/徽章/进度条插槽，整卡可点击可聚焦。
 *
 * - 可点击用 `<a href="#/...">` 包裹（原生键盘可达：Tab 聚焦、Enter 触发），hover border-ring + 焦点环。
 * - 错误态：卡片脱离跳转（避免「按钮嵌链接」非法嵌套），显示 `text-sm text-destructive` 文案 +
 *   「重试」outline 按钮（UI 方案 §3.1 error 态「单卡失败」分支）。
 */
export function StatCard({
  title,
  subtitle,
  value,
  hint,
  badge,
  progressPercent,
  extra,
  href,
  error,
  onRetry,
  testId,
}: StatCardProps) {
  const clickable = !error;
  const cardClass = cn(
    'transition-colors',
    clickable && 'cursor-pointer hover:border-ring hover:ring-1 hover:ring-ring/50',
  );
  const cardBody = (
    <Card size="sm" className={cardClass} data-testid={`stat-card-${testId}`}>
      <CardHeader>
        <div className="flex w-full items-center justify-between gap-2">
          <div className="text-xs text-muted-foreground">
            {title}
            {subtitle ? <span className="ml-1 opacity-70">（{subtitle}）</span> : null}
          </div>
          {badge}
        </div>
      </CardHeader>
      <CardContent>
        {error ? (
          <div className="flex flex-col items-start gap-2">
            <p className="text-sm text-destructive" role="alert">
              {error}
            </p>
            <Button
              variant="outline"
              size="sm"
              onClick={onRetry}
              data-testid={`stat-card-${testId}-retry`}
            >
              重试
            </Button>
          </div>
        ) : (
          <>
            <div className="text-xl font-medium tabular-nums">{value}</div>
            {typeof progressPercent === 'number' ? (
              <div
                className="mt-3 h-2 w-full rounded bg-muted"
                role="progressbar"
                aria-valuenow={Math.round(progressPercent)}
                aria-valuemin={0}
                aria-valuemax={100}
                data-testid={`stat-card-${testId}-progress`}
              >
                <div
                  className="h-2 rounded bg-primary transition-all"
                  style={{ width: `${Math.min(100, Math.max(0, progressPercent))}%` }}
                />
              </div>
            ) : null}
            {hint ? (
              <div
                className="mt-2 line-clamp-1 text-xs text-muted-foreground"
                title={typeof hint === 'string' ? hint : undefined}
              >
                {hint}
              </div>
            ) : null}
            {extra ? <div className="mt-2">{extra}</div> : null}
          </>
        )}
      </CardContent>
    </Card>
  );

  if (!clickable) {
    return cardBody;
  }
  return (
    <a
      href={href.startsWith('#') ? href : `#${href}`}
      className="block rounded-xl outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
      data-testid={`stat-card-${testId}-link`}
      aria-label={`${title}，点击查看详情`}
    >
      {cardBody}
    </a>
  );
}
