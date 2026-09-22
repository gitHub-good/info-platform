import { Badge } from '@/components/ui/badge';
import { changeColorClass, formatPct, formatPrice } from '@/lib/format';
import { SectionCard } from './SectionCard';
import type { EventItem, SourceStatus } from '@/types/subject-detail';

interface EventSectionProps {
  data: EventItem[] | null | undefined;
  status: SourceStatus;
}

/** 异动类型展示标签（anomaly_event.anomaly_type 枚举名 → 中文） */
const ANOMALY_TYPE_LABEL: Record<string, string> = {
  PRICE_CHANGE: '涨跌幅异动',
  VOLUME: '量异动',
  EVENT: '事件',
};

function typeLabel(anomalyType: string): string {
  return ANOMALY_TYPE_LABEL[anomalyType] ?? anomalyType;
}

/** 触发时刻（ISO-8601 UTC）→ 本地时间串展示 */
function formatTriggerTime(triggerTime: string): string {
  const date = new Date(triggerTime);
  if (Number.isNaN(date.getTime())) return triggerTime;
  return date.toLocaleString('zh-CN', { hour12: false });
}

/** 事件分区（ADR-0013）：本地 anomaly_event 近期异动/事件列表 */
export function EventSection({ data, status }: EventSectionProps) {
  const items = data ?? [];
  return (
    <SectionCard title="事件监控" status={status}>
      {items.length > 0 ? (
        <ul className="flex flex-col gap-3" data-testid="event-list">
          {items.map((item) => (
            <li
              key={`${item.anomalyType}-${item.triggerTime}`}
              className="flex flex-col gap-1 border-b border-border pb-3 last:border-0 last:pb-0"
            >
              <div className="flex flex-wrap items-center gap-2">
                <Badge variant="secondary">{typeLabel(item.anomalyType)}</Badge>
                {typeof item.changePct === 'number' ? (
                  <span className={`text-sm font-medium ${changeColorClass(item.changePct)}`}>
                    {formatPct(item.changePct)}
                  </span>
                ) : null}
              </div>
              {item.detail ? (
                <p className="text-xs text-muted-foreground line-clamp-2">{item.detail}</p>
              ) : null}
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
                <span>{formatTriggerTime(item.triggerTime)}</span>
                {typeof item.currentPrice === 'number' ? (
                  <span>现价 {formatPrice(item.currentPrice)}</span>
                ) : null}
              </div>
            </li>
          ))}
        </ul>
      ) : null}
    </SectionCard>
  );
}
