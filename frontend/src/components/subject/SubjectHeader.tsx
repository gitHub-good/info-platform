import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardAction, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { navigate } from '@/lib/navigation';
import type { Subject, SubjectMarket } from '@/types/subject-detail';

const MARKET_LABEL: Record<SubjectMarket, string> = {
  A_SHARE: 'A 股',
  HK: '港股',
  INDEX: '指数',
  SECTOR: '板块',
};

interface SubjectHeaderProps {
  subject: Subject;
  /** 数字主键（「AI 简报」带参跳转用；未解析到时退化为无参入口） */
  subjectId: number | null;
}

/**
 * 标的头部卡：名称 + 市场徽章 + AI 简报入口（带数字主键预填）+ 代码/行业元信息。
 * 独立详情页与完整版弹框共用（弹框即终点：与独立页同源同渲染）。
 */
export function SubjectHeader({ subject, subjectId }: SubjectHeaderProps) {
  return (
    <Card data-testid="subject-header">
      <CardHeader>
        <CardTitle className="text-lg">{subject.name}</CardTitle>
        <CardAction>
          <div className="flex items-center gap-2">
            <Badge variant="secondary">{MARKET_LABEL[subject.market] ?? subject.market}</Badge>
            <Button
              variant="outline"
              size="sm"
              // 带数字主键预填 AiBrief（P1 顺带项）；未解析到时退化为无参入口
              onClick={() =>
                navigate(subjectId != null ? `/ai-brief?subjectId=${subjectId}` : '/ai-brief')
              }
              data-testid="subject-goto-ai-brief"
            >
              AI 简报
            </Button>
          </div>
        </CardAction>
      </CardHeader>
      <CardContent>
        <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-sm text-muted-foreground">
          <span>代码：{subject.subjectCode}</span>
          {subject.industry ? <span>行业：{subject.industry}</span> : null}
        </div>
      </CardContent>
    </Card>
  );
}
