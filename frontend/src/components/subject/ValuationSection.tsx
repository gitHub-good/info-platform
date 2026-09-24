import { SectionCard } from './SectionCard';
import { formatNumber } from '@/lib/format';
import type { SourceStatus, Valuation } from '@/types/subject-detail';

interface ValuationSectionProps {
  data: Valuation | null | undefined;
  status: SourceStatus;
}

/** 估值分区：市盈率 PE / 市净率 PB（真实源键 peTtm 与 mock 源键 pe 互为兜底） */
export function ValuationSection({ data, status }: ValuationSectionProps) {
  return (
    <SectionCard title="估值" status={status} source={data?.source} updatedAt={data?.updatedAt}>
      {data ? (
        <div className="grid grid-cols-2 gap-3" data-testid="valuation-metrics">
          <div className="flex flex-col gap-1">
            <span className="text-xs text-muted-foreground">市盈率 PE</span>
            <span className="text-sm font-medium text-foreground">
              {formatNumber(data.pe ?? data.peTtm)}
            </span>
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-xs text-muted-foreground">市净率 PB</span>
            <span className="text-sm font-medium text-foreground">{formatNumber(data.pb)}</span>
          </div>
        </div>
      ) : null}
    </SectionCard>
  );
}
