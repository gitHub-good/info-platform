import { SectionCard } from './SectionCard';
import { formatMoney, formatPct } from '@/lib/format';
import type { Finance, SourceStatus } from '@/types/subject-detail';

interface FinanceSectionProps {
  data: Finance | null | undefined;
  status: SourceStatus;
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex flex-col gap-1">
      <span className="text-xs text-muted-foreground">{label}</span>
      <span className="text-sm font-medium text-foreground">{value}</span>
    </div>
  );
}

/** 财务分区：营收 / 净利 / 毛利率 / ROE / 报告期 */
export function FinanceSection({ data, status }: FinanceSectionProps) {
  return (
    <SectionCard title="财务" status={status} source={data?.source} updatedAt={data?.updatedAt}>
      {data ? (
        <div className="grid grid-cols-2 gap-3" data-testid="finance-metrics">
          <Metric label="营业收入" value={formatMoney(data.revenue)} />
          <Metric label="净利润" value={formatMoney(data.netProfit)} />
          <Metric label="毛利率" value={formatPct(data.grossMargin)} />
          <Metric label="ROE" value={formatPct(data.roe)} />
          <Metric label="报告期" value={data.reportPeriod ?? '--'} />
        </div>
      ) : null}
    </SectionCard>
  );
}
