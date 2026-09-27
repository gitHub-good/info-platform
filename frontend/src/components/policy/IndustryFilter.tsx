import { cn } from 'cn';

interface IndustryFilterProps {
  /** 可选行业集合（V2.3 T205 起为静态 L1 口径枚举：申万 31 + 监管·政策 容器）。 */
  industries: readonly string[];
  /** 当前选中行业；'' 表示「全部行业」（不发送 industry 参数）。 */
  value: string;
  onChange: (industry: string) => void;
  disabled?: boolean;
}

/**
 * 行业过滤下拉（§4.1 industry 过滤，L1 口径）。
 * 选项为静态枚举全集（POLICY_INDUSTRY_OPTIONS，后端 IndustryCategory 镜像）——
 * 旧行业字典（响应 relatedIndustries 去重聚合）随轨 B 退役（3 行业字典 35 条 29 空 → 31+容器全量可选）。
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
