// 轻量对话框（shadcn 风格暗色，无 base-ui Dialog 依赖以降低联调风险）。
// 受控：open 控制显隐；Escape / 点遮罩关闭；role=dialog + aria-modal 保证可访问与可测。

import { useEffect, type ReactNode } from 'react';
import { cn } from 'cn';

interface DialogProps {
  open: boolean;
  title: string;
  description?: string;
  onClose: () => void;
  children?: ReactNode;
  /** 底部操作区（取消/确认按钮等）。 */
  footer?: ReactNode;
}

export function Dialog({ open, title, description, onClose, children, footer }: DialogProps) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4" data-testid="dialog">
      <div
        className="absolute inset-0 bg-black/60"
        onClick={onClose}
        data-testid="dialog-backdrop"
        aria-hidden="true"
      />
      <div
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className={cn(
          'relative z-10 w-full max-w-md rounded-xl bg-card p-5 text-card-foreground shadow-xl ring-1 ring-foreground/10',
        )}
      >
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-base font-medium">{title}</h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="关闭"
            className="rounded px-1 text-lg leading-none text-muted-foreground hover:text-foreground"
            data-testid="dialog-close"
          >
            ×
          </button>
        </div>
        {description ? (
          <p className="mb-3 text-sm text-muted-foreground">{description}</p>
        ) : null}
        <div className="flex flex-col gap-3">{children}</div>
        {footer ? (
          <div className="mt-4 flex justify-end gap-2">{footer}</div>
        ) : null}
      </div>
    </div>
  );
}
