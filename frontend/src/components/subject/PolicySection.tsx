import { Badge } from '@/components/ui/badge';
import { SectionCard } from './SectionCard';
import { navigate } from '@/lib/navigation';
import type {
  PolicySectionItem,
  PolicySectionView,
  SourceStatus,
} from '@/types/subject-detail';

/** 列表滚动容器样式（UI 设计 §4 高度策略：md 起内滚，出口行常驻滚动区外）。 */
const LIST_SCROLL_CLASS = 'md:max-h-[26rem] md:overflow-y-auto';

/** 命中级别徽章（V2.3-M23 T206 三来源分级：SUBJECT=标的关联 emerald / INDUSTRY=行业关联 sky；缺省不渲染）。 */
function MatchTypeBadge({ item }: { item: PolicySectionItem }) {
  if (item.matchType === 'SUBJECT') {
    return (
      <Badge
        className="bg-emerald-500/15 text-emerald-400"
        data-testid={`policy-match-${item.id ?? item.title}`}
        title="matched_subjects 直接回联该标的"
      >
        标的关联
      </Badge>
    );
  }
  if (item.matchType === 'INDUSTRY') {
    return (
      <Badge
        className="bg-sky-500/15 text-sky-400"
        data-testid={`policy-match-${item.id ?? item.title}`}
        title="标的行业关联集命中的行业政策"
      >
        行业关联
      </Badge>
    );
  }
  return null;
}

/** 单条政策行：标题外链 + 时间 + 源 + 命中级别徽章（兜底段条目无徽章）。 */
function PolicyRow({ item }: { item: PolicySectionItem }) {
  return (
    <li
      className="flex flex-col gap-1 border-b border-border pb-3 last:border-0 last:pb-0"
      data-testid="policy-row"
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
        <MatchTypeBadge item={item} />
        <span>{item.publishedAt ?? ''}</span>
        {item.sourceName ? <span>来源：{item.sourceName}</span> : null}
      </div>
    </li>
  );
}

interface PolicySectionProps {
  data: PolicySectionView | null | undefined;
  status: SourceStatus;
}

/**
 * 政策分区（M12 T95 → V2.3-M23 T206 分区对象契约呈现）：
 * 关联命中段（items，SUBJECT/INDUSTRY 分级徽章）或宏观兜底段（fallback：muted「近期宏观政策」
 * + note 口径明示文案——不冒充关联）二选其一；底部右对齐「全部政策 →」跳 /policies 深检索出口 +
 * basis 口径脚注。库内查询无外呼三态（sourceStatus.policy 恒 ok），MISSING 构造性消除——
 * 双段皆空的异常形态仅 muted 空文案兜底不崩。
 */
export function PolicySection({ data, status }: PolicySectionProps) {
  const items = data?.items ?? [];
  const fallback = data?.fallback ?? null;
  // 防御：items 非空时契约上 fallback 恒 null；若双段同现，关联段优先（徽章可解释性不破坏）
  const showFallback = items.length === 0 && fallback != null && fallback.items.length > 0;
  const hasContent = items.length > 0 || showFallback;

  return (
    <SectionCard title="政策" status={status}>
      {hasContent ? (
        <>
          <div className={LIST_SCROLL_CLASS}>
            {showFallback ? (
              <section data-testid="policy-fallback">
                <p className="mb-2 text-xs text-muted-foreground" data-testid="policy-fallback-note">
                  近期宏观政策 · {fallback?.note}
                </p>
                <ul className="flex flex-col gap-3" data-testid="policy-fallback-list">
                  {fallback?.items.map((item) => (
                    <PolicyRow key={item.id ?? item.title} item={item} />
                  ))}
                </ul>
              </section>
            ) : (
              <ul className="flex flex-col gap-3" data-testid="policy-list">
                {items.map((item) => (
                  <PolicyRow key={item.id ?? item.title} item={item} />
                ))}
              </ul>
            )}
          </div>
          <div className="mt-4 flex flex-wrap items-center justify-end gap-3 border-t border-border pt-4">
            {data?.basis ? (
              <span className="text-xs text-muted-foreground" data-testid="policy-basis">
                口径 {data.basis}
              </span>
            ) : null}
            <button
              type="button"
              onClick={() => navigate('/news-library?l1=监管·政策')}
              data-testid="policy-more-link"
              className="text-sm text-primary underline-offset-4 hover:underline"
            >
              全部政策 →
            </button>
          </div>
        </>
      ) : null}
    </SectionCard>
  );
}
