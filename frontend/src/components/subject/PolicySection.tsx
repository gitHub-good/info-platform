import { SectionCard } from './SectionCard';
import { navigate } from '@/lib/navigation';
import type { PolicyItem, SourceStatus } from '@/types/subject-detail';

/** 列表滚动容器样式（UI 设计 §4 高度策略：md 起内滚，出口行常驻滚动区外）。 */
const LIST_SCROLL_CLASS = 'md:max-h-[26rem] md:overflow-y-auto';

interface PolicySectionProps {
  data: PolicyItem[] | null | undefined;
  status: SourceStatus;
}

/**
 * 政策分区：标题 / 发文单位 / 时间 / 来源 / URL 列表（M12 T95，UI 设计 §3.4）。
 * 不翻页（单页全量语义，PRD Won't——源无分页能力不伪造空页）；底部右对齐
 * 「更多政策 →」站内文字链跳 /policies（深检索出口），仅 ok 且有命中时渲染。
 */
export function PolicySection({ data, status }: PolicySectionProps) {
  const items = data ?? [];
  return (
    <SectionCard title="政策" status={status}>
      {items.length > 0 ? (
        <>
          <div className={LIST_SCROLL_CLASS}>
            <ul className="flex flex-col gap-3" data-testid="policy-list">
              {items.map((item) => (
                <li
                  key={item.id ?? item.title}
                  className="flex flex-col gap-1 border-b border-border pb-3 last:border-0 last:pb-0"
                >
                  <a
                    href={item.url ?? '#'}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="text-sm font-medium text-foreground hover:underline"
                  >
                    {item.title}
                  </a>
                  <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
                    {item.publisher ? <span>发文单位：{item.publisher}</span> : null}
                    <span>{item.publishedAt}</span>
                    {item.source ? <span>来源：{item.source}</span> : null}
                  </div>
                </li>
              ))}
            </ul>
          </div>
          <div className="mt-4 border-t border-border pt-4 text-right">
            <button
              type="button"
              onClick={() => navigate('/policies')}
              data-testid="policy-more-link"
              className="text-sm text-primary underline-offset-4 hover:underline"
            >
              更多政策 →
            </button>
          </div>
        </>
      ) : null}
    </SectionCard>
  );
}
