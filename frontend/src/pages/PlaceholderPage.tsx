import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';

interface PlaceholderPageProps {
  /** 页标题。 */
  title: string;
  /** 一句说明（页头副文案）。 */
  description: string;
  /** 骨架卡数量（默认 3，对位后续真实内容的卡片结构）。 */
  skeletonCards?: number;
  /** testid 前缀（默认 placeholder-page）。 */
  testId?: string;
}

/**
 * 新页占位骨架（T38）：#/overview、#/llm-config、#/datasource-config、#/task-center、#/feed
 * 本批先放「开发中」占位卡，后续任务（T39~T43）逐页替换为真实实现。
 */
export function PlaceholderPage({
  title,
  description,
  skeletonCards = 3,
  testId = 'placeholder-page',
}: PlaceholderPageProps) {
  return (
    <main className="mx-auto w-full max-w-6xl p-4 sm:p-6" data-testid={testId}>
      <header className="mb-4">
        <h1 className="text-xl font-medium">{title}</h1>
        <p className="mt-1 text-sm text-muted-foreground">{description}</p>
      </header>
      <Card>
        <CardContent className="flex flex-col gap-4 py-6">
          <div className="flex items-center gap-2">
            <Badge variant="secondary" data-testid={`${testId}-badge`}>
              开发中
            </Badge>
            <span className="text-sm text-muted-foreground">本页面即将在后续版本提供</span>
          </div>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {Array.from({ length: skeletonCards }, (_, index) => (
              <Skeleton key={index} className="h-28 w-full" />
            ))}
          </div>
        </CardContent>
      </Card>
    </main>
  );
}
