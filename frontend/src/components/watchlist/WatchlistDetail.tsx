import { useState } from 'react';
import { Button } from '@/components/ui/button';
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
import { formatNumber } from '@/lib/format';
import type { WatchlistItemView, WatchlistView } from '@/types/watchlist';

interface WatchlistDetailProps {
  watchlist: WatchlistView | null;
  loading: boolean;
  submitting: boolean;
  /** 详情区动作（加/删/改阈值）的错误提示。 */
  actionError: string | null;
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

/** 清单详情：标题 + 添加标的入口 + 清单项表（标的ID/阈值/改阈值/移除；移除需二次确认）。 */
export function WatchlistDetail({
  watchlist,
  loading,
  submitting,
  actionError,
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
        请从左侧选择一个清单查看明细
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
                  <TableHead>标的 ID</TableHead>
                  <TableHead>异动阈值（%）</TableHead>
                  <TableHead className="text-right">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody data-testid="watchlist-items-table">
                {items.map((item) => (
                  <TableRow key={item.id} data-testid={`watchlist-item-row-${item.id}`}>
                    <TableCell data-testid={`watchlist-item-subjectId-${item.id}`}>
                      {item.subjectId}
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
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
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
