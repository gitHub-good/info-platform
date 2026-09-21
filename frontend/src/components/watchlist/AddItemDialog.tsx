import { useEffect, useState, type FormEvent } from 'react';
import { Button } from '@/components/ui/button';
import { Dialog } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';

const DEFAULT_THRESHOLD = '3.00';

interface AddItemDialogProps {
  open: boolean;
  submitting: boolean;
  error: string | null;
  onClose: () => void;
  onAdd: (subjectId: number, threshold: number) => Promise<void> | void;
}

/** 添加标的对话框：标的 ID（正整数）+ 异动阈值（%，默认 3.00）。 */
export function AddItemDialog({ open, submitting, error, onClose, onAdd }: AddItemDialogProps) {
  const [subjectId, setSubjectId] = useState('');
  const [threshold, setThreshold] = useState(DEFAULT_THRESHOLD);

  useEffect(() => {
    if (open) {
      setSubjectId('');
      setThreshold(DEFAULT_THRESHOLD);
    }
  }, [open]);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const sid = Number(subjectId);
    const thr = Number(threshold);
    if (!Number.isInteger(sid) || sid <= 0) return; // 标的 ID 须为正整数
    if (!Number.isFinite(thr) || thr < 0) return;
    await onAdd(sid, Number(thr.toFixed(2)));
  };

  return (
    <Dialog
      open={open}
      title="添加标的"
      description="输入标的 ID 与异动阈值（%），加入当前清单。"
      onClose={onClose}
    >
      <form className="flex flex-col gap-3" onSubmit={submit} data-testid="watchlist-add-item-form">
        <label className="flex flex-col gap-1 text-sm">
          <span>标的 ID</span>
          <Input
            type="number"
            min={1}
            step={1}
            inputMode="numeric"
            placeholder="如 1"
            value={subjectId}
            onChange={(e) => setSubjectId(e.target.value)}
            data-testid="watchlist-add-subjectId"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span>异动阈值（%）</span>
          <Input
            type="number"
            min={0}
            step={0.01}
            value={threshold}
            onChange={(e) => setThreshold(e.target.value)}
            data-testid="watchlist-add-threshold"
          />
        </label>
        {error ? (
          <p className="text-sm text-destructive" data-testid="watchlist-add-error">
            {error}
          </p>
        ) : null}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" size="sm" onClick={onClose}>
            取消
          </Button>
          <Button type="submit" size="sm" disabled={submitting} data-testid="watchlist-add-submit">
            {submitting ? '添加中…' : '添加'}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
