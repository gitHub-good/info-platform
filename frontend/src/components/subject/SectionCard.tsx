import type { ReactNode } from 'react';
import { Card, CardAction, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import type { SourceStatus } from '@/types/subject-detail';
import { SourceStatusBadge } from './SourceStatusBadge';

interface SectionCardProps {
  title: string;
  status: SourceStatus;
  /** 单源分区（行情/财务/估值）的来源；列表分区按条目标注，不在此传 */
  source?: string;
  updatedAt?: string;
  children?: ReactNode;
}

/** 非 ok 分区的兜底文案（与徽章标签互补，不阻断其他分区） */
const FALLBACK_TEXT: Record<SourceStatus, string> = {
  ok: '',
  missing: '本分区暂未返回数据',
  failed: '本分区数据获取失败，请稍后重试',
  timeout: '本分区数据源响应超时，请稍后重试',
};

/**
 * 分区卡片外壳：统一渲染标题、sourceStatus 徽章、来源与时间戳，
 * 并在 missing/failed/timeout 时展示兜底（不阻断其他分区）。
 */
export function SectionCard({ title, status, source, updatedAt, children }: SectionCardProps) {
  const isOk = status === 'ok';
  return (
    <Card data-testid="section-card">
      <CardHeader>
        <CardTitle>{title}</CardTitle>
        <CardAction>
          <SourceStatusBadge status={status} />
        </CardAction>
      </CardHeader>
      <CardContent>
        {isOk ? (
          <>
            {source || updatedAt ? (
              <p className="mb-3 text-xs text-muted-foreground" data-testid="section-meta">
                {source ? <span>来源：{source}</span> : null}
                {source && updatedAt ? <span className="mx-2">·</span> : null}
                {updatedAt ? <span>更新于 {updatedAt}</span> : null}
              </p>
            ) : null}
            {children}
          </>
        ) : (
          <div
            className="py-6 text-center text-sm text-muted-foreground"
            data-testid={`fallback-${status}`}
          >
            {FALLBACK_TEXT[status]}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
