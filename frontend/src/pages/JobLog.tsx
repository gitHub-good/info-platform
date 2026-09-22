import { useCallback, useEffect, useRef, useState } from 'react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { ApiError } from '@/api/http';
import { listJobLogs } from '@/api/jobLog';
import type { JobExecutionStatus, JobLogView } from '@/types/jobLog';

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

interface JobLogProps {
  /** URL 参数初始过滤（#/job-logs?jobName=xxx，T38 预留、T41 任务中心「历史」跳转用）。 */
  initialJobName?: string;
}

/**
 * Job 执行日志页（T33；T38 支持初始过滤）。
 * - 列表：GET /job-logs?jobName=&cursor=（游标分页，每页 20）。
 * - 状态徽章：SUCCESS 绿 / FAILED 红 / STARTED 黄。
 * - 过滤：jobName 下拉（选项来自已加载日志的 jobName 去重）→ 重新拉首页；
 *   挂载时可用 initialJobName 预过滤（URL 参数初始化，改动仅初始取参）。
 * - 分页：nextCursor 存在时「加载更多」追加下一页。
 * 三态：加载骨架 / 空数据引导 / 错误重试；受保护接口 401 由 http 层统一跳 /login。
 */
export function JobLog({ initialJobName = '' }: JobLogProps) {
  const [items, setItems] = useState<JobLogView[]>([]);
  const [nextCursor, setNextCursor] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [jobName, setJobName] = useState(initialJobName);
  const [loadingMore, setLoadingMore] = useState(false);

  // 切换 jobName 过滤时取消在途请求，避免旧响应覆盖新结果
  const abortRef = useRef<AbortController | null>(null);

  const loadFirst = useCallback(async (name: string) => {
    abortRef.current?.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    setLoading(true);
    setError(null);
    try {
      const data = await listJobLogs(name || null, null, ctrl.signal);
      if (ctrl.signal.aborted) return;
      setItems(data.items);
      setNextCursor(data.nextCursor);
    } catch (err) {
      if (ctrl.signal.aborted) return;
      setError(messageOf(err, 'Job 日志加载失败'));
    } finally {
      if (!ctrl.signal.aborted) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadFirst(initialJobName);
    return () => abortRef.current?.abort();
  }, [loadFirst, initialJobName]);

  const handleJobNameChange = (name: string) => {
    setJobName(name);
    void loadFirst(name);
  };

  const handleLoadMore = async () => {
    if (nextCursor == null || loadingMore) return;
    setLoadingMore(true);
    try {
      const data = await listJobLogs(jobName || null, nextCursor);
      setItems((prev) => [...prev, ...data.items]);
      setNextCursor(data.nextCursor);
    } catch (err) {
      setError(messageOf(err, '加载更多失败'));
    } finally {
      setLoadingMore(false);
    }
  };

  const handleRetry = () => void loadFirst(jobName);

  // 选项来自已加载日志的 jobName 去重排序
  const jobNames = Array.from(new Set(items.map((i) => i.jobName))).sort();
  const hasMore = nextCursor != null;

  return (
    <main className="mx-auto w-full max-w-6xl p-4 sm:p-6" data-testid="job-log-page">
      <header className="mb-4">
        <h1 className="text-xl font-medium">Job 执行日志</h1>
      </header>

      <div className="mb-4">
        <JobNameFilter
          jobNames={jobNames}
          value={jobName}
          onChange={handleJobNameChange}
          disabled={loading}
        />
      </div>

      <section data-testid="job-log-section">
        {loading ? (
          <div className="flex flex-col gap-2" data-testid="job-log-loading">
            {Array.from({ length: 5 }, (_, i) => (
              <Skeleton key={i} className="h-10 w-full" />
            ))}
          </div>
        ) : error ? (
          <div className="flex flex-col items-start gap-2" data-testid="job-log-error">
            <p className="text-sm text-destructive" role="alert">
              {error}
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
          <div
            className="py-10 text-center text-sm text-muted-foreground"
            data-testid="job-log-empty"
          >
            暂无 Job 执行记录
          </div>
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
            {hasMore ? (
              <Button
                variant="outline"
                size="sm"
                className="mt-3 w-full"
                onClick={handleLoadMore}
                disabled={loadingMore}
                data-testid="job-log-load-more"
              >
                {loadingMore ? '加载中…' : '加载更多'}
              </Button>
            ) : null}
          </>
        )}
      </section>
    </main>
  );
}

export default JobLog;
