/** 分区保存反馈相位（UI 方案 §4.3 状态机：idle → dirty → saving → saved/error）。 */
export type SaveFeedbackState = 'idle' | 'saving' | 'success' | 'error';

interface SaveFeedbackBarProps {
  state: SaveFeedbackState;
  /** success：逐项枚举生效范围（按接口 effectiveMode 分组）；error：具体原因（ApiError.msg 优先）。 */
  hotFields?: string[];
  restartFields?: string[];
  message?: string;
  testId: string;
}

function effectSummary(hotFields: string[], restartFields: string[]): string {
  // §4.2 红线：禁止「保存成功」却不说明生效范围的静默反馈；逐项枚举
  const parts: string[] = [];
  if (hotFields.length > 0) {
    parts.push(`即时生效：${hotFields.join(' / ')}`);
  }
  if (restartFields.length > 0) {
    parts.push(`${restartFields.join(' / ')} 重启后生效`);
  }
  return parts.length > 0 ? ` · ${parts.join('；')}` : '';
}

/**
 * 分区保存反馈条（T39，UI 方案 §5.2/D4）：saving/success/error 三相，内联常驻（不弹 toast），
 * aria-live="polite" 播报；success 按 effectiveMode 枚举「即时生效 / 重启后生效」字段清单。
 */
export function SaveFeedbackBar({
  state,
  hotFields = [],
  restartFields = [],
  message,
  testId,
}: SaveFeedbackBarProps) {
  if (state === 'idle') {
    return null;
  }
  if (state === 'saving') {
    return (
      <p
        className="text-xs text-muted-foreground"
        aria-live="polite"
        data-testid={testId}
      >
        保存中…
      </p>
    );
  }
  if (state === 'error') {
    return (
      <p
        className="text-xs text-destructive"
        role="alert"
        aria-live="polite"
        data-testid={testId}
      >
        {message ?? '保存失败，请重试'}
      </p>
    );
  }
  return (
    <p
      className="text-xs text-emerald-400"
      aria-live="polite"
      data-testid={testId}
    >
      <span className="font-medium">已保存</span>
      {effectSummary(hotFields, restartFields)}
    </p>
  );
}
