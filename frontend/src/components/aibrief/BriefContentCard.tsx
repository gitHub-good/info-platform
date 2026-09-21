import { Badge } from '@/components/ui/badge';
import { Card, CardAction, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { DisclaimerBadge } from './DisclaimerBadge';
import type { BriefContent } from '@/types/aibrief';

interface BriefContentCardProps {
  content: BriefContent;
  /** status=3 待核实时展示降级提示（幻觉校验不符，不作已确认结论）。 */
  needVerify?: boolean;
}

interface Tendency {
  label: string;
  /** 倾向配色（A 股惯例：涨红跌绿 → 利好红、利空绿、中性灰）。 */
  className: string;
}

/**
 * bias / keyEvent.impact 倾向配色。
 * A 股惯例涨红跌绿：利好→红（rose）、利空→绿（emerald）、中性→灰（muted）。
 * 文字标签明示，消除纯色歧义；与 BriefStatusBadge 的 destructive 红（失败）不同时出现
 * （失败时 content 为 null，无 bias 展示）。
 */
function tendencyMeta(t?: string): Tendency | null {
  const s = (t ?? '').trim();
  if (!s) return null;
  if (s.includes('利空')) {
    return { label: '利空', className: 'bg-emerald-500/15 text-emerald-400' };
  }
  if (s.includes('利好')) {
    return { label: '利好', className: 'bg-rose-500/15 text-rose-400' };
  }
  if (s.includes('中性')) {
    return { label: '中性', className: 'bg-muted text-muted-foreground' };
  }
  return null;
}

function TendencyBadge({
  tendency,
  testId,
}: {
  tendency: Tendency | null;
  testId?: string;
}) {
  if (!tendency) return null;
  return (
    <Badge variant="ghost" className={tendency.className} data-testid={testId}>
      {tendency.label}
    </Badge>
  );
}

/**
 * 结构化简报展示卡：核心摘要 / 关键事件（含影响方向与回链）/ 倾向与理由 / 关注建议。
 * status=1 直接展示；status=3 仍展示但附「部分数值待核实」降级提示（PRD 故事 2 场景 3）。
 */
export function BriefContentCard({ content, needVerify }: BriefContentCardProps) {
  const bias = tendencyMeta(content.bias);
  const keyEvents = content.keyEvents ?? [];

  return (
    <Card data-testid="brief-content">
      <CardHeader>
        <CardTitle>结构化简报</CardTitle>
        <CardAction>
          <div className="flex flex-wrap items-center gap-1.5">
            {bias ? (
              <TendencyBadge tendency={bias} testId="brief-bias" />
            ) : null}
            <DisclaimerBadge text={content.disclaimer} />
          </div>
        </CardAction>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {needVerify ? (
          <p
            className="rounded-md bg-amber-500/10 px-3 py-2 text-sm text-amber-400"
            data-testid="brief-need-verify"
            role="alert"
          >
            部分数值待核实，请以原始数据源为准，不作已确认结论。
          </p>
        ) : null}

        {content.summary ? (
          <section data-testid="brief-summary">
            <h3 className="mb-1 text-sm font-medium">核心摘要</h3>
            <p className="text-sm text-muted-foreground">{content.summary}</p>
          </section>
        ) : null}

        {keyEvents.length > 0 ? (
          <section data-testid="brief-key-events">
            <h3 className="mb-1 text-sm font-medium">关键事件</h3>
            <ul className="flex flex-col gap-2">
              {keyEvents.map((e, i) => {
                const impact = tendencyMeta(e.impact);
                return (
                  <li
                    key={i}
                    className="rounded-md border border-border/60 p-2"
                    data-testid={`brief-key-event-${i}`}
                  >
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-sm">{e.event}</span>
                      {impact ? <TendencyBadge tendency={impact} /> : null}
                    </div>
                    {e.reason ? (
                      <p className="mt-1 text-xs text-muted-foreground">{e.reason}</p>
                    ) : null}
                    {e.sourceUrl ? (
                      <a
                        href={e.sourceUrl}
                        target="_blank"
                        rel="noreferrer"
                        className="mt-1 inline-block text-xs text-primary underline"
                        data-testid={`brief-key-event-link-${i}`}
                      >
                        原文
                      </a>
                    ) : null}
                  </li>
                );
              })}
            </ul>
          </section>
        ) : null}

        {content.biasReason ? (
          <section data-testid="brief-bias-reason">
            <h3 className="mb-1 text-sm font-medium">倾向理由</h3>
            <p className="text-sm text-muted-foreground">{content.biasReason}</p>
          </section>
        ) : null}

        {content.watchSuggestion ? (
          <section data-testid="brief-suggestion">
            <h3 className="mb-1 text-sm font-medium">关注建议</h3>
            <p className="text-sm text-muted-foreground">{content.watchSuggestion}</p>
          </section>
        ) : null}
      </CardContent>
    </Card>
  );
}
