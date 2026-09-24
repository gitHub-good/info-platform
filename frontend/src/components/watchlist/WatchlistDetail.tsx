import { useState } from 'react';
import { Badge } from '@/components/ui/badge';
import { Button, buttonVariants } from '@/components/ui/button';
import { Card, CardAction, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog } from '@/components/ui/dialog';
import { Skeleton } from '@/components/ui/skeleton';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { navigate } from '@/lib/navigation';
import { changeColorClass, formatNumber, formatPct, formatPrice } from '@/lib/format';
import type { SubjectQuoteRow } from '@/api/subject';
import type { WatchlistItemView, WatchlistView } from '@/types/watchlist';

interface WatchlistDetailProps {
  watchlist: WatchlistView | null;
  loading: boolean;
  submitting: boolean;
  /** 详情区动作（加/删/改阈值）的错误提示。 */
  actionError: string | null;
  /** 标的摘要+行情（按 subjectId 索引，体检 P1-2 自选清单增强）；null = 未加载/加载失败，行情列显示「—」。 */
  subjectRows: Record<number, SubjectQuoteRow> | null;
  onOpenAdd: () => void;
  onOpenEdit: (item: WatchlistItemView) => void;
  onRemove: (itemId: number) => void;
}

function DetailSkeleton() {
  return (
    <div className="flex flex-col gap-3" data-testid="watchlist-detail-loading">
      <Skeleton className="h-8 w-40" />
      <Skeleton className="h-40 w-full" />
    </div>
  );
}

/** 标的列：代码 + 名称 + 行业徽章（行情缺失或标的未知时回退数字主键，不裸奔错误）。 */
function SubjectCell({ item, row }: { item: WatchlistItemView; row?: SubjectQuoteRow }) {
  if (!row) {
    return (
      <span className="text-muted-foreground" data-testid={`watchlist-item-subject-${item.id}`}>
        #{item.subjectId}
      </span>
    );
  }
  return (
    <div className="flex items-center gap-2" data-testid={`watchlist-item-subject-${item.id}`}>
      <span className="font-medium">{row.subjectCode}</span>
      <span>{row.name}</span>
      {row.industry ? <Badge variant="secondary">{row.industry}</Badge> : null}
    </div>
  );
}

/** 行情列：无数据显示「—」（行情失败不阻断清单）。 */
function QuoteCell({
  testId,
  value,
  format,
  colorize = false,
}: {
  testId: string;
  value: number | null | undefined;
  format: (v: number) => string;
  colorize?: boolean;
}) {
  if (value == null) {
    return (
      <span className="text-muted-foreground" data-testid={testId}>
        —
      </span>
    );
  }
  return (
    <span className={colorize ? changeColorClass(value) : undefined} data-testid={testId}>
      {format(value)}
    </span>
  );
}

/** 清单详情：标题 + 添加标的入口 + 清单项表（标的/最新价/涨跌幅/阈值/操作；移除需二次确认）。 */
export function WatchlistDetail({
  watchlist,
  loading,
  submitting,
  actionError,
  subjectRows,
  onOpenAdd,
  onOpenEdit,
  onRemove,
}: WatchlistDetailProps) {
  // 移除二次确认目标（destructive 操作防误触，全站危险操作确认规范）
  const [removeTarget, setRemoveTarget] = useState<WatchlistItemView | null>(null);

  if (loading) return <DetailSkeleton />;
  if (!watchlist) {
    return (
      <div
        className="py-10 text-center text-sm text-muted-foreground"
        data-testid="watchlist-detail-empty"
      >
        请从上方选择一个清单查看明细
      </div>
    );
  }

  const items = watchlist.items;
  return (
    <>
      <Card data-testid="watchlist-detail">
        <CardHeader>
          <CardTitle className="text-lg" data-testid="watchlist-detail-name">
            {watchlist.name}
          </CardTitle>
          <CardAction>
            <Button size="sm" onClick={onOpenAdd} disabled={submitting} data-testid="watchlist-add-item">
              添加标的
            </Button>
          </CardAction>
        </CardHeader>
        <CardContent>
          {watchlist.remark ? (
            <p className="mb-3 text-sm text-muted-foreground">{watchlist.remark}</p>
          ) : null}
          {actionError ? (
            <p className="mb-3 text-sm text-destructive" data-testid="watchlist-action-error">
              {actionError}
            </p>
          ) : null}
          {items.length === 0 ? (
            <p
              className="py-6 text-center text-sm text-muted-foreground"
              data-testid="watchlist-detail-no-items"
            >
              该清单暂无标的，点“添加标的”开始监控
            </p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>标的</TableHead>
                  <TableHead>最新价</TableHead>
                  <TableHead>涨跌幅</TableHead>
                  <TableHead>异动阈值（%）</TableHead>
                  <TableHead className="text-right">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody data-testid="watchlist-items-table">
                {items.map((item) => {
                  const row = subjectRows?.[item.subjectId];
                  return (
                    <TableRow key={item.id} data-testid={`watchlist-item-row-${item.id}`}>
                      <TableCell>
                        <SubjectCell item={item} row={row} />
                      </TableCell>
                      <TableCell>
                        <QuoteCell
                          testId={`watchlist-item-price-${item.id}`}
                          value={row?.quote?.price}
                          format={formatPrice}
                        />
                      </TableCell>
                      <TableCell>
                        <QuoteCell
                          testId={`watchlist-item-changepct-${item.id}`}
                          value={row?.quote?.changePct}
                          format={formatPct}
                          colorize
                        />
                      </TableCell>
                      <TableCell data-testid={`watchlist-item-threshold-${item.id}`}>
                        {formatNumber(item.anomalyThreshold)}
                      </TableCell>
                      <TableCell className="text-right">
                        <div className="flex justify-end gap-2">
                          <Button
                            variant="outline"
                            size="xs"
                            onClick={() => onOpenEdit(item)}
                            disabled={submitting}
                            data-testid={`watchlist-edit-threshold-${item.id}`}
                          >
                            改阈值
                          </Button>
                          <Button
                            variant="destructive"
                            size="xs"
                            onClick={() => setRemoveTarget(item)}
                            disabled={submitting}
                            data-testid={`watchlist-remove-item-${item.id}`}
                          >
                            移除
                          </Button>
                          {row ? (
                            <a
                              href={`#/subjects/${encodeURIComponent(row.subjectCode)}`}
                              className={buttonVariants({ variant: 'outline', size: 'xs' })}
                              data-testid={`watchlist-item-detail-${item.id}`}
                            >
                              查看详情
                            </a>
                          ) : null}
                          <Button
                            variant="outline"
                            size="xs"
                            disabled={submitting}
                            onClick={() => navigate(`/ai-brief?subjectId=${item.subjectId}`)}
                            data-testid={`watchlist-item-brief-${item.id}`}
                          >
                            AI 简报
                          </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

        <Dialog
        open={removeTarget != null}
        title="确认移除该标的？"
        description="移除后该标的的异动检测与推送将同步停止。"
        onClose={() => setRemoveTarget(null)}
        footer={
          <>
            <Button
              variant="outline"
              size="sm"
              onClick={() => setRemoveTarget(null)}
              data-testid="watchlist-remove-confirm-cancel"
            >
              取消
            </Button>
            <Button
              variant="destructive"
              size="sm"
              disabled={submitting}
              onClick={() => {
                const target = removeTarget;
                setRemoveTarget(null);
                if (target) onRemove(target.id);
              }}
              data-testid="watchlist-remove-confirm-ok"
            >
              确认移除
            </Button>
          </>
        }
      />
    </>
  );
}
