import { cn } from 'cn';

/** 源下拉选项（value = sourceCode）。 */
export interface PolicySourceOption {
  sourceCode: string;
  name: string;
}

interface SourceFilterProps {
  /** 政策 scope 内源（政策类 + 宏观类——显式选源可旁路 scope 口径，ADR-0062 随批 4）。 */
  sources: PolicySourceOption[];
  /** 当前选中源 code；'' 表示「全部源」（不发送 sourceCode 参数）。 */
  value: string;
  onChange: (sourceCode: string) => void;
  disabled?: boolean;
}

/**
 * 源过滤下拉（V2.3-M23 T205，§4.1 sourceCode 追加参数）。
 * 选项由页面从既有 /info-sources 端点取「政策/宏观」分组源下发（复用资讯库源下拉先例，
 * 接口失败静默降级为仅「全部源」，不阻塞列表主路径）。
 */
export function SourceFilter({ sources, value, onChange, disabled }: SourceFilterProps) {
  return (
    <label className="flex items-center gap-2 text-sm text-muted-foreground">
      <span>源</span>
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        disabled={disabled}
        data-testid="policy-source-filter"
        className={cn(
          'h-9 max-w-44 rounded-lg border border-input bg-input/30 px-3 text-sm text-foreground shadow-sm transition-colors',
          'focus-visible:border-ring focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50',
          'disabled:cursor-not-allowed disabled:opacity-50',
        )}
      >
        <option value="">全部源</option>
        {sources.map((source) => (
          <option key={source.sourceCode} value={source.sourceCode}>
            {source.name}
          </option>
        ))}
      </select>
    </label>
  );
}
