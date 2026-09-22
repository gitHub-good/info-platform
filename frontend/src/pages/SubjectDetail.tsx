import { useEffect } from 'react';
import { useSubjectDetail } from '@/hooks/useSubjectDetail';
import { trackReadingOnce } from '@/api/readingEvent';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardAction, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { AnnounceSection } from '@/components/subject/AnnounceSection';
import { EventSection } from '@/components/subject/EventSection';
import { FinanceSection } from '@/components/subject/FinanceSection';
import { NewsSection } from '@/components/subject/NewsSection';
import { PolicySection } from '@/components/subject/PolicySection';
import { QuoteSection } from '@/components/subject/QuoteSection';
import { ValuationSection } from '@/components/subject/ValuationSection';
import { navigate, rememberSubject } from '@/lib/navigation';
import type { Subject, SubjectDetailData, SubjectMarket } from '@/types/subject-detail';

const MARKET_LABEL: Record<SubjectMarket, string> = {
  A_SHARE: 'A 股',
  HK: '港股',
  INDEX: '指数',
  SECTOR: '板块',
};

interface SubjectDetailProps {
  /** 标的代码（内部统一代码，如 SH600519） */
  subjectId?: string;
  /** 测试 / 预渲染注入；不传则走 useSubjectDetail（当前 mock，联调日切真实接口） */
  data?: SubjectDetailData;
}

function SubjectHeader({ subject }: { subject: Subject }) {
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
              onClick={() => navigate('/ai-brief')}
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

function LoadingSkeleton() {
  return (
    <div className="flex flex-col gap-4" data-testid="subject-loading">
      <Skeleton className="h-20 w-full" />
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
        {Array.from({ length: 6 }, (_, index) => (
          <Skeleton key={index} className="h-40 w-full" />
        ))}
      </div>
    </div>
  );
}

/**
 * 标的详情聚合页（技术方案 §2 Container 清单 / T10）。
 * 一个视图内按分区展示行情 / 财务 / 估值 / 公告 / 新闻 / 政策 / 事件监控，
 * 每分区按 sourceStatus 三态降级（ok / missing / failed / timeout），
 * 单源缺失不阻断其他分区；每条信息标注数据来源与时间戳。
 */
export function SubjectDetail({ subjectId = 'SH600519', data: injected }: SubjectDetailProps) {
  const { data, loading, error } = useSubjectDetail(subjectId, injected);

  // T38 路由参数化：记录最近浏览标的（侧栏「标的详情」入口指向，无历史落默认标的）
  useEffect(() => {
    rememberSubject(subjectId);
  }, [subjectId]);

  // 阅读埋点（T29）：详情数据加载成功后上报一次（会话级去重、静默失败，不打扰主流程）
  useEffect(() => {
    if (!data) return;
    trackReadingOnce(`subject:${data.subject.subjectCode}`, {
      contentType: 'SUBJECT_DETAIL',
      contentRef: data.subject.subjectCode,
      subjectCode: data.subject.subjectCode,
    });
  }, [data]);

  if (loading) {
    return <LoadingSkeleton />;
  }
  if (error) {
    return (
      <div className="py-10 text-center text-sm text-destructive" data-testid="subject-error">
        加载失败：{error.message}
      </div>
    );
  }
  if (!data) {
    return (
      <div className="py-10 text-center text-sm text-muted-foreground" data-testid="subject-empty">
        暂无该标的数据
      </div>
    );
  }

  const status = data.sourceStatus;
  return (
    <div className="flex flex-col gap-4" data-testid="subject-detail">
      <SubjectHeader subject={data.subject} />
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
        <QuoteSection data={data.quote} status={status.quote} />
        <FinanceSection data={data.finance} status={status.finance} />
        <ValuationSection data={data.valuation} status={status.valuation} />
        <AnnounceSection data={data.announcements} status={status.announce} />
        <NewsSection data={data.news} status={status.news} />
        <PolicySection data={data.policies} status={status.policy} />
        <EventSection data={data.events} status={status.event} />
      </div>
    </div>
  );
}

export default SubjectDetail;
