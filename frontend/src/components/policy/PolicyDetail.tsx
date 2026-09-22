import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Card,
  CardAction,
  CardContent,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { TendencyBadge } from './TendencyBadge';
import type { PolicyDetailView } from '@/types/policy';

interface PolicyDetailProps {
  detail: PolicyDetailView | null;
  loading: boolean;
  error: string | null;
  onRetry: () => void;
}

/**
 * 政策详情面板：标题 + 倾向徽章 + 来源/时间/原文 + 摘要 + 关联行业 + 关联自选标的表。
 * 三态：加载（骨架，不裸转圈）/ 错误（重试入口）/ 未选择（引导）/ 详情（完整展示）。
 */
export function PolicyDetail({ detail, loading, error, onRetry }: PolicyDetailProps) {
  // 加载
  if (loading) {
    return (
      <Card data-testid="policy-detail-loading">
        <CardHeader>
          <CardTitle className="text-base">政策详情</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <Skeleton className="h-5 w-3/4" />
          <Skeleton className="h-20 w-full" />
          <Skeleton className="h-8 w-full" />
        </CardContent>
      </Card>
    );
  }

  // 错误
  if (error) {
    return (
      <Card data-testid="policy-detail-error">
        <CardHeader>
          <CardTitle className="text-base">政策详情</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col items-start gap-2">
          <p className="text-sm text-destructive" role="alert">
            {error}
          </p>
          <Button
            variant="outline"
            size="sm"
            onClick={onRetry}
            data-testid="policy-detail-retry"
          >
            重试
          </Button>
        </CardContent>
      </Card>
    );
  }

  // 未选择
  if (!detail) {
    return (
      <Card data-testid="policy-detail-empty">
        <CardContent className="py-10 text-center text-sm text-muted-foreground">
          点击左侧政策条目查看详情与关联自选标的
        </CardContent>
      </Card>
    );
  }

  // 详情 + 关联标的 + 倾向徽章
  const related = detail.relatedSubjects;
  return (
    <Card data-testid="policy-detail">
      <CardHeader>
        <CardTitle className="text-base">{detail.title}</CardTitle>
        <CardAction>
          <TendencyBadge tendency={detail.aiTendency} testId="policy-tendency" />
        </CardAction>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
          {detail.source ? <span>来源：{detail.source}</span> : null}
          {detail.publishedAt ? <span>{detail.publishedAt}</span> : null}
          {detail.sourceUrl ? (
            <a
              href={detail.sourceUrl}
              target="_blank"
              rel="noreferrer"
              className="text-primary underline"
              data-testid="policy-source-url"
            >
              原文
            </a>
          ) : null}
        </div>

        {detail.summary ? (
          <p className="text-sm text-muted-foreground" data-testid="policy-summary">
            {detail.summary}
          </p>
        ) : null}

        {detail.relatedIndustries.length > 0 ? (
          <div className="flex flex-wrap items-center gap-1.5">
            {detail.relatedIndustries.map((ind) => (
              <Badge key={ind} variant="secondary">
                {ind}
              </Badge>
            ))}
          </div>
        ) : null}

        <section data-testid="policy-related-subjects">
          <h3 className="mb-2 text-sm font-medium">关联自选标的</h3>
          {related.length > 0 ? (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>代码</TableHead>
                  <TableHead>名称</TableHead>
                  <TableHead>行业</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {related.map((s) => (
                  <TableRow
                    key={s.subjectCode}
                    data-testid={`policy-related-${s.subjectCode}`}
                  >
                    <TableCell>{s.subjectCode}</TableCell>
                    <TableCell>{s.subjectName}</TableCell>
                    <TableCell>{s.industry}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ) : (
            <p
              className="text-xs text-muted-foreground"
              data-testid="policy-related-empty"
            >
              该政策关联行业下暂无你的自选标的
            </p>
          )}
        </section>
      </CardContent>
    </Card>
  );
}
