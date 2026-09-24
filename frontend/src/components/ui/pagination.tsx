// 通用分页条（M9 T63，UI 设计 §2）：shadcn 风格纯受控组件 + 自研折叠算法，零新依赖。
// - total=0 整条隐藏（return null）；单页收敛为精简态（总数 + 第 1/1 页 + 条数，无翻页按钮）。
// - 页码折叠：总页数 ≤7 全显；>7 时首末页恒显 + 当前页 ±1，差=2 补位、差≥3 插省略号。
// - 在途禁用由页面 pageLoading 传入 disabled（全控件原生禁用）。
// 组成件全部既有（Button + 原生 select + cn），暗色 token 沿用全站基线。

import { ChevronLeft, ChevronRight } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { cn } from 'cn';

/** 折叠序列元素：页码或省略号占位。 */
export type PaginationItem = number | 'ellipsis';

/** 每页条数选项（PRD 场景 1.6：前端只发 10/20/50）。 */
export const DEFAULT_PAGE_SIZE_OPTIONS = [10, 20, 50] as const;

/**
 * 页码折叠纯函数（UI 设计 §2.3，导出供单测）。
 * 规则：总页数 ≤7 全显；否则取 {1, 末页, current±1}，升序遍历——相邻差=2 补中间页码，
 * 差 ≥3 插一个省略号。任何输入下数字按钮 ≤7 个、省略号 ≤2 个（宽度恒定）。
 */
export function getPaginationItems(
  current: number,
  totalPages: number,
): PaginationItem[] {
  if (totalPages <= 0) return [];
  if (totalPages <= 7) {
    return Array.from({ length: totalPages }, (_, i) => i + 1);
  }
  const candidates = new Set<number>([
    1,
    totalPages,
    current - 1,
    current,
    current + 1,
  ]);
  const numbers = [...candidates]
    .filter((n) => n >= 1 && n <= totalPages)
    .sort((a, b) => a - b);

  const items: PaginationItem[] = [numbers[0]];
  for (let i = 1; i < numbers.length; i++) {
    const prev = numbers[i - 1];
    const n = numbers[i];
    if (n - prev === 2) items.push(prev + 1);
    else if (n - prev >= 3) items.push('ellipsis');
    items.push(n);
  }
  return items;
}

export interface PaginationProps {
  /** 当前页（1 起，受控，无内部 state）。 */
  page: number;
  /** 每页条数（受控）。 */
  pageSize: number;
  /** 筛选后总条数（响应 total 字段）。 */
  total: number;
  onPageChange: (page: number) => void;
  onPageSizeChange: (size: number) => void;
  /** 在途禁用（页面 pageLoading 传入 → 全控件 disabled）。 */
  disabled?: boolean;
  pageSizeOptions?: number[];
  /** <nav aria-label> 文案，默认「分页」。 */
  label?: string;
}

/**
 * 列表分页条。纯受控组件：切页/条数只回调，不发请求不存状态；
 * 页码指示与高亮以 min(page, ceil(total/pageSize)) clamp（显示层守卫，UI 设计 §5.3）。
 */
export function Pagination({
  page,
  pageSize,
  total,
  onPageChange,
  onPageSizeChange,
  disabled = false,
  pageSizeOptions = DEFAULT_PAGE_SIZE_OPTIONS,
  label = '分页',
}: PaginationProps) {
  if (total <= 0) return null;

  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  // 显示层 clamp：total 收缩后 page 可能越界，指示与高亮收敛到末页
  const current = Math.min(Math.max(1, page), totalPages);
  const items = getPaginationItems(current, totalPages);
  const singlePage = totalPages === 1;

  const go = (target: number) => {
    if (!disabled && target !== current) onPageChange(target);
  };

  return (
    <nav
      aria-label={label}
      data-testid="pagination-root"
      className="mt-4 flex flex-wrap items-center justify-between gap-x-4 gap-y-2 border-t border-border pt-4"
    >
      <span className="text-sm text-muted-foreground" data-testid="pagination-total">
        共 {total} 条
      </span>
      <div className="flex flex-wrap items-center gap-1">
        <span
          className="mr-2 text-sm text-muted-foreground"
          data-testid="pagination-page-indicator"
        >
          第 {current} / {totalPages} 页
        </span>
        {singlePage ? null : (
          <>
            <Button
              variant="ghost"
              size="icon"
              aria-label="上一页"
              data-testid="pagination-prev"
              disabled={disabled || current <= 1}
              onClick={() => go(current - 1)}
            >
              <ChevronLeft aria-hidden="true" />
            </Button>
            {items.map((item, idx) =>
              item === 'ellipsis' ? (
                <span
                  key={`ellipsis-${idx}`}
                  aria-hidden="true"
                  data-testid="pagination-ellipsis"
                  className="px-1 text-muted-foreground select-none"
                >
                  …
                </span>
              ) : (
                <Button
                  key={item}
                  variant={item === current ? 'default' : 'ghost'}
                  aria-current={item === current ? 'page' : undefined}
                  data-testid={`pagination-page-${item}`}
                  disabled={disabled}
                  onClick={() => go(item)}
                  className="h-8 min-w-8 rounded-[min(var(--radius-md),12px)] px-1.5"
                >
                  {item}
                </Button>
              ),
            )}
            <Button
              variant="ghost"
              size="icon"
              aria-label="下一页"
              data-testid="pagination-next"
              disabled={disabled || current >= totalPages}
              onClick={() => go(current + 1)}
            >
              <ChevronRight aria-hidden="true" />
            </Button>
          </>
        )}
        <label className="ml-2 flex items-center gap-1.5 text-sm text-muted-foreground">
          每页
          <select
            value={pageSize}
            onChange={(e) => {
              if (!disabled) onPageSizeChange(Number(e.target.value));
            }}
            disabled={disabled}
            aria-label="每页条数"
            data-testid="pagination-size"
            className={cn(
              'h-8 rounded-lg border border-input bg-input/30 px-2 text-sm text-foreground shadow-sm transition-colors',
              'focus-visible:border-ring focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50',
              'disabled:cursor-not-allowed disabled:opacity-50',
            )}
          >
            {pageSizeOptions.map((size) => (
              <option key={size} value={size}>
                {size}
              </option>
            ))}
          </select>
          条
        </label>
      </div>
    </nav>
  );
}
