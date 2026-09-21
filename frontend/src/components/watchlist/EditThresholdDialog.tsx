import { useEffect, useState, type FormEvent } from 'react';
import { Button } from '@/components/ui/button';
import { Dialog } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { formatNumber } from '@/lib/format';
import type { WatchlistItemView } from '@/types/watchlist';

interface EditThresholdDialogProps {
  /** 非 null 表示打开并编辑该项；null 表示关闭。 */
  item: WatchlistItemView | null;
  submitting: boolean;
  error: string | null;
  onClose: () => void;
  onSubmit: (itemId: number, threshold: number) => Promise<void> | void;
}

/** 修改异动阈值对话框：预填当前阈值，保存后由父级 PATCH。 */
export function EditThresholdDialog({ item, submitting, error, onClose, onSubmit }: EditThresholdDialogProps) {
  const [threshold, setThreshold] = useState('3.00');

  useEffect(() => {
    if (item) setThreshold(formatNumber(item.anomalyThreshold));
  }, [item]);

  const open = item !== null;
  const subjectId = item?.subjectId;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (item == null) return;
    const thr = Number(threshold);
    if (!Number.isFinite(thr) || thr < 0) return;
    await onSubmit(item.id, Number(thr.toFixed(2)));
  };

  return (
    <Dialog
      open={open}
      title="修改异动阈值"
      description={subjectId == null ? undefined : `标的 ID：${subjectId}`}
      onClose={onClose}
    >
      <form className="flex flex-col gap-3" onSubmit={submit} data-testid="watchlist-edit-form">
        <label className="flex flex-col gap-1 text-sm">
          <span>异动阈值（%）</span>
          <Input
            type="number"
            min={0}
            step={0.01}
            value={threshold}
            onChange={(e) => setThreshold(e.target.value)}
            data-testid="watchlist-edit-threshold"
          />
        </label>
        {error ? (
          <p className="text-sm text-destructive" data-testid="watchlist-edit-error">
            {error}
          </p>
        ) : null}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" size="sm" onClick={onClose}>
            取消
          </Button>
          <Button type="submit" size="sm" disabled={submitting} data-testid="watchlist-edit-submit">
            {submitting ? '保存中…' : '保存'}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
