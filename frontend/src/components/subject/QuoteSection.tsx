import { SectionCard } from './SectionCard';
import { changeColorClass, formatMoney, formatPct, formatPrice, formatVolume } from '@/lib/format';
import type { Quote, SourceStatus } from '@/types/subject-detail';

interface QuoteSectionProps {
  data: Quote | null | undefined;
  status: SourceStatus;
}

function Metric({
  label,
  value,
  className,
}: {
  label: string;
  value: string;
  className?: string;
}) {
  return (
    <div className="flex flex-col gap-1">
      <span className="text-xs text-muted-foreground">{label}</span>
      <span className={`text-sm font-medium ${className ?? 'text-foreground'}`}>{value}</span>
    </div>
  );
}

/** 行情分区：最新价 / 涨跌幅 / 开高低收 / 量额 / 换手率 / 振幅 */
export function QuoteSection({ data, status }: QuoteSectionProps) {
  const changeClass = changeColorClass(data?.changePct);
  return (
    <SectionCard title="行情" status={status} source={data?.source} updatedAt={data?.updatedAt}>
      {data ? (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3" data-testid="quote-metrics">
          <Metric label="最新价" value={formatPrice(data.price)} className={changeClass} />
          <Metric label="涨跌幅" value={formatPct(data.changePct)} className={changeClass} />
          <Metric label="开盘价" value={formatPrice(data.open)} />
          <Metric label="最高价" value={formatPrice(data.high)} />
          <Metric label="最低价" value={formatPrice(data.low)} />
          <Metric label="昨收价" value={formatPrice(data.preClose)} />
          <Metric label="成交量" value={formatVolume(data.volume)} />
          <Metric label="成交额" value={formatMoney(data.amount)} />
          <Metric label="换手率" value={formatPct(data.turnoverRate)} />
          <Metric label="振幅" value={formatPct(data.amplitude)} />
        </div>
      ) : null}
    </SectionCard>
  );
}
