import { useState, type FormEvent } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import type { BriefTypeCode } from '@/types/aibrief';

interface BriefTriggerFormProps {
  submitting: boolean;
  error: string | null;
  /** 上次触发参数（用于展示当前选中态）；无则默认 briefType=1。 */
  briefType?: BriefTypeCode;
  /** 预填标的 ID（来自路由 query，如从 watchlist 跳转）。 */
  defaultSubjectId?: string;
  onTrigger: (subjectId: number | null, briefType: BriefTypeCode) => void;
}

/** 简报类型选项（对齐 §4.1.4 briefType）。 */
const BRIEF_TYPES: { code: BriefTypeCode; label: string; hint: string }[] = [
  { code: 1, label: '个股', hint: '聚合行情/财务/估值/公告/新闻' },
  { code: 2, label: '事件归因', hint: '单一事件对标的的影响' },
  { code: 3, label: '政策解读', hint: '宏观政策倾向判断' },
  { code: 4, label: '每日推荐', hint: '自选池 Top5，无需指定标的' },
];

/**
 * AI 简报触发表单：选标的（内部主键 ID）+ 简报类型 → 触发生成。
 * 与 watchlist AddItemDialog 同约定：标的 ID 为正整数；每日推荐型（4）可留空（后端 subjectId 可空）。
 */
export function BriefTriggerForm({
  submitting,
  error,
  briefType = 1,
  defaultSubjectId = '',
  onTrigger,
}: BriefTriggerFormProps) {
  const [subjectId, setSubjectId] = useState(defaultSubjectId);
  const [type, setType] = useState<BriefTypeCode>(briefType);
  const [validation, setValidation] = useState<string | null>(null);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (submitting) return;
    const sid = Number(subjectId);
    // 个股/事件/政策型须指定正整数标的 ID；每日推荐型可空
    if (type !== 4 && (!Number.isInteger(sid) || sid <= 0)) {
      setValidation('请输入有效的标的 ID（正整数）');
      return;
    }
    setValidation(null);
    const finalSubjectId =
      type === 4 ? (Number.isInteger(sid) && sid > 0 ? sid : null) : sid;
    onTrigger(finalSubjectId, type);
  };

  const typeHint = BRIEF_TYPES.find((t) => t.code === type)?.hint ?? '';

  return (
    <form
      className="flex flex-col gap-3"
      onSubmit={submit}
      data-testid="brief-trigger-form"
    >
      <label className="flex flex-col gap-1 text-sm">
        <span>标的 ID</span>
        <Input
          type="number"
          min={1}
          step={1}
          inputMode="numeric"
          placeholder={type === 4 ? '每日推荐无需指定（可留空）' : '如 1'}
          value={subjectId}
          onChange={(e) => setSubjectId(e.target.value)}
          data-testid="brief-subjectId"
        />
      </label>

      <fieldset className="flex flex-col gap-1 text-sm">
        <legend className="mb-1">简报类型</legend>
        <div className="flex flex-wrap gap-2">
          {BRIEF_TYPES.map((t) => (
            <Button
              key={t.code}
              type="button"
              variant={type === t.code ? 'default' : 'outline'}
              size="sm"
              aria-pressed={type === t.code}
              onClick={() => setType(t.code)}
              data-testid={`brief-type-${t.code}`}
            >
              {t.label}
            </Button>
          ))}
        </div>
        <p className="text-xs text-muted-foreground" data-testid="brief-type-hint">
          {typeHint}
        </p>
      </fieldset>

      {validation ? (
        <p className="text-sm text-destructive" data-testid="brief-validation-error" role="alert">
          {validation}
        </p>
      ) : null}
      {error ? (
        <p className="text-sm text-destructive" data-testid="brief-create-error" role="alert">
          {error}
        </p>
      ) : null}

      <div>
        <Button type="submit" size="sm" disabled={submitting} data-testid="brief-trigger-submit">
          {submitting ? '生成中…' : '生成 AI 简报'}
        </Button>
      </div>
    </form>
  );
}
