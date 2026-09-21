import { useState, type FormEvent } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';

interface CreateWatchlistFormProps {
  submitting: boolean;
  error: string | null;
  onCreate: (name: string) => void;
}

/** 新建清单内联表单：输入名称 → 创建；空名禁提交。 */
export function CreateWatchlistForm({ submitting, error, onCreate }: CreateWatchlistFormProps) {
  const [name, setName] = useState('');

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const trimmed = name.trim();
    if (!trimmed || submitting) return;
    onCreate(trimmed);
    setName('');
  };

  return (
    <form className="flex flex-wrap items-center gap-2" onSubmit={submit} data-testid="watchlist-create-form">
      <Input
        placeholder="新清单名称"
        value={name}
        onChange={(e) => setName(e.target.value)}
        data-testid="watchlist-create-name"
        className="max-w-xs"
      />
      <Button type="submit" size="sm" disabled={submitting} data-testid="watchlist-create-submit">
        {submitting ? '创建中…' : '创建清单'}
      </Button>
      {error ? (
        <span className="text-sm text-destructive" data-testid="watchlist-create-error">
          {error}
        </span>
      ) : null}
    </form>
  );
}
