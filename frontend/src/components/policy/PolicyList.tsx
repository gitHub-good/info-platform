import { PolicyItem } from './PolicyItem';
import type { PolicyView } from '@/types/policy';

interface PolicyListProps {
  items: PolicyView[];
  selectedId?: number | null;
  onSelect: (id: number) => void;
}

/** 政策列表（条目集合）。加载 / 空 / 错误态由页面分层处理（与 Watchlist 一致）。 */
export function PolicyList({ items, selectedId, onSelect }: PolicyListProps) {
  return (
    <ul className="flex flex-col gap-2" data-testid="policy-list">
      {items.map((p) => (
        <PolicyItem
          key={p.id}
          policy={p}
          selected={p.id === selectedId}
          onSelect={onSelect}
        />
      ))}
    </ul>
  );
}
