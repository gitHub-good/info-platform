import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import type { BriefFact } from '@/types/aibrief';

interface SourceLinkListProps {
  /** 事实回链（content.facts[]）：每条 claim/metric/value/source/sourceUrl，可点击跳原文。 */
  facts: BriefFact[];
  /** 顶层 sourceLinks（URL 字符串数组），去重后作为「相关来源」补充展示。 */
  sourceLinks?: string[] | null;
}

/**
 * 内部/过程性措辞（id=13 实测：模型自填「用户提供聚合行情数据」直接露给用户）。
 * 命中即净化展示为「平台数据」——后端 source 供幻觉回查原值不动，仅展示层净化。
 */
const INTERNAL_SOURCE_PATTERN = /用户提供|聚合|上下文|给定数据|无新闻|数据缺失/;

/** 面向投资者的净化来源徽章文本；空或 null 返回 null（表格显示 —）。 */
function sanitizedSource(source?: string | null): string | null {
  const s = (source ?? '').trim();
  if (!s) return null;
  return INTERNAL_SOURCE_PATTERN.test(s) ? '平台数据' : s;
}

/** claim 兜底（v1.0 模板产物 claim 可能为 null）：「{metric} 数值」；metric 亦空返回 null（显示 —）。 */
function claimText(fact: BriefFact): string | null {
  const claim = (fact.claim ?? '').trim();
  if (claim) return claim;
  const metric = (fact.metric ?? '').trim();
  return metric ? `${metric} 数值` : null;
}

/** 去重并剔除空串。 */
function dedupLinks(links?: string[] | null): string[] {
  if (!links) return [];
  return Array.from(new Set(links.map((l) => String(l).trim()).filter(Boolean)));
}

/**
 * 事实回链列表：把结构化事实（content.facts[]）渲染为表格，
 * 每行标注 claim / metric / value / source（徽章，内部措辞净化）/ sourceUrl（可点击跳原文），
 * 满足 PRD「每条事实附带原文链接、事实回链率 100%」。
 * 顶层 sourceLinks（URL 数组）作为「相关来源」链接补充，去重后展示。
 */
export function SourceLinkList({ facts, sourceLinks }: SourceLinkListProps) {
  const links = dedupLinks(sourceLinks);
  const hasFacts = facts.length > 0;

  return (
    <Card data-testid="brief-sources">
      <CardHeader>
        <CardTitle>事实回链</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {hasFacts ? (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>事实陈述</TableHead>
                <TableHead>指标</TableHead>
                <TableHead>数值</TableHead>
                <TableHead>来源</TableHead>
                <TableHead>原文</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {facts.map((f, i) => {
                const source = sanitizedSource(f.source);
                return (
                  <TableRow key={i} data-testid={`brief-fact-${i}`}>
                    <TableCell className="whitespace-normal">
                      {claimText(f) ?? '—'}
                    </TableCell>
                    <TableCell>{f.metric ?? '—'}</TableCell>
                    <TableCell data-testid={`brief-fact-value-${i}`}>
                      {f.value == null ? '—' : f.value}
                    </TableCell>
                    <TableCell>
                      {source ? (
                        <Badge
                          variant="secondary"
                          className="text-xs"
                          data-testid={`brief-fact-source-${i}`}
                        >
                          {source}
                        </Badge>
                      ) : (
                        '—'
                      )}
                    </TableCell>
                    <TableCell>
                      {f.sourceUrl ? (
                        <a
                          href={f.sourceUrl}
                          target="_blank"
                          rel="noreferrer"
                          className="text-xs text-primary underline"
                          data-testid={`brief-fact-link-${i}`}
                        >
                          原文
                        </a>
                      ) : (
                        '—'
                      )}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        ) : (
          <p
            className="text-sm text-muted-foreground"
            data-testid="brief-sources-empty"
          >
            暂无事实回链
          </p>
        )}

        {links.length > 0 ? (
          <section data-testid="brief-source-links">
            <h3 className="mb-1 text-sm font-medium">相关来源</h3>
            <ul className="flex flex-col gap-1">
              {links.map((u, i) => (
                <li key={i}>
                  <a
                    href={u}
                    target="_blank"
                    rel="noreferrer"
                    className="text-xs text-primary underline break-all"
                    data-testid={`brief-source-link-${i}`}
                  >
                    {u}
                  </a>
                </li>
              ))}
            </ul>
          </section>
        ) : null}
      </CardContent>
    </Card>
  );
}
