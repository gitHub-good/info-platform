import { Button } from '@/components/ui/button';
import { Dialog } from '@/components/ui/dialog';
import { Skeleton } from '@/components/ui/skeleton';
import { AnnounceSection } from '@/components/subject/AnnounceSection';
import { EventSection } from '@/components/subject/EventSection';
import { FinanceSection } from '@/components/subject/FinanceSection';
import { NewsSection } from '@/components/subject/NewsSection';
import { PolicySection } from '@/components/subject/PolicySection';
import { QuoteSection } from '@/components/subject/QuoteSection';
import { SubjectHeader } from '@/components/subject/SubjectHeader';
import { ValuationSection } from '@/components/subject/ValuationSection';
import { useSubjectDetail } from '@/hooks/useSubjectDetail';

interface SubjectDetailDialogProps {
  /** 受控显隐（打开时才挂载数据主体、才发起取数）。 */
  open: boolean;
  /** 标的代码（内部统一代码，如 SZ000858）；null 时视为关闭（调用方守门）。 */
  subjectCode: string | null;
  onClose: () => void;
}

function DialogSkeleton() {
  return (
    <div className="flex flex-col gap-4" data-testid="subject-dialog-loading">
      <Skeleton className="h-20 w-full" />
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        {Array.from({ length: 6 }, (_, index) => (
          <Skeleton key={index} className="h-36 w-full" />
        ))}
      </div>
    </div>
  );
}

/** 弹框数据主体：仅在打开且有代码时挂载（打开才取数，关闭即卸载停请求）。 */
function SubjectDetailDialogBody({ subjectCode }: { subjectCode: string }) {
  const { data, subjectId: resolvedId, loading, error, retry } = useSubjectDetail(subjectCode);

  if (loading) return <DialogSkeleton />;
  if (error) {
    return (
      <div
        className="flex flex-col items-center gap-3 py-8 text-center"
        data-testid="subject-dialog-error"
      >
        <p className="text-sm text-destructive">详情加载失败，请稍后重试</p>
        <Button variant="outline" size="sm" onClick={retry} data-testid="subject-dialog-retry">
          重试
        </Button>
      </div>
    );
  }
  if (!data) {
    return (
      <div
        className="py-8 text-center text-sm text-muted-foreground"
        data-testid="subject-dialog-empty"
      >
        暂无该标的数据
      </div>
    );
  }

  const status = data.sourceStatus;
  return (
    <div className="flex flex-col gap-4" data-testid="subject-dialog-content">
      <SubjectHeader subject={data.subject} subjectId={resolvedId} />

      {/* 限高滚动：长内容在弹框内消化，页面不动 */}
      <div className="max-h-[65vh] overflow-y-auto pr-1">
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
          <QuoteSection data={data.quote} status={status.quote} />
          <FinanceSection data={data.finance} status={status.finance} />
          <ValuationSection data={data.valuation} status={status.valuation} />
          {/* 四分区「看更多」沿独立页行为（M12 T95）：首屏 sectionPagination 驱动分页条，翻页走分区子端点 */}
          <AnnounceSection
            data={data.announcements}
            status={status.announce}
            subjectId={resolvedId}
            pagination={data.sectionPagination?.announce}
          />
          <NewsSection data={data.news} status={status.news} subjectId={resolvedId} />
          <PolicySection data={data.policies} status={status.policy} />
          <EventSection
            data={data.events}
            status={status.event}
            subjectId={resolvedId}
            total={data.sectionPagination?.event?.total}
          />
        </div>
      </div>
    </div>
  );
}

/**
 * 标的详情弹框（交互优化二段：完整版，弹框即终点）。
 * 与独立页 SubjectDetail 同源同渲染：SubjectHeader 信息 + 行情/财务/估值/公告/新闻/政策/事件
 * 7 分区完整内容（复用分区组件，分页/加载更多/AI 简报/原文外链等子级交互沿独立页行为）；
 * 无「查看完整详情」链接——完整内容已在弹框内，用户不再需要跳独立页。
 * 取数沿 useSubjectDetail 两跳（by-code 解析 + 聚合 detail）与分区三态降级（骨架/错误/空态）。
 * 宽度沿 ui/dialog contentClassName 先例覆盖 max-w-md → max-w-4xl。
 */
export function SubjectDetailDialog({ open, subjectCode, onClose }: SubjectDetailDialogProps) {
  const active = open && subjectCode != null;
  return (
    <Dialog open={active} title="标的详情" onClose={onClose} contentClassName="max-w-4xl">
      {active && subjectCode != null ? <SubjectDetailDialogBody subjectCode={subjectCode} /> : null}
    </Dialog>
  );
}
