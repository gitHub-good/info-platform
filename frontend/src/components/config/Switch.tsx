import { cn } from 'cn';

interface SwitchProps {
  checked: boolean;
  onCheckedChange: (checked: boolean) => void;
  disabled?: boolean;
  'aria-label'?: string;
  'data-testid'?: string;
}

/**
 * 轻量布尔开关（T39，UI 方案 §5.2/D5）：button + role="switch" + aria-checked，
 * 自研不引 Radix/base-ui Switch（零新依赖，对齐 dialog.tsx 先例）。
 * on：bg-primary；off：bg-muted；h-5 w-9，旋钮滑动过渡。
 */
export function Switch({
  checked,
  onCheckedChange,
  disabled = false,
  'aria-label': ariaLabel,
  'data-testid': testId,
}: SwitchProps) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={ariaLabel}
      disabled={disabled}
      data-testid={testId}
      onClick={() => onCheckedChange(!checked)}
      className={cn(
        'inline-flex h-5 w-9 shrink-0 items-center rounded-full p-0.5 transition-colors',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50',
        checked ? 'bg-primary' : 'bg-muted',
        disabled && 'cursor-not-allowed opacity-50',
      )}
    >
      <span
        aria-hidden="true"
        className={cn(
          'block size-4 rounded-full bg-background shadow transition-transform',
          checked ? 'translate-x-4' : 'translate-x-0',
        )}
      />
    </button>
  );
}
