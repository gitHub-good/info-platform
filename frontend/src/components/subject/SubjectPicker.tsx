import { useCallback, useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { Badge } from '@/components/ui/badge';
import { Input } from '@/components/ui/input';
import { searchSubjects, type SubjectSummary } from '@/api/subject';

/** 输入联想防抖（毫秒）：避免每个按键打一次搜索接口。 */
const DEFAULT_DEBOUNCE_MS = 300;
/** 下拉一次拉取条数（与后端 search 默认一致）。 */
const SEARCH_LIMIT = 20;

/** 下拉面板状态：初始无面板 / 搜索中 / 无结果 / 结果列表 / 失败（可重试）。 */
type PanelState =
  | { kind: 'closed' }
  | { kind: 'loading' }
  | { kind: 'empty' }
  | { kind: 'results'; options: SubjectSummary[] }
  | { kind: 'error' };

interface SubjectPickerProps {
  /** 已选标的（受控）；null 表示未选。 */
  value: SubjectSummary | null;
  onChange: (subject: SubjectSummary | null) => void;
  placeholder?: string;
  disabled?: boolean;
  /** 防抖毫秒数（默认 300；测试可缩短加速）。 */
  debounceMs?: number;
  /** 测试锚点前缀：生成 `${testId}-input` / `-option-${id}` / `-empty` / `-error` / `-retry` / `-selected`。 */
  testId?: string;
}

/**
 * 标的搜索选择器（体检 P1-2）：输入联想（防抖 300ms 调 /subjects/search）→ 下拉列表
 * （代码/名称/行业徽章）→ 选中回填；键盘 ↑↓ 移动高亮 + Enter 确认 + Escape 关闭；
 * 三态兜底：搜索中 / 无结果「未找到匹配标的」/ 失败可重试。
 *
 * 受控组件：value/onChange 暴露完整选中标的对象（含数字主键 id，供下游接口直接使用）。
 */
export function SubjectPicker({
  value,
  onChange,
  placeholder = '输入代码或名称搜索，如 600519 / 茅台',
  disabled = false,
  debounceMs = DEFAULT_DEBOUNCE_MS,
  testId = 'subject-picker',
}: SubjectPickerProps) {
  const [query, setQuery] = useState('');
  const [panel, setPanel] = useState<PanelState>({ kind: 'closed' });
  /** 键盘高亮行下标（-1 = 无高亮）。 */
  const [activeIndex, setActiveIndex] = useState(-1);
  /** 最近一次发起的查询词（失败重试用）。 */
  const lastQueryRef = useRef('');
  /** 当前在途搜索请求（新请求发起前中止旧请求，响应过期即忽略）。 */
  const inFlightRef = useRef<AbortController | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  /** 发起一次搜索（去重：同词连续触发不叠加请求）。 */
  const runSearch = useCallback((keyword: string) => {
    inFlightRef.current?.abort();
    const controller = new AbortController();
    inFlightRef.current = controller;
    setPanel({ kind: 'loading' });
    searchSubjects(keyword, { limit: SEARCH_LIMIT, signal: controller.signal })
      .then((options) => {
        if (inFlightRef.current !== controller) return; // 已被更新请求取代
        setPanel(options.length === 0 ? { kind: 'empty' } : { kind: 'results', options });
        setActiveIndex(options.length === 0 ? -1 : 0);
      })
      .catch(() => {
        if (inFlightRef.current !== controller) return; // 主动中止不算失败
        setPanel({ kind: 'error' });
      });
  }, []);

  // 输入变化 → 防抖搜索；清空输入即收起面板（卸载时清理挂起定时器）
  useEffect(() => {
    const keyword = query.trim();
    if (!keyword) {
      setPanel({ kind: 'closed' });
      setActiveIndex(-1);
      return;
    }
    const timer = setTimeout(() => {
      lastQueryRef.current = keyword;
      runSearch(keyword);
    }, debounceMs);
    return () => clearTimeout(timer);
  }, [query, debounceMs, runSearch]);

  // 卸载时中止在途请求
  useEffect(() => () => inFlightRef.current?.abort(), []);

  // 点击组件外部关闭下拉
  useEffect(() => {
    const onPointerDown = (e: PointerEvent) => {
      if (!containerRef.current?.contains(e.target as Node)) {
        setPanel({ kind: 'closed' });
      }
    };
    document.addEventListener('pointerdown', onPointerDown);
    return () => document.removeEventListener('pointerdown', onPointerDown);
  }, []);

  const select = (subject: SubjectSummary) => {
    onChange(subject);
    setQuery('');
    setPanel({ kind: 'closed' });
    setActiveIndex(-1);
  };

  const options = panel.kind === 'results' ? panel.options : [];

  const handleKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (options.length === 0) return;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setActiveIndex((i) => (i + 1) % options.length);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActiveIndex((i) => (i - 1 + options.length) % options.length);
    } else if (e.key === 'Enter' && activeIndex >= 0 && activeIndex < options.length) {
      e.preventDefault();
      select(options[activeIndex]);
    } else if (e.key === 'Escape') {
      setPanel({ kind: 'closed' });
    }
  };

  /** 失败重试：以最近一次查询词立即重放（绕过防抖）。 */
  const retry = () => {
    if (lastQueryRef.current) runSearch(lastQueryRef.current);
  };

  // 已选态：展示代码+名称徽章，可一键清除重选
  if (value) {
    return (
      <div
        className="flex h-9 w-full items-center gap-2 rounded-lg border border-input bg-input/30 px-3"
        data-testid={`${testId}-selected`}
      >
        <span className="truncate text-sm">
          <span className="font-medium">{value.subjectCode}</span>
          <span className="ml-2 text-muted-foreground">{value.name}</span>
        </span>
        {value.industry ? (
          <Badge variant="secondary" className="shrink-0">
            {value.industry}
          </Badge>
        ) : null}
        <button
          type="button"
          aria-label="清除已选标的"
          disabled={disabled}
          onClick={() => onChange(null)}
          className="ml-auto shrink-0 rounded px-1 text-muted-foreground hover:text-foreground disabled:cursor-not-allowed disabled:opacity-50"
          data-testid={`${testId}-clear`}
        >
          ×
        </button>
      </div>
    );
  }

  return (
    <div className="relative" ref={containerRef}>
      <Input
        type="text"
        role="combobox"
        aria-expanded={panel.kind === 'results'}
        aria-autocomplete="list"
        placeholder={placeholder}
        disabled={disabled}
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        onKeyDown={handleKeyDown}
        data-testid={`${testId}-input`}
      />
      {panel.kind !== 'closed' ? (
        <ul
          className="absolute z-10 mt-1 max-h-64 w-full overflow-auto rounded-lg border border-border bg-popover p-1 text-sm shadow-lg"
          role="listbox"
          data-testid={`${testId}-panel`}
        >
          {panel.kind === 'loading' ? (
            <li className="px-3 py-2 text-muted-foreground" data-testid={`${testId}-loading`}>
              搜索中…
            </li>
          ) : null}
          {panel.kind === 'empty' ? (
            <li className="px-3 py-2 text-muted-foreground" data-testid={`${testId}-empty`}>
              未找到匹配标的
            </li>
          ) : null}
          {panel.kind === 'error' ? (
            <li className="flex items-center justify-between gap-2 px-3 py-2">
              <span className="text-destructive" data-testid={`${testId}-error`}>
                搜索失败
              </span>
              <button
                type="button"
                className="text-primary hover:underline"
                onClick={retry}
                data-testid={`${testId}-retry`}
              >
                重试
              </button>
            </li>
          ) : null}
          {options.map((subject, index) => (
            <li key={subject.id} role="option" aria-selected={index === activeIndex}>
              <button
                type="button"
                className={`flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left ${
                  index === activeIndex ? 'bg-accent text-accent-foreground' : 'hover:bg-muted'
                }`}
                onMouseEnter={() => setActiveIndex(index)}
                onClick={() => select(subject)}
                data-testid={`${testId}-option-${subject.id}`}
              >
                <span className="w-24 shrink-0 font-medium">{subject.subjectCode}</span>
                <span className="truncate">{subject.name}</span>
                {subject.industry ? (
                  <Badge variant="secondary" className="ml-auto shrink-0">
                    {subject.industry}
                  </Badge>
                ) : null}
              </button>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
