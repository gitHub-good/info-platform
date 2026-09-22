import { cn } from 'cn';

interface IndustryFilterProps {
  /** 可选行业集合（由已加载政策 relatedIndustries 去重聚合）。 */
  industries: string[];
  /** 当前选中行业；'' 表示「全部行业」（不发送 industry 参数）。 */
  value: string;
  onChange: (industry: string) => void;
  disabled?: boolean;
}

/**
 * 行业过滤下拉（§4.1.5 industry 过滤）。
 * 选项来自已加载政策的 relatedIndustries 去重；「全部行业」清空 industry 参数。
 * 用原生 <select>：项目无 shadcn Select 原语，原生控件零新依赖、无障碍可用、
 * 暗色 token 与 Input 一致（border-input / bg-input/30 / ring）。
 */
export function IndustryFilter({ industries, value, onChange, disabled }: IndustryFilterProps) {
  const options = ['全部行业', ...industries];
  return (
    <label className="flex items-center gap-2 text-sm text-muted-foreground">
      <span>行业</span>
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        disabled={disabled}
        data-testid="policy-industry-filter"
        className={cn(
          'h-9 rounded-lg border border-input bg-input/30 px-3 text-sm text-foreground shadow-sm transition-colors',
          'focus-visible:border-ring focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50',
          'disabled:cursor-not-allowed disabled:opacity-50',
        )}
      >
        {options.map((opt) => (
          <option key={opt} value={opt === '全部行业' ? '' : opt}>
            {opt}
          </option>
        ))}
      </select>
    </label>
  );
}
