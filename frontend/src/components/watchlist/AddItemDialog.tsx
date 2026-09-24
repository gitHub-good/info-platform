import { useEffect, useState, type FormEvent } from 'react';
import { Button } from '@/components/ui/button';
import { Dialog } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { SubjectPicker } from '@/components/subject/SubjectPicker';
import type { SubjectSummary } from '@/api/subject';

const DEFAULT_THRESHOLD = '3.00';

interface AddItemDialogProps {
  open: boolean;
  submitting: boolean;
  error: string | null;
  onClose: () => void;
  onAdd: (subjectId: number, threshold: number) => Promise<void> | void;
}

/** 添加标的对话框：搜索选择标的（体检 P1-2，替代手输数字 ID）+ 异动阈值（%，默认 3.00）。 */
export function AddItemDialog({ open, submitting, error, onClose, onAdd }: AddItemDialogProps) {
  const [subject, setSubject] = useState<SubjectSummary | null>(null);
  const [threshold, setThreshold] = useState(DEFAULT_THRESHOLD);
  const [validation, setValidation] = useState<string | null>(null);

  useEffect(() => {
    if (open) {
      setSubject(null);
      setThreshold(DEFAULT_THRESHOLD);
      setValidation(null);
    }
  }, [open]);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!subject) {
      setValidation('请先搜索并选择标的');
      return;
    }
    const thr = Number(threshold);
    if (!Number.isFinite(thr) || thr < 0) return;
    setValidation(null);
    await onAdd(subject.id, Number(thr.toFixed(2)));
  };

  return (
    <Dialog
      open={open}
      title="添加标的"
      description="搜索选择标的并设置异动阈值（%），加入当前清单。"
      onClose={onClose}
    >
      <form className="flex flex-col gap-3" onSubmit={submit} data-testid="watchlist-add-item-form">
        <div className="flex flex-col gap-1 text-sm">
          <span>标的</span>
          <SubjectPicker
            value={subject}
            onChange={(next) => {
              setSubject(next);
              setValidation(null);
            }}
            disabled={submitting}
            testId="watchlist-add-subject"
          />
        </div>
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
        {validation ? (
          <p className="text-sm text-destructive" data-testid="watchlist-add-validation" role="alert">
            {validation}
          </p>
        ) : null}
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
