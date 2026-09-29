import { useCallback, useEffect, useState } from 'react';
import { Button } from '@/components/ui/button';
import { PageHeader } from '@/components/ui/PageHeader';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog } from '@/components/ui/dialog';
import { EmptyState } from '@/components/ui/EmptyState';
import { Skeleton } from '@/components/ui/skeleton';
import { AddItemDialog } from '@/components/watchlist/AddItemDialog';
import { CreateWatchlistForm } from '@/components/watchlist/CreateWatchlistForm';
import { EditThresholdDialog } from '@/components/watchlist/EditThresholdDialog';
import { RenameWatchlistDialog } from '@/components/watchlist/RenameWatchlistDialog';
import { WatchlistCard } from '@/components/watchlist/WatchlistCard';
import { WatchlistDetail } from '@/components/watchlist/WatchlistDetail';
import { ApiError } from '@/api/http';
import {
  addWatchlistItem,
  createWatchlist,
  deleteWatchlist,
  getWatchlist,
  getWatchlistItemsPaged,
  listWatchlists,
  removeWatchlistItem,
  renameWatchlist,
  updateItemThreshold,
} from '@/api/watchlist';
import type {
  WatchlistItemView,
  WatchlistItemsPagedView,
  WatchlistItemsSortKey,
  WatchlistView,
} from '@/types/watchlist';

/** 非 ApiError 兜底文案。 */
function messageOf(err: unknown, fallback: string): string {
  return err instanceof ApiError ? err.msg : fallback;
}

/** 创建清单错误映射：30011 同名 → 友好提示。 */
function createErrorMessage(err: unknown): string {
  if (err instanceof ApiError && err.code === 30011) return '同名清单已存在';
  return messageOf(err, '创建失败');
}

/** 加标的错误映射：30011 已在 / 30001 标的不存在 / 30010 清单不存在 / 30012 越权。 */
function addItemErrorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.code === 30011) return '该标的已在清单中';
    if (err.code === 30001) return '标的不存在';
    if (err.code === 30010) return '自选清单不存在';
    if (err.code === 30012) return '无权操作该清单';
    return err.msg;
  }
  return '添加失败';
}

/** 改名错误映射：30011 同名 / 30010 不存在 / 30012 越权。 */
function renameErrorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.code === 30011) return '同名清单已存在';
    if (err.code === 30010) return '自选清单不存在';
    if (err.code === 30012) return '无权操作该清单';
    return err.msg;
  }
  return '改名失败';
}

/** 清单项表缺省分页（M9 口径 20/页，可选 10/20/50）。 */
const DEFAULT_ITEMS_PAGE_SIZE = 20;

/**
 * watchlist 管理页（技术方案 §4.1.2 + T12）。
 * 列表 / 创建 / 详情 / 加标的 / 删标的 / 改阈值，错误按 30010/30011/30012/30001 提示。
 * 清单项表分页+排序（M9 页码契约）：GET /watchlists/{id}/items?page&size&sort&dir，
 * 行情两列内联返回（不再二次调 /subjects/quotes）；最新价/涨跌幅表头可排序，行情缺失行沉底。
 * 受保护接口 401 由 http 层统一清 token 跳 /login。
 */
export function Watchlist() {
  const [watchlists, setWatchlists] = useState<WatchlistView[]>([]);
  const [listLoading, setListLoading] = useState(true);
  const [listError, setListError] = useState<string | null>(null);

  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [selected, setSelected] = useState<WatchlistView | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  // 清单项分页+排序态（受控；切清单/切排序重置回第 1 页）
  const [itemsView, setItemsView] = useState<WatchlistItemsPagedView | null>(null);
  const [itemsLoading, setItemsLoading] = useState(false);
  const [itemsError, setItemsError] = useState<string | null>(null);
  // 手动重试滴答（进 effect 依赖）：仅改 error 不改查询参数也能重发
  const [itemsRefreshTick, setItemsRefreshTick] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_ITEMS_PAGE_SIZE);
  const [sort, setSort] = useState<WatchlistItemsSortKey>('addedAt');
  const [dir, setDir] = useState<'asc' | 'desc'>('asc');

  const [submitting, setSubmitting] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);
  const [detailError, setDetailError] = useState<string | null>(null);

  const [addOpen, setAddOpen] = useState(false);
  const [editItem, setEditItem] = useState<WatchlistItemView | null>(null);
  // 改名目标清单（null = 关闭）与改名错误（弹框内展示）
  const [renameTarget, setRenameTarget] = useState<WatchlistView | null>(null);
  const [renameError, setRenameError] = useState<string | null>(null);
  // 删除二次确认目标（destructive 防误触，全站危险操作确认规范）
  const [deleteTarget, setDeleteTarget] = useState<WatchlistView | null>(null);

  const reloadList = useCallback(async () => {
    setListLoading(true);
    setListError(null);
    try {
      setWatchlists(await listWatchlists());
    } catch (err) {
      setListError(messageOf(err, '清单加载失败'));
    } finally {
      setListLoading(false);
    }
  }, []);

  const reloadDetail = useCallback(async (id: number) => {
    setDetailLoading(true);
    setDetailError(null);
    try {
      setSelected(await getWatchlist(id));
    } catch (err) {
      setDetailError(messageOf(err, '清单详情加载失败'));
      setSelected(null);
    } finally {
      setDetailLoading(false);
    }
  }, []);

  // 清单项分页查询（selectedId/page/pageSize/sort/dir 任一变化即重查；selectedId 为空不发）
  useEffect(() => {
    if (selectedId == null) {
      setItemsView(null);
      return;
    }
    const controller = new AbortController();
    setItemsLoading(true);
    setItemsError(null);
    getWatchlistItemsPaged(selectedId, { page, size: pageSize, sort, dir }, controller.signal)
      .then((view) => {
        if (controller.signal.aborted) return;
        setItemsView(view);
        // 删除末页最后一条后落位回有效页（后端超界页返回空 items）
        const lastPage = Math.max(1, Math.ceil(view.total / view.size));
        if (view.items.length === 0 && view.total > 0 && view.page > lastPage) {
          setPage(lastPage);
        }
      })
      .catch((err) => {
        if (controller.signal.aborted) return;
        setItemsError(messageOf(err, '清单项加载失败'));
        setItemsView(null);
      })
      .finally(() => {
        if (!controller.signal.aborted) setItemsLoading(false);
      });
    return () => controller.abort();
  }, [selectedId, page, pageSize, sort, dir, itemsRefreshTick]);

  // 首次加载清单列表
  useEffect(() => {
    void reloadList();
  }, [reloadList]);

  // 列表就绪后自动选中第一个并拉详情
  useEffect(() => {
    if (selectedId === null && watchlists.length > 0) {
      const firstId = watchlists[0].id;
      setSelectedId(firstId);
      setPage(1);
      void reloadDetail(firstId);
    }
  }, [watchlists, selectedId, reloadDetail]);

  const handleSelect = (id: number) => {
    setSelectedId(id);
    setPage(1); // 切清单回第 1 页（排序偏好保留）
    setDetailError(null);
    void reloadDetail(id);
  };

  const handleSortChange = (key: WatchlistItemsSortKey) => {
    setPage(1);
    if (sort !== key) {
      // 新键降序优先（金融列表习惯：先看最高/最强）
      setSort(key);
      setDir('desc');
      return;
    }
    setDir((prev) => (prev === 'desc' ? 'asc' : 'desc'));
  };

  /** 动作后三刷：分页行（tick 触发 effect 重查）+ 卡片标的数 + 清单元信息。 */
  const refreshItemsAndCounts = async (id: number) => {
    setItemsRefreshTick((t) => t + 1);
    await Promise.all([reloadList(), reloadDetail(id)]);
  };

  const handleCreate = async (name: string) => {
    setSubmitting(true);
    setCreateError(null);
    try {
      const created = await createWatchlist(name);
      setWatchlists(await listWatchlists());
      setSelectedId(created.id);
      setPage(1);
      void reloadDetail(created.id);
    } catch (err) {
      setCreateError(createErrorMessage(err));
    } finally {
      setSubmitting(false);
    }
  };

  const handleAdd = async (subjectId: number, threshold: number) => {
    const id = selectedId;
    if (id == null) return;
    setSubmitting(true);
    setDetailError(null);
    try {
      await addWatchlistItem(id, subjectId, threshold);
      setAddOpen(false);
      await refreshItemsAndCounts(id);
    } catch (err) {
      setDetailError(addItemErrorMessage(err));
    } finally {
      setSubmitting(false);
    }
  };

  const handleRemove = async (itemId: number) => {
    const id = selectedId;
    if (id == null) return;
    setSubmitting(true);
    setDetailError(null);
    try {
      await removeWatchlistItem(id, itemId);
      await refreshItemsAndCounts(id);
    } catch (err) {
      setDetailError(messageOf(err, '移除失败'));
    } finally {
      setSubmitting(false);
    }
  };

  const handleEditThreshold = async (itemId: number, threshold: number) => {
    const id = selectedId;
    if (id == null) return;
    setSubmitting(true);
    setDetailError(null);
    try {
      await updateItemThreshold(id, itemId, threshold);
      setEditItem(null);
      setItemsRefreshTick((t) => t + 1); // 阈值列在分页行里，重查当前页
      await reloadDetail(id);
    } catch (err) {
      setDetailError(messageOf(err, '修改阈值失败'));
    } finally {
      setSubmitting(false);
    }
  };

  const openAdd = () => {
    setDetailError(null);
    setAddOpen(true);
  };

  const openEdit = (item: WatchlistItemView) => {
    setDetailError(null);
    setEditItem(item);
  };

  const handleRename = async (watchlistId: number, name: string) => {
    setSubmitting(true);
    setRenameError(null);
    try {
      await renameWatchlist(watchlistId, name);
      setRenameTarget(null);
      await reloadList();
      if (watchlistId === selectedId) {
        await reloadDetail(watchlistId); // 选中清单改名 → 详情标题同步
      }
    } catch (err) {
      setRenameError(renameErrorMessage(err));
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = async (watchlistId: number) => {
    setSubmitting(true);
    setDetailError(null);
    try {
      await deleteWatchlist(watchlistId);
      const remaining = await listWatchlists();
      setWatchlists(remaining);
      if (watchlistId === selectedId) {
        // 删除的是当前选中清单：改选剩余第一张（无剩余清空详情）
        const next = remaining[0]?.id ?? null;
        setSelectedId(next);
        setPage(1);
        if (next != null) {
          void reloadDetail(next);
        } else {
          setSelected(null);
          setItemsView(null);
        }
      }
    } catch (err) {
      setDetailError(messageOf(err, '删除失败'));
    } finally {
      setSubmitting(false);
    }
  };

  // T38 导航收编：原头部「政策时事 / Job 日志 / 成本报表 / 登出」4 个跨页按钮移除，职责移交侧栏
  return (
    <main className="mx-auto w-full max-w-6xl p-4 sm:p-6" data-testid="watchlist-page">
      <PageHeader
        title="自选清单"
        subtitle="分组管理关注标的：行情速览、异动与详情一屏直达"
      />

      <Card className="mb-4">
        <CardHeader>
          <CardTitle className="text-base">新建清单</CardTitle>
        </CardHeader>
        <CardContent>
          <CreateWatchlistForm
            submitting={submitting}
            error={createError}
            onCreate={handleCreate}
          />
        </CardContent>
      </Card>

      {listLoading ? (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3" data-testid="watchlist-list-loading">
          {Array.from({ length: 3 }, (_, i) => (
            <Skeleton key={i} className="h-32 w-full" />
          ))}
        </div>
      ) : listError ? (
        <div
          className="flex flex-col items-center gap-2 py-10"
          data-testid="watchlist-list-error"
        >
          <p className="text-sm text-destructive" role="alert">
            {listError}
          </p>
          <Button
            variant="outline"
            size="sm"
            onClick={() => void reloadList()}
            data-testid="watchlist-list-retry"
          >
            重试
          </Button>
        </div>
      ) : watchlists.length === 0 ? (
        <EmptyState
          title="暂无清单"
          description="点上方「创建清单」开始"
          testId="watchlist-list-empty"
        />
      ) : (
        <section
          className="mb-6 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3"
          data-testid="watchlist-list"
        >
          {watchlists.map((w) => (
            <WatchlistCard
              key={w.id}
              watchlist={w}
              selected={w.id === selectedId}
              onSelect={handleSelect}
              onRename={(target) => {
                setRenameError(null);
                setRenameTarget(target);
              }}
              onDelete={(target) => {
                setDetailError(null);
                setDeleteTarget(target);
              }}
            />
          ))}
        </section>
      )}

      <WatchlistDetail
        watchlist={selected}
        loading={detailLoading}
        submitting={submitting}
        actionError={detailError}
        itemsView={itemsView}
        itemsLoading={itemsLoading}
        itemsError={itemsError}
        page={page}
        pageSize={pageSize}
        sort={sort}
        dir={dir}
        onPageChange={setPage}
        onPageSizeChange={(size) => {
          setPageSize(size);
          setPage(1);
        }}
        onSortChange={handleSortChange}
        onRetryItems={() => setItemsRefreshTick((t) => t + 1)}
        onOpenAdd={openAdd}
        onOpenEdit={openEdit}
        onRemove={handleRemove}
      />

      <AddItemDialog
        open={addOpen}
        submitting={submitting}
        error={detailError}
        onClose={() => setAddOpen(false)}
        onAdd={handleAdd}
      />

      <EditThresholdDialog
        item={editItem}
        submitting={submitting}
        error={detailError}
        onClose={() => setEditItem(null)}
        onSubmit={handleEditThreshold}
      />

      <RenameWatchlistDialog
        watchlist={renameTarget}
        submitting={submitting}
        error={renameError}
        onClose={() => {
          setRenameTarget(null);
          setRenameError(null);
        }}
        onSubmit={handleRename}
      />

      <Dialog
        open={deleteTarget != null}
        title="确认删除该清单？"
        description={`「${deleteTarget?.name ?? ''}」及其 ${deleteTarget?.items.length ?? 0} 个标的将停止异动检测与推送（软删除，可追溯）。`}
        onClose={() => setDeleteTarget(null)}
        footer={
          <>
            <Button
              variant="outline"
              size="sm"
              onClick={() => setDeleteTarget(null)}
              data-testid="watchlist-delete-confirm-cancel"
            >
              取消
            </Button>
            <Button
              variant="destructive"
              size="sm"
              disabled={submitting}
              onClick={() => {
                const target = deleteTarget;
                setDeleteTarget(null);
                if (target) void handleDelete(target.id);
              }}
              data-testid="watchlist-delete-confirm-ok"
            >
              确认删除
            </Button>
          </>
        }
      />
    </main>
  );
}

export default Watchlist;
