import { useCallback, useEffect, useState } from 'react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { AddItemDialog } from '@/components/watchlist/AddItemDialog';
import { CreateWatchlistForm } from '@/components/watchlist/CreateWatchlistForm';
import { EditThresholdDialog } from '@/components/watchlist/EditThresholdDialog';
import { WatchlistCard } from '@/components/watchlist/WatchlistCard';
import { WatchlistDetail } from '@/components/watchlist/WatchlistDetail';
import { logout } from '@/api/auth';
import { ApiError } from '@/api/http';
import {
  addWatchlistItem,
  createWatchlist,
  getWatchlist,
  listWatchlists,
  removeWatchlistItem,
  updateItemThreshold,
} from '@/api/watchlist';
import { navigate } from '@/lib/navigation';
import type { WatchlistItemView, WatchlistView } from '@/types/watchlist';

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

/**
 * watchlist 管理页（技术方案 §4.1.2 + T12）。
 * 列表 / 创建 / 详情 / 加标的 / 删标的 / 改阈值，错误按 30010/30011/30012/30001 提示。
 * 受保护接口 401 由 http 层统一清 token 跳 /login。
 */
export function Watchlist() {
  const [watchlists, setWatchlists] = useState<WatchlistView[]>([]);
  const [listLoading, setListLoading] = useState(true);
  const [listError, setListError] = useState<string | null>(null);

  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [selected, setSelected] = useState<WatchlistView | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  const [submitting, setSubmitting] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);
  const [detailError, setDetailError] = useState<string | null>(null);

  const [addOpen, setAddOpen] = useState(false);
  const [editItem, setEditItem] = useState<WatchlistItemView | null>(null);

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

  // 首次加载清单列表
  useEffect(() => {
    void reloadList();
  }, [reloadList]);

  // 列表就绪后自动选中第一个并拉详情
  useEffect(() => {
    if (selectedId === null && watchlists.length > 0) {
      const firstId = watchlists[0].id;
      setSelectedId(firstId);
      void reloadDetail(firstId);
    }
  }, [watchlists, selectedId, reloadDetail]);

  const handleSelect = (id: number) => {
    setSelectedId(id);
    setDetailError(null);
    void reloadDetail(id);
  };

  const handleCreate = async (name: string) => {
    setSubmitting(true);
    setCreateError(null);
    try {
      const created = await createWatchlist(name);
      setWatchlists(await listWatchlists());
      setSelectedId(created.id);
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
      const [detail, list] = await Promise.all([getWatchlist(id), listWatchlists()]);
      setSelected(detail);
      setWatchlists(list);
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
      const [detail, list] = await Promise.all([getWatchlist(id), listWatchlists()]);
      setSelected(detail);
      setWatchlists(list);
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
      setSelected(await getWatchlist(id));
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

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  return (
    <main className="mx-auto w-full max-w-6xl p-4 sm:p-6" data-testid="watchlist-page">
      <header className="mb-4 flex items-center justify-between">
        <h1 className="text-xl font-medium">自选清单</h1>
        <div className="flex items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={() => navigate('/policies')}
            data-testid="watchlist-goto-policies"
          >
            政策时事
          </Button>
          <Button variant="outline" size="sm" onClick={handleLogout} data-testid="watchlist-logout">
            登出
          </Button>
        </div>
      </header>

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
        <div className="py-10 text-center text-sm text-destructive" data-testid="watchlist-list-error">
          {listError}
        </div>
      ) : watchlists.length === 0 ? (
        <div className="py-10 text-center text-sm text-muted-foreground" data-testid="watchlist-list-empty">
          暂无清单，点上方“创建清单”开始
        </div>
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
            />
          ))}
        </section>
      )}

      <WatchlistDetail
        watchlist={selected}
        loading={detailLoading}
        submitting={submitting}
        actionError={detailError}
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
    </main>
  );
}

export default Watchlist;
