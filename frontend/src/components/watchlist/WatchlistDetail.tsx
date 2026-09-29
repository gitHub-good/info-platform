import { useState } from 'react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardAction, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog } from '@/components/ui/dialog';
import { Pagination } from '@/components/ui/pagination';
import { Skeleton } from '@/components/ui/skeleton';
import { SubjectDetailDialog } from '@/components/subject/SubjectDetailDialog';
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
import type {
  WatchlistItemPagedRow,
  WatchlistItemView,
  WatchlistItemsPagedView,
  WatchlistItemsSortKey,
  WatchlistView,
} from '@/types/watchlist';

interface WatchlistDetailProps {
  watchlist: WatchlistView | null;
  /** 清单元信息（名称/备注）加载态。 */
  loading: boolean;
  submitting: boolean;
  /** 详情区动作（加/删/改阈值）的错误提示。 */
  actionError: string | null;
  /** 清单项分页视图（行情内联，分页排序态由受控 props 承载）；null = 未加载。 */
  itemsView: WatchlistItemsPagedView | null;
  itemsLoading: boolean;
  itemsError: string | null;
  page: number;
  pageSize: number;
  sort: WatchlistItemsSortKey;
  dir: 'asc' | 'desc';
  onPageChange: (page: number) => void;
  onPageSizeChange: (size: number) => void;
  /** 点排序表头：非当前键 → 该键降序（金融列表降序优先）；当前键 → 升降切换。 */
  onSortChange: (key: WatchlistItemsSortKey) => void;
  onRetryItems: () => void;
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

/** 标的列：代码 + 名称 + 行业徽章（行情整体缺失或标的未知时回退数字主键，不裸奔错误）。 */
function SubjectCell({ row }: { row: WatchlistItemPagedRow }) {
  if (!row.subjectCode) {
    return (
      <span className="text-muted-foreground" data-testid={`watchlist-item-subject-${row.id}`}>
        #{row.subjectId}
      </span>
    );
  }
  return (
    <div className="flex items-center gap-2" data-testid={`watchlist-item-subject-${row.id}`}>
      <span className="font-medium">{row.subjectCode}</span>
      <span>{row.name}</span>
      {row.industry ? <Badge variant="secondary">{row.industry}</Badge> : null}
    </div>
  );
}

/** 行情列：无数据显示「—」（行情缺失不阻断清单）。 */
function QuoteCell({
  testId,
  value,
  format,
  colorize = false,
}: {
  testId: string;
  value: number | null;
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

/** 可排序表头（最新价/涨跌幅）：箭头随激活态与方向切换。 */
function SortableTableHead({
  label,
  sortKey,
  activeSort,
  activeDir,
  onSortChange,
  testId,
}: {
  label: string;
  sortKey: WatchlistItemsSortKey;
  activeSort: WatchlistItemsSortKey;
  activeDir: 'asc' | 'desc';
  onSortChange: (key: WatchlistItemsSortKey) => void;
  testId: string;
}) {
  const active = activeSort === sortKey;
  return (
    <TableHead>
      <Button
        variant="ghost"
        size="xs"
        className="-ml-2 gap-1 px-2 font-medium"
        onClick={() => onSortChange(sortKey)}
        aria-label={`按${label}排序`}
        data-testid={testId}
      >
        {label}
        <span className="text-[10px] leading-none" aria-hidden>
          {active ? (activeDir === 'desc' ? '▼' : '▲') : '⇅'}
        </span>
      </Button>
    </TableHead>
  );
}

/** 清单详情：标题 + 添加标的入口 + 清单项表（分页 + 最新价/涨跌幅排序；移除需二次确认）。 */
export function WatchlistDetail({
  watchlist,
  loading,
  submitting,
  actionError,
  itemsView,
  itemsLoading,
  itemsError,
  page,
  pageSize,
  sort,
  dir,
  onPageChange,
  onPageSizeChange,
  onSortChange,
  onRetryItems,
  onOpenAdd,
  onOpenEdit,
  onRemove,
}: WatchlistDetailProps) {
  // 移除二次确认目标（destructive 操作防误触，全站危险操作确认规范）
  const [removeTarget, setRemoveTarget] = useState<WatchlistItemView | null>(null);
  // 查看详情弹框标的代码（交互优化：弹框预览替代跳独立页；null = 关闭）
  const [detailCode, setDetailCode] = useState<string | null>(null);

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

  const rows = itemsView?.items ?? [];
  const rowAsItem = (row: WatchlistItemPagedRow): WatchlistItemView => ({
    id: row.id,
    subjectId: row.subjectId,
    anomalyThreshold: row.anomalyThreshold,
    status: 1,
  });
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
          {itemsError ? (
            <div
              className="mb-3 flex items-center gap-2"
              data-testid="watchlist-items-error"
              role="alert"
            >
              <p className="text-sm text-destructive">{itemsError}</p>
              <Button variant="outline" size="xs" onClick={onRetryItems}>
                重试
              </Button>
            </div>
          ) : null}
          {itemsView != null && itemsView.total === 0 ? (
            <p
              className="py-6 text-center text-sm text-muted-foreground"
              data-testid="watchlist-detail-no-items"
            >
              该清单暂无标的，点“添加标的”开始监控
            </p>
          ) : (
            <div className={itemsLoading && rows.length === 0 ? 'opacity-60' : undefined}>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>标的</TableHead>
                    <SortableTableHead
                      label="最新价"
                      sortKey="price"
                      activeSort={sort}
                      activeDir={dir}
                      onSortChange={onSortChange}
                      testId="watchlist-sort-price"
                    />
                    <SortableTableHead
                      label="涨跌幅"
                      sortKey="changePct"
                      activeSort={sort}
                      activeDir={dir}
                      onSortChange={onSortChange}
                      testId="watchlist-sort-changePct"
                    />
                    <TableHead>异动阈值（%）</TableHead>
                    <TableHead className="text-right">操作</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody data-testid="watchlist-items-table">
                  {rows.map((row) => (
                    <TableRow key={row.id} data-testid={`watchlist-item-row-${row.id}`}>
                      <TableCell>
                        <SubjectCell row={row} />
                      </TableCell>
                      <TableCell>
                        <QuoteCell
                          testId={`watchlist-item-price-${row.id}`}
                          value={row.price}
                          format={formatPrice}
                        />
                      </TableCell>
                      <TableCell>
                        <QuoteCell
                          testId={`watchlist-item-changepct-${row.id}`}
                          value={row.changePct}
                          format={formatPct}
                          colorize
                        />
                      </TableCell>
                      <TableCell data-testid={`watchlist-item-threshold-${row.id}`}>
                        {formatNumber(row.anomalyThreshold)}
                      </TableCell>
                      <TableCell className="text-right">
                        <div className="flex justify-end gap-2">
                          <Button
                            variant="outline"
                            size="xs"
                            onClick={() => onOpenEdit(rowAsItem(row))}
                            disabled={submitting}
                            data-testid={`watchlist-edit-threshold-${row.id}`}
                          >
                            改阈值
                          </Button>
                          <Button
                            variant="destructive"
                            size="xs"
                            onClick={() => setRemoveTarget(rowAsItem(row))}
                            disabled={submitting}
                            data-testid={`watchlist-remove-item-${row.id}`}
                          >
                            移除
                          </Button>
                          {row.subjectCode ? (
                            <Button
                              variant="outline"
                              size="xs"
                              onClick={() => setDetailCode(row.subjectCode)}
                              data-testid={`watchlist-item-detail-${row.id}`}
                            >
                              查看详情
                            </Button>
                          ) : null}
                          <Button
                            variant="outline"
                            size="xs"
                            disabled={submitting}
                            onClick={() => navigate(`/ai-brief?subjectId=${row.subjectId}`)}
                            data-testid={`watchlist-item-brief-${row.id}`}
                          >
                            AI 简报
                          </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              {itemsView != null ? (
                <Pagination
                  page={page}
                  pageSize={pageSize}
                  total={itemsView.total}
                  onPageChange={onPageChange}
                  onPageSizeChange={onPageSizeChange}
                  disabled={itemsLoading || submitting}
                  pageSizeOptions={[10, 20, 50]}
                  testIdPrefix="watchlist-items"
                />
              ) : null}
            </div>
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

      {/* 查看详情弹框（交互优化）：核心分区预览 + 「查看完整详情」保独立页可达 */}
      <SubjectDetailDialog
        open={detailCode != null}
        subjectCode={detailCode}
        onClose={() => setDetailCode(null)}
      />
    </>
  );
}
