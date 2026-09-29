import { useEffect, useState, type FormEvent } from 'react';
import { Button } from '@/components/ui/button';
import { Dialog } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import type { WatchlistView } from '@/types/watchlist';

interface RenameWatchlistDialogProps {
  /** 非 null 表示打开并改名该清单；null 表示关闭。 */
  watchlist: WatchlistView | null;
  submitting: boolean;
  error: string | null;
  onClose: () => void;
  onSubmit: (watchlistId: number, name: string) => Promise<void> | void;
}

/** 清单改名对话框：预填当前名，保存后由父级 PATCH；空名/纯空白前端拦截不发。 */
export function RenameWatchlistDialog({
  watchlist,
  submitting,
  error,
  onClose,
  onSubmit,
}: RenameWatchlistDialogProps) {
  const [name, setName] = useState('');

  useEffect(() => {
    if (watchlist) setName(watchlist.name);
  }, [watchlist]);

  const open = watchlist !== null;
  const trimmed = name.trim();

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (watchlist == null || !trimmed) return;
    await onSubmit(watchlist.id, trimmed);
  };

  return (
    <Dialog open={open} title="重命名清单" onClose={onClose}>
      <form className="flex flex-col gap-3" onSubmit={submit} data-testid="watchlist-rename-form">
        <label className="flex flex-col gap-1 text-sm">
          <span>清单名称</span>
          <Input
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="输入新名称"
            data-testid="watchlist-rename-input"
          />
        </label>
        {error ? (
          <p className="text-sm text-destructive" data-testid="watchlist-rename-error">
            {error}
          </p>
        ) : null}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" size="sm" onClick={onClose}>
            取消
          </Button>
          <Button
            type="submit"
            size="sm"
            disabled={submitting || !trimmed}
            data-testid="watchlist-rename-submit"
          >
            {submitting ? '保存中…' : '保存'}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
