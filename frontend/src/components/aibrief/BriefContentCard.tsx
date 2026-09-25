import { useState } from 'react';
import { Badge } from '@/components/ui/badge';
import { Card, CardAction, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { DisclaimerBadge } from './DisclaimerBadge';
import type { BriefContent, BriefKeyEvent } from '@/types/aibrief';

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

/** 事件标题兜底截断上限（与提示词 v1.1 的 event 10~20 字契约一致）。 */
const TITLE_MAX_LENGTH = 20;

/** reason 超过该长度才提供「展开/收起」（两行装不下的长理由）。 */
const REASON_COLLAPSE_THRESHOLD = 60;

/** 事件标题无可合成素材时的泛化标题，保证展示不空。 */
const GENERIC_EVENT_TITLE = '关键事件';

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

/**
 * 事件标题兜底（v1.0 模板产物 event 可能为 null，id=13 实测）：event 空 → reason
 * 首句截断 ≤20 字 → 泛化标题。与后端解析兜底同规则，前端再兜一层防直接读存量 JSON。
 */
function eventTitle(e: BriefKeyEvent): string {
  const event = (e.event ?? '').trim();
  if (event) return event;
  const reason = (e.reason ?? '').trim();
  const firstSentence =
    reason
      .split(/[。！？!?；;\n]/)
      .map((s) => s.trim())
      .find(Boolean) ?? '';
  if (firstSentence) {
    return firstSentence.length > TITLE_MAX_LENGTH
      ? firstSentence.slice(0, TITLE_MAX_LENGTH)
      : firstSentence;
  }
  return GENERIC_EVENT_TITLE;
}

/** summary 按句读分段（保留句末标点），无句读时整段一句。 */
function splitSentences(summary: string): string[] {
  return summary.match(/[^。！？!?；;]+[。！？!?；;]?/g) ?? [summary];
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

/** 单条关键事件卡片：兜底标题 + impact 徽章 + reason 两行截断可展开 + 原文链接。 */
function KeyEventItem({
  event,
  index,
}: {
  event: BriefKeyEvent;
  index: number;
}) {
  const [expanded, setExpanded] = useState(false);
  const collapsible = (event.reason ?? '').trim().length > REASON_COLLAPSE_THRESHOLD;
  const impact = tendencyMeta(event.impact);

  return (
    <li
      className="rounded-lg border border-border/60 p-3"
      data-testid={`brief-key-event-${index}`}
    >
      <div className="flex flex-wrap items-center gap-2">
        <span
          className="text-sm font-semibold"
          data-testid={`brief-key-event-title-${index}`}
        >
          {eventTitle(event)}
        </span>
        {impact ? (
          <TendencyBadge tendency={impact} testId={`brief-key-event-impact-${index}`} />
        ) : null}
      </div>
      {event.reason ? (
        <p
          className={`mt-1 text-xs leading-relaxed text-muted-foreground ${
            expanded ? '' : 'line-clamp-2'
          }`}
          data-testid={`brief-key-event-reason-${index}`}
        >
          {event.reason}
        </p>
      ) : null}
      <div className="mt-1 flex flex-wrap items-center gap-3">
        {collapsible ? (
          <button
            type="button"
            className="text-xs text-primary underline"
            onClick={() => setExpanded((v) => !v)}
            data-testid={`brief-key-event-toggle-${index}`}
          >
            {expanded ? '收起' : '展开'}
          </button>
        ) : null}
        {event.sourceUrl ? (
          <a
            href={event.sourceUrl}
            target="_blank"
            rel="noreferrer"
            className="text-xs text-primary underline"
            data-testid={`brief-key-event-link-${index}`}
          >
            原文
          </a>
        ) : null}
      </div>
    </li>
  );
}

/**
 * 结构化简报展示卡：核心摘要 / 关键事件（含影响方向与回链）/ 倾向与理由 / 关注建议 /
 * 每日推荐 Top5（briefType=4，含推荐理由——T29 相关性命中因子的人工标注载体）。
 * status=1 直接展示；status=3 仍展示但附「部分数值待核实」降级提示（PRD 故事 2 场景 3）。
 */
export function BriefContentCard({ content, needVerify }: BriefContentCardProps) {
  const bias = tendencyMeta(content.bias);
  const keyEvents = content.keyEvents ?? [];
  const topRecommend = [...(content.topRecommend ?? [])].sort(
    (a, b) => (a.rank ?? 0) - (b.rank ?? 0),
  );

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
            <div className="flex flex-col gap-1.5 text-sm leading-relaxed text-muted-foreground">
              {splitSentences(content.summary).map((sentence, i) => (
                <p key={i} data-testid={`brief-summary-sentence-${i}`}>
                  {sentence}
                </p>
              ))}
            </div>
          </section>
        ) : null}

        {keyEvents.length > 0 ? (
          <section data-testid="brief-key-events">
            <h3 className="mb-1 text-sm font-medium">关键事件</h3>
            <ul className="flex flex-col gap-2">
              {keyEvents.map((e, i) => (
                <KeyEventItem key={i} event={e} index={i} />
              ))}
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

        {topRecommend.length > 0 ? (
          <section data-testid="brief-top-recommend">
            <h3 className="mb-1 text-sm font-medium">每日推荐 Top5</h3>
            <ol className="flex flex-col gap-2">
              {topRecommend.map((item, i) => (
                <li
                  key={item.subjectCode || i}
                  className="rounded-md border border-border/60 p-2"
                  data-testid={`brief-top-recommend-${i}`}
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge variant="secondary">No.{item.rank}</Badge>
                    <span className="text-sm">
                      {item.subjectName}（{item.subjectCode}）
                    </span>
                  </div>
                  {item.reason ? (
                    <p
                      className="mt-1 text-xs text-muted-foreground"
                      data-testid={`brief-top-recommend-reason-${i}`}
                    >
                      {item.reason}
                    </p>
                  ) : null}
                </li>
              ))}
            </ol>
          </section>
        ) : null}
      </CardContent>
    </Card>
  );
}
