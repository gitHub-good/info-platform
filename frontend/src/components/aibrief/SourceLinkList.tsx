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

/** 去重并剔除空串。 */
function dedupLinks(links?: string[] | null): string[] {
  if (!links) return [];
  return Array.from(new Set(links.map((l) => String(l).trim()).filter(Boolean)));
}

/**
 * 事实回链列表：把结构化事实（content.facts[]）渲染为表格，
 * 每行标注 claim / metric / value / source / sourceUrl（可点击跳原文），
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
              {facts.map((f, i) => (
                <TableRow key={i} data-testid={`brief-fact-${i}`}>
                  <TableCell className="whitespace-normal">{f.claim ?? '—'}</TableCell>
                  <TableCell>{f.metric ?? '—'}</TableCell>
                  <TableCell data-testid={`brief-fact-value-${i}`}>
                    {f.value == null ? '—' : f.value}
                  </TableCell>
                  <TableCell>{f.source ?? '—'}</TableCell>
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
              ))}
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
