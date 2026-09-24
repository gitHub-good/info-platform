import { useCallback, useEffect, useRef, useState } from 'react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { Pagination } from '@/components/ui/pagination';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { ApiError } from '@/api/http';
import { listJobLogsPaged } from '@/api/jobLog';
import type { JobExecutionStatus, JobLogPagedView, JobLogView } from '@/types/jobLog';

/** 每页条数默认值（选项 10/20/50 由 Pagination 提供）。 */
const DEFAULT_PAGE_SIZE = 20;

/** 非 ApiError 兜底文案。 */
function messageOf(err: unknown, fallback: string): string {
  return err instanceof ApiError ? err.msg : fallback;
}

/** 状态徽章：SUCCESS 绿 / FAILED 红 / STARTED 黄（沿用 T22 A 股惯例色 token，文字明示状态）。 */
const STATUS_META: Record<JobExecutionStatus, { label: string; className: string }> = {
  SUCCESS: { label: '成功', className: 'bg-emerald-500/15 text-emerald-400' },
  FAILED: { label: '失败', className: 'bg-rose-500/15 text-rose-400' },
  STARTED: { label: '执行中', className: 'bg-amber-500/15 text-amber-400' },
};

/** ISO-8601 整秒文本 → 本地可读时间（yyyy-MM-dd HH:mm:ss）；空返回 —。 */
function formatTime(iso: string | null): string {
  if (!iso) return '—';
  try {
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return iso;
    const p = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(
      d.getMinutes(),
    )}:${p(d.getSeconds())}`;
  } catch {
    return iso;
  }
}

/** 毫秒 → 可读耗时（<1s 显示 ms，否则 s 保留 2 位）。 */
function formatDuration(ms: number | null): string {
  if (ms == null) return '—';
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(2)}s`;
}

/** 滚回表格顶部（翻页/条数/筛选成功后，UI 设计 §7.1-6）；环境不支持时跳过。 */
function scrollToListTop(el: HTMLElement | null): void {
  if (el && typeof el.scrollIntoView === 'function') {
    el.scrollIntoView({ block: 'start' });
  }
}

interface JobQuery {
  jobName: string;
  status: JobExecutionStatus | null;
  page: number;
  size: number;
}

/**
 * 单次页码查询 + 空页防御回退（UI 设计 §5.3）：
 * 响应 items 空且 total>0 且 page>1 → 页界漂移，以 ceil(total/size) 静默重发一次。
 */
async function fetchJobLogs(
  q: JobQuery,
  signal: AbortSignal,
): Promise<{ data: JobLogPagedView; page: number }> {
  const call = (page: number) =>
    listJobLogsPaged(q.jobName, q.status, page, q.size, signal);
  let data = await call(q.page);
  if (data.items.length === 0 && data.total > 0 && q.page > 1) {
    const last = Math.ceil(data.total / q.size);
    if (last >= 1 && last < q.page) {
      data = await call(last);
      return { data, page: last };
    }
  }
  return { data, page: q.page };
}

interface JobStatusBadgeProps {
  status: JobExecutionStatus;
}

function JobStatusBadge({ status }: JobStatusBadgeProps) {
  const { label, className } = STATUS_META[status] ?? {
    label: status,
    className: 'bg-muted text-muted-foreground',
  };
  return (
    <Badge variant="ghost" className={className} data-testid={`job-status-${status}`}>
      {label}
    </Badge>
  );
}

interface StatusFilterProps {
  value: JobExecutionStatus | null;
  onChange: (status: JobExecutionStatus | null) => void;
  disabled?: boolean;
}

/**
 * 状态分段 4 档（UI 设计 D6）：全部 / 成功 / 失败 / 执行中。
 * 等值过滤 SUCCESS / FAILED / STARTED，「全部」不发 status 参数；
 * 复用 WindowSwitcher 按钮组先例（default 高亮 / outline 未选）。
 */
function StatusFilter({ value, onChange, disabled }: StatusFilterProps) {
  const options: { value: JobExecutionStatus | null; label: string }[] = [
    { value: null, label: '全部' },
    ...(['SUCCESS', 'FAILED', 'STARTED'] as const).map((s) => ({
      value: s,
      label: STATUS_META[s].label,
    })),
  ];
  return (
    <div className="flex items-center gap-1" data-testid="job-status-filter">
      {options.map(({ value: v, label }) => (
        <Button
          key={label}
          variant={v === value ? 'default' : 'outline'}
          size="sm"
          disabled={disabled}
          onClick={() => onChange(v)}
          data-testid={`job-status-filter-${v ?? 'all'}`}
        >
          {label}
        </Button>
      ))}
    </div>
  );
}

interface JobNameFilterProps {
  jobNames: string[];
  value: string;
  onChange: (jobName: string) => void;
  disabled?: boolean;
}

/** Job 名过滤下拉（原生 select，零新依赖、暗色 token 与 Input 一致）。 */
function JobNameFilter({ jobNames, value, onChange, disabled }: JobNameFilterProps) {
  const options = ['全部 Job', ...jobNames];
  return (
    <label className="flex items-center gap-2 text-sm text-muted-foreground">
      <span>Job</span>
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        disabled={disabled}
        data-testid="job-name-filter"
        className="h-9 rounded-lg border border-input bg-input/30 px-3 text-sm text-foreground shadow-sm transition-colors focus-visible:border-ring focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50"
      >
        {options.map((opt) => (
          <option key={opt} value={opt === '全部 Job' ? '' : opt}>
            {opt}
          </option>
        ))}
      </select>
    </label>
  );
}

interface JobLogEmptyProps {
  hasFilter: boolean;
  onClear: () => void;
}

/** 空态（UI 设计 §4.3）：有筛选区分性文案 + 清除筛选 CTA；无筛选维持现状文案。 */
function JobLogEmpty({ hasFilter, onClear }: JobLogEmptyProps) {
  if (!hasFilter) {
    return (
      <div
        className="py-10 text-center text-sm text-muted-foreground"
        data-testid="job-log-empty"
      >
        暂无 Job 执行记录
      </div>
    );
  }
  return (
    <div
      className="flex flex-col items-center gap-2 py-10 text-center"
      data-testid="job-log-empty"
    >
      <p className="text-sm text-foreground">未找到匹配的 Job 执行记录</p>
      <p className="text-xs text-muted-foreground">可调整 Job 或状态筛选后重试</p>
      <Button
        variant="outline"
        size="sm"
        onClick={onClear}
        data-testid="job-log-clear-filters"
      >
        清除筛选
      </Button>
    </div>
  );
}

interface JobLogProps {
  /** URL 参数初始过滤（#/job-logs?jobName=xxx，T38 预留、T41 任务中心「历史」跳转用）。 */
  initialJobName?: string;
}

/**
 * Job 执行日志页（M9 T65 页码分页化，UI 设计 §4 + §5 联动语义）。
 * - 列表：GET /job-logs?jobName&status&page&size（页码分页，默认 20/页）。
 * - 筛选：jobName 下拉（沿用）+ 状态分段 4 档（全部/成功/失败/执行中）；
 *   变更 → page=1 骨架重查；组合过滤交集刷新 total。
 * - URL 预过滤：initialJobName 仅作初始 state（App.tsx key 重挂载消费），不回写不跳转（D9）。
 * - 联动（§5）：与政策页同款——两类 loading 分离、翻页失败保留重试、空页回退、AbortController 单点。
 * 三态：加载骨架 / 空数据引导 / 错误重试；受保护接口 401 由 http 层统一跳 /login。
 */
export function JobLog({ initialJobName = '' }: JobLogProps) {
  const [items, setItems] = useState<JobLogView[]>([]);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  // pageSize 的稳定读取点：loadFirst 保持引用稳定（避免挂载 effect 重跑）
  const pageSizeRef = useRef(DEFAULT_PAGE_SIZE);
  const [total, setTotal] = useState(0);

  const [jobName, setJobName] = useState(initialJobName);
  const [status, setStatus] = useState<JobExecutionStatus | null>(null);
  // jobName 选项全集缓存：仅在无过滤的第 1 页响应聚合，过滤态下拉不随结果收缩（§7.1-5）
  const [allJobNames, setAllJobNames] = useState<string[]>([]);

  const [listLoading, setListLoading] = useState(true);
  const [listError, setListError] = useState<string | null>(null);
  const [pageLoading, setPageLoading] = useState(false);
  // 翻页失败：目标页收进重试闭包，显示态不前跳（§5.2）
  const [pageError, setPageError] = useState<{ page: number; message: string } | null>(
    null,
  );

  // 单一中止点：任何新请求发出前 abort 旧的，响应回填前检查 aborted（§5.4）
  const abortRef = useRef<AbortController | null>(null);
  const sectionRef = useRef<HTMLElement | null>(null);

  /** 骨架通道（首屏/筛选变更 → 新结果集查询，§5.1）。 */
  const loadFirst = useCallback(
    async (nextJobName: string, nextStatus: JobExecutionStatus | null) => {
      abortRef.current?.abort();
      const ctrl = new AbortController();
      abortRef.current = ctrl;
      setListLoading(true);
      setListError(null);
      setPageLoading(false);
      setPageError(null);
      setPage(1);
      try {
        const { data, page: landed } = await fetchJobLogs(
          {
            jobName: nextJobName,
            status: nextStatus,
            page: 1,
            size: pageSizeRef.current,
          },
          ctrl.signal,
        );
        if (ctrl.signal.aborted) return;
        setItems(data.items);
        setTotal(data.total);
        setPage(landed);
        // 无过滤（无 jobName、无状态）首页响应才聚合 jobName 全集
        if (!nextJobName && !nextStatus && landed === 1) {
          setAllJobNames((prev) =>
            Array.from(new Set([...prev, ...data.items.map((i) => i.jobName)])).sort(),
          );
        }
        scrollToListTop(sectionRef.current);
      } catch (err) {
        if (ctrl.signal.aborted) return;
        setListError(messageOf(err, 'Job 日志加载失败'));
      } finally {
        if (!ctrl.signal.aborted) setListLoading(false);
      }
    },
    [],
  );

  // 首查：URL 预过滤仅作初始 state（状态=全部、page=1、size=20），一次组合参数
  useEffect(() => {
    void loadFirst(initialJobName, null);
    return () => abortRef.current?.abort();
  }, [loadFirst, initialJobName]);

  /** 在途保留通道（同筛选条件下的翻页/条数切换，§5.1）。 */
  const fetchPage = useCallback(
    async (target: number, size: number) => {
      abortRef.current?.abort();
      const ctrl = new AbortController();
      abortRef.current = ctrl;
      setPageLoading(true);
      setPageError(null);
      try {
        const { data, page: landed } = await fetchJobLogs(
          { jobName, status, page: target, size },
          ctrl.signal,
        );
        if (ctrl.signal.aborted) return;
        setItems(data.items);
        setTotal(data.total);
        setPage(landed);
        scrollToListTop(sectionRef.current);
      } catch (err) {
        if (ctrl.signal.aborted) return;
        setPageError({ page: target, message: messageOf(err, '加载失败') });
      } finally {
        if (!ctrl.signal.aborted) setPageLoading(false);
      }
    },
    [jobName, status],
  );

  const handleJobNameChange = (next: string) => {
    setJobName(next);
    void loadFirst(next, status);
  };

  const handleStatusChange = (next: JobExecutionStatus | null) => {
    setStatus(next);
    void loadFirst(jobName, next);
  };

  const handlePageChange = (target: number) => {
    if (target === page) return;
    void fetchPage(target, pageSizeRef.current);
  };

  // 条数切换：重置 page=1 再查（PRD 场景 1.3）；同结果集重排 → 保留数据通道（§5.1 注）
  const handlePageSizeChange = (next: number) => {
    if (next === pageSizeRef.current) return;
    pageSizeRef.current = next;
    setPageSize(next);
    void fetchPage(1, next);
  };

  const handleRetryPage = () => {
    if (pageError) void fetchPage(pageError.page, pageSizeRef.current);
  };

  // 清除筛选：只改 state 不回写 URL、不加跳转（入口参数仅初始化用，D9）
  const handleClearFilters = () => {
    setJobName('');
    setStatus(null);
    void loadFirst('', null);
  };

  const handleRetry = () => void loadFirst(jobName, status);

  // 选项：全量缓存 ∪ 当前页日志 jobName（去重排序），过滤后不收缩
  const jobNames = Array.from(
    new Set([...allJobNames, ...items.map((i) => i.jobName)]),
  ).sort();
  const hasFilter = jobName !== '' || status !== null;

  return (
    <main className="mx-auto w-full max-w-6xl p-4 sm:p-6" data-testid="job-log-page">
      <header className="mb-4">
        <h1 className="text-xl font-medium">Job 执行日志</h1>
      </header>

      <div className="mb-4 flex flex-wrap items-center gap-3">
        <JobNameFilter
          jobNames={jobNames}
          value={jobName}
          onChange={handleJobNameChange}
          disabled={listLoading}
        />
        <StatusFilter
          value={status}
          onChange={handleStatusChange}
          disabled={listLoading}
        />
      </div>

      <section ref={sectionRef} data-testid="job-log-section" aria-busy={pageLoading || undefined}>
        {listLoading ? (
          <div className="flex flex-col gap-2" data-testid="job-log-loading">
            {Array.from({ length: 5 }, (_, i) => (
              <Skeleton key={i} className="h-10 w-full" />
            ))}
          </div>
        ) : listError ? (
          <div className="flex flex-col items-start gap-2" data-testid="job-log-error">
            <p className="text-sm text-destructive" role="alert">
              {listError}
            </p>
            <Button
              variant="outline"
              size="sm"
              onClick={handleRetry}
              data-testid="job-log-retry"
            >
              重试
            </Button>
          </div>
        ) : items.length === 0 ? (
          <JobLogEmpty hasFilter={hasFilter} onClear={handleClearFilters} />
        ) : (
          <>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Job</TableHead>
                  <TableHead>开始时间</TableHead>
                  <TableHead>结束时间</TableHead>
                  <TableHead>状态</TableHead>
                  <TableHead>耗时</TableHead>
                  <TableHead>处理/错误</TableHead>
                  <TableHead>异常信息</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {items.map((log) => (
                  <TableRow key={log.id} data-testid={`job-log-row-${log.id}`}>
                    <TableCell className="font-medium">{log.jobName}</TableCell>
                    <TableCell className="whitespace-nowrap">
                      {formatTime(log.startTime)}
                    </TableCell>
                    <TableCell className="whitespace-nowrap">
                      {formatTime(log.endTime)}
                    </TableCell>
                    <TableCell>
                      <JobStatusBadge status={log.status} />
                    </TableCell>
                    <TableCell className="whitespace-nowrap">
                      {formatDuration(log.durationMillis)}
                    </TableCell>
                    <TableCell className="whitespace-nowrap">
                      <span data-testid={`job-log-processed-${log.id}`}>
                        {log.processedCount}
                      </span>
                      {' / '}
                      <span
                        className={
                          log.errorCount > 0 ? 'text-rose-400' : 'text-muted-foreground'
                        }
                      >
                        {log.errorCount}
                      </span>
                    </TableCell>
                    <TableCell
                      className="max-w-[24rem] truncate text-muted-foreground"
                      title={log.errorMessage ?? ''}
                    >
                      {log.errorMessage ?? '—'}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            {pageLoading ? (
              <p
                className="mt-2 text-sm text-muted-foreground"
                aria-live="polite"
                data-testid="job-log-page-loading"
              >
                加载中…
              </p>
            ) : null}
            {pageError ? (
              <div
                className="mt-2 flex flex-wrap items-center gap-2"
                data-testid="job-log-pagination-error"
              >
                <p className="text-sm text-destructive" role="alert">
                  加载第 {pageError.page} 页失败：{pageError.message}
                </p>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={handleRetryPage}
                  data-testid="job-log-pagination-retry"
                >
                  重试
                </Button>
              </div>
            ) : null}
            <Pagination
              page={page}
              pageSize={pageSize}
              total={total}
              disabled={pageLoading}
              onPageChange={handlePageChange}
              onPageSizeChange={handlePageSizeChange}
              label="Job 日志分页"
            />
          </>
        )}
      </section>
    </main>
  );
}

export default JobLog;
