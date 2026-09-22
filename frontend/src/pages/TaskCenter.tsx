import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '@/api/http';
import { getJobs, patchJob, runJob } from '@/api/taskCenter';
import { Switch } from '@/components/config/Switch';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Dialog } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { navigate } from '@/lib/navigation';
import type { JobConfigUpdate, JobEffectiveMode, JobView } from '@/types/taskCenter';

// —— 常量与工具 ——

/** 轮询间隔（UI 方案 §3.4 交互 4）：存在运行中任务 3s；全部终态 30s 慢轮询保底。 */
const POLL_RUNNING_MS = 3000;
const POLL_IDLE_MS = 30000;

/** 行内提示自动消退时长（触发失败 / 「已生效」短暂反馈）。 */
const NOTE_AUTO_CLEAR_MS = 5000;

function messageOf(err: unknown, fallback: string): string {
  return err instanceof ApiError ? err.msg : fallback;
}

/** 毫秒 → 可读间隔（10s / 30s / 30m / 1h；非整分保留分秒）。 */
function formatInterval(ms: number | null): string {
  if (ms == null || ms <= 0) return '—';
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) return `每 ${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) {
    const rest = seconds % 60;
    return rest === 0 ? `每 ${minutes}m` : `每 ${minutes}m${rest}s`;
  }
  const hours = Math.floor(minutes / 60);
  const restMinutes = minutes % 60;
  return restMinutes === 0 ? `每 ${hours}h` : `每 ${hours}h${restMinutes}m`;
}

/** ISO-8601 文本 → 本地可读时间（MM-dd HH:mm:ss）；空返回 —。 */
function formatTime(iso: string | null): string {
  if (!iso) return '—';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  const p = (n: number) => String(n).padStart(2, '0');
  return `${p(date.getMonth() + 1)}-${p(date.getDate())} ${p(date.getHours())}:${p(date.getMinutes())}:${p(date.getSeconds())}`;
}

/** 毫秒 → 可读耗时（<1s 显示 ms，否则 s 保留 2 位）。 */
function formatDuration(ms: number | null): string {
  if (ms == null) return '—';
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(2)}s`;
}

/** cron 粗校验：须为 6 段空格分隔（与后端 Spring CronExpression 口径一致；如 0 0 9 * * ?）。 */
function cronError(raw: string): string | null {
  const segments = raw.trim().split(/\s+/);
  return segments.length === 6 && segments.every((s) => s.length > 0)
    ? null
    : 'cron 格式不正确（须为 6 段，如 0 0 9 * * ?）';
}

function positiveSecondsError(raw: string): string | null {
  const n = Number(raw);
  return Number.isInteger(n) && n > 0 ? null : '间隔须为正整数（秒）';
}

/** 逗号分隔用户 id 粗校验（空串允许，对齐后端校验器）。 */
function userIdsError(raw: string): string | null {
  return raw.trim() === '' || /^\s*\d+(\s*,\s*\d+)*\s*$/.test(raw) ? null : '须为逗号分隔的用户 id，如 1,2';
}

// —— 上次执行徽章（三色 + 运行中 amber，沿用 JobLog 状态徽章惯例） ——

const STATUS_META: Record<string, { label: string; className: string }> = {
  SUCCESS: { label: '成功', className: 'bg-emerald-500/15 text-emerald-400' },
  FAILED: { label: '失败', className: 'bg-rose-500/15 text-rose-400' },
  STARTED: { label: '执行中', className: 'bg-amber-500/15 text-amber-400' },
};

function LastExecutionCell({ job }: { job: JobView }) {
  if (job.running) {
    return (
      <span className="flex flex-wrap items-center gap-2">
        <Badge
          variant="ghost"
          className="animate-pulse bg-amber-500/15 text-amber-400"
          data-testid={`task-last-${job.jobKey}`}
        >
          运行中
        </Badge>
      </span>
    );
  }
  if (!job.lastExecution) {
    return (
      <span className="text-sm text-muted-foreground" data-testid={`task-last-${job.jobKey}`}>
        从未执行
      </span>
    );
  }
  const meta = STATUS_META[job.lastExecution.status] ?? {
    label: job.lastExecution.status,
    className: 'bg-muted text-muted-foreground',
  };
  return (
    <span className="flex flex-wrap items-center gap-2" data-testid={`task-last-${job.jobKey}`}>
      <span className="text-xs text-muted-foreground">{formatTime(job.lastExecution.startTime)}</span>
      <Badge variant="ghost" className={meta.className}>
        {meta.label}
      </Badge>
      <span className="text-xs text-muted-foreground">
        {formatDuration(job.lastExecution.durationMillis)}
      </span>
    </span>
  );
}

/** 行内生效提示：LIVE_NEXT_CYCLE → amber「下一调度周期生效」（常驻）；启停热切换 → emerald「已生效」（短暂）。 */
function ScheduleNote({ note }: { note: 'done' | 'next-cycle' }) {
  if (note === 'next-cycle') {
    return (
      <Badge
        variant="outline"
        className="bg-amber-500/15 text-amber-400"
        data-testid="task-note-next-cycle"
      >
        下一调度周期生效
      </Badge>
    );
  }
  return (
    <span className="text-xs text-emerald-400" data-testid="task-note-done">
      已生效
    </span>
  );
}

// —— 调度编辑 Dialog（间隔秒数 / cron / 每日推荐 userIds） ——

interface EditDialogProps {
  job: JobView;
  onClose: () => void;
  onSaved: (saved: JobView, changed: string[]) => void;
  onError: (msg: string) => void;
}

function ScheduleEditDialog({ job, onClose, onSaved, onError }: EditDialogProps) {
  const isCron = job.scheduleType === 'CRON';
  const showUserIds = job.jobKey === 'DAILY_RECOMMEND';
  const [intervalSeconds, setIntervalSeconds] = useState(
    job.intervalMillis != null ? String(Math.round(job.intervalMillis / 1000)) : '',
  );
  const [cron, setCron] = useState(job.cron ?? '');
  const [userIds, setUserIds] = useState(job.userIds ?? '');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const dirty = isCron
    ? cron.trim() !== (job.cron ?? '') || (showUserIds && userIds.trim() !== (job.userIds ?? ''))
    : Number(intervalSeconds) * 1000 !== job.intervalMillis;

  const handleSave = async () => {
    const validation = isCron ? cronError(cron) : positiveSecondsError(intervalSeconds);
    const usersValidation = showUserIds ? userIdsError(userIds) : null;
    setError(validation ?? usersValidation);
    if (validation || usersValidation || !dirty) {
      return;
    }
    const body: JobConfigUpdate = { expectedUpdatedAt: job.updatedAt ?? undefined };
    const changed: string[] = [];
    if (isCron) {
      if (cron.trim() !== (job.cron ?? '')) {
        body.cron = cron.trim();
        changed.push('cron');
      }
      if (showUserIds && userIds.trim() !== (job.userIds ?? '')) {
        body.userIds = userIds.trim();
        changed.push('userIds');
      }
    } else if (Number(intervalSeconds) * 1000 !== job.intervalMillis) {
      body.intervalMillis = Number(intervalSeconds) * 1000;
      changed.push('intervalMillis');
    }
    setSaving(true);
    try {
      const saved = await patchJob(job.jobKey, body);
      onSaved(saved, changed);
      onClose();
    } catch (err) {
      const msg = messageOf(err, '保存失败，请重试');
      setError(null); // 字段级错误与后端文案分开展示：后端原因进行内反馈
      onError(msg);
      onClose();
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog
      open
      title={`编辑调度 · ${job.name}`}
      description={
        isCron
          ? 'cron 表达式（6 段 Spring cron，如 0 0 9 * * ?）；保存后自下一调度周期生效。'
          : '轮询间隔（秒）；保存后自下一调度周期生效，进行中的一轮会跑完。'
      }
      onClose={onClose}
      footer={
        <>
          <Button variant="outline" size="sm" onClick={onClose}>
            取消
          </Button>
          <Button
            size="sm"
            disabled={!dirty || saving}
            onClick={() => void handleSave()}
            data-testid={`task-edit-save-${job.jobKey}`}
          >
            {saving ? '保存中…' : '保存'}
          </Button>
        </>
      }
    >
      {isCron ? (
        <label className="flex flex-col gap-1 text-sm">
          <span>cron 表达式</span>
          <Input
            value={cron}
            onChange={(e) => setCron(e.target.value)}
            aria-label="cron 表达式"
            data-testid={`task-edit-cron-${job.jobKey}`}
          />
          {error ? (
            <span className="text-xs text-destructive" role="alert">
              {error}
            </span>
          ) : null}
        </label>
      ) : (
        <label className="flex flex-col gap-1 text-sm">
          <span>间隔（秒）</span>
          <Input
            value={intervalSeconds}
            onChange={(e) => setIntervalSeconds(e.target.value)}
            aria-label="轮询间隔秒数"
            inputMode="numeric"
            data-testid={`task-edit-interval-${job.jobKey}`}
          />
          {error ? (
            <span className="text-xs text-destructive" role="alert">
              {error}
            </span>
          ) : null}
        </label>
      )}
      {showUserIds ? (
        <label className="flex flex-col gap-1 text-sm">
          <span>预热用户 id（逗号分隔，可留空）</span>
          <Input
            value={userIds}
            onChange={(e) => setUserIds(e.target.value)}
            aria-label="预热用户 id 列表"
            data-testid={`task-edit-userids-${job.jobKey}`}
          />
        </label>
      ) : null}
    </Dialog>
  );
}

// —— 单行 ——

interface TaskRowProps {
  job: JobView;
  note: 'done' | 'next-cycle' | null;
  rowError: string | null;
  triggering: boolean;
  onToggle: (job: JobView, next: boolean) => void;
  onEdit: (job: JobView) => void;
  onRun: (job: JobView) => void;
}

function TaskRow({ job, note, rowError, triggering, onToggle, onEdit, onRun }: TaskRowProps) {
  const scheduleText =
    job.scheduleType === 'CRON'
      ? `cron ${job.cron ?? '—'}`
      : formatInterval(job.intervalMillis);
  return (
    <TableRow data-testid={`task-row-${job.jobKey}`}>
      <TableCell className="py-3">
        <div className="flex flex-col">
          <span className="font-medium">{job.name}</span>
          <span className="text-xs text-muted-foreground">{job.description}</span>
        </div>
      </TableCell>
      <TableCell className="py-3">
        <div className="flex flex-col items-start gap-1">
          <div className="flex items-center gap-2">
            <Switch
              checked={job.enabled}
              onCheckedChange={(next) => onToggle(job, next)}
              disabled={triggering}
              aria-label={`启用 ${job.name}`}
              data-testid={`task-enabled-${job.jobKey}`}
            />
            <span className="text-sm text-muted-foreground" data-testid={`task-schedule-${job.jobKey}`}>
              {scheduleText}
            </span>
            <Button
              variant="outline"
              size="sm"
              onClick={() => onEdit(job)}
              data-testid={`task-edit-${job.jobKey}`}
            >
              编辑调度
            </Button>
          </div>
          {note ? <ScheduleNote note={note} /> : null}
        </div>
      </TableCell>
      <TableCell className="py-3">
        <LastExecutionCell job={job} />
      </TableCell>
      <TableCell className="py-3">
        <div className="flex flex-col">
          <span className="text-sm" data-testid={`task-next-${job.jobKey}`}>
            {job.enabled ? formatTime(job.nextExecutionTime) : '—'}
          </span>
          {!job.enabled ? (
            <span className="text-xs text-muted-foreground">已停用</span>
          ) : null}
        </div>
      </TableCell>
      <TableCell className="py-3">
        <div className="flex flex-col items-start gap-1">
          <div className="flex items-center gap-2">
            <Button
              size="sm"
              disabled={job.running || triggering}
              onClick={() => onRun(job)}
              data-testid={`task-run-${job.jobKey}`}
            >
              {job.running ? '运行中…' : triggering ? '触发中…' : '立即执行'}
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() => navigate(`/job-logs?jobName=${encodeURIComponent(job.jobName)}`)}
              data-testid={`task-history-${job.jobKey}`}
            >
              历史
            </Button>
          </div>
          {rowError ? (
            <span className="text-xs text-destructive" role="alert" data-testid={`task-run-error-${job.jobKey}`}>
              {rowError}
            </span>
          ) : null}
        </div>
      </TableCell>
    </TableRow>
  );
}

// —— 页面 ——

/**
 * 任务执行中心页（T41，#/task-center，UI 方案 §3.4）。
 * Table 5 列（任务/调度/上次执行/下次执行/操作）：Switch 启停（停用二次确认）、编辑调度 Dialog、
 * 立即执行（防重入置灰「运行中…」，后端 30063 收敛同态不报错）、历史跳 Job 日志预过滤（?jobName=）。
 * 轮询：运行中 3s / 空闲 30s / document.hidden 暂停、恢复可见立即刷一次；触发成功立即单次刷新。
 * 三态：5 行骨架 / 「暂无注册任务」空态 / 整页错误重试；轮询失败静默降级不清数据。
 */
export function TaskCenter() {
  const [jobs, setJobs] = useState<JobView[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notes, setNotes] = useState<Record<string, 'done' | 'next-cycle'>>({});
  const [rowErrors, setRowErrors] = useState<Record<string, string>>({});
  const [triggering, setTriggering] = useState<Record<string, boolean>>({});
  const [editJob, setEditJob] = useState<JobView | null>(null);
  const [stopConfirm, setStopConfirm] = useState<JobView | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  // 轮询周期随运行态切换（作为 interval effect 的依赖触发重建）
  const [hasRunning, setHasRunning] = useState(false);
  const noteTimers = useRef<Record<string, number>>({});

  const applyJobs = useCallback((next: JobView[]) => {
    setJobs(next);
    setHasRunning(next.some((job) => job.running));
  }, []);

  const refresh = useCallback(
    async (silent: boolean) => {
      if (silent) {
        // 静默轮询：失败降级不清已展示数据（UI 方案 §3.4 三态）
        try {
          const data = await getJobs();
          applyJobs(data.jobs);
        } catch {
          // 下次轮询重试
        }
        return;
      }
      abortRef.current?.abort();
      const ctrl = new AbortController();
      abortRef.current = ctrl;
      setLoading(true);
      setError(null);
      try {
        const data = await getJobs(ctrl.signal);
        if (ctrl.signal.aborted) return;
        applyJobs(data.jobs);
      } catch (err) {
        if (ctrl.signal.aborted) return;
        setError(messageOf(err, '任务列表加载失败'));
      } finally {
        if (!ctrl.signal.aborted) setLoading(false);
      }
    },
    [applyJobs],
  );

  useEffect(() => {
    void refresh(false);
    return () => abortRef.current?.abort();
  }, [refresh]);

  // 轮询节流（交互 4）：运行中 3s / 空闲 30s；document.hidden 暂停
  useEffect(() => {
    const timer = window.setInterval(
      () => {
        if (document.hidden) return;
        void refresh(true);
      },
      hasRunning ? POLL_RUNNING_MS : POLL_IDLE_MS,
    );
    return () => window.clearInterval(timer);
  }, [hasRunning, refresh]);

  // 恢复可见立即刷新一次（交互 4）
  useEffect(() => {
    const onVisibility = () => {
      if (!document.hidden) void refresh(true);
    };
    document.addEventListener('visibilitychange', onVisibility);
    return () => document.removeEventListener('visibilitychange', onVisibility);
  }, [refresh]);

  const setNote = (jobKey: string, note: 'done' | 'next-cycle') => {
    setNotes((prev) => ({ ...prev, [jobKey]: note }));
    if (note === 'done') {
      window.clearTimeout(noteTimers.current[jobKey]);
      noteTimers.current[jobKey] = window.setTimeout(() => {
        setNotes((prev) => {
          const next = { ...prev };
          delete next[jobKey];
          return next;
        });
      }, NOTE_AUTO_CLEAR_MS);
    }
  };

  const setRowError = (jobKey: string, msg: string) => {
    setRowErrors((prev) => ({ ...prev, [jobKey]: msg }));
    window.setTimeout(() => {
      setRowErrors((prev) => {
        const next = { ...prev };
        delete next[jobKey];
        return next;
      });
    }, NOTE_AUTO_CLEAR_MS);
  };

  const applySaved = (saved: JobView, changed: string[]) => {
    setJobs((prev) => prev?.map((job) => (job.jobKey === saved.jobKey ? saved : job)) ?? prev);
    // 生效方式按接口 effectiveMode 渲染（UI 方案 §4.2/D3）：LIVE_NEXT_CYCLE 常驻明示，LIVE 短暂「已生效」
    const modes: Record<string, JobEffectiveMode> = saved.effectiveModes ?? {};
    const nextCycle = changed.some((field) => modes[field] === 'LIVE_NEXT_CYCLE');
    setNote(saved.jobKey, nextCycle ? 'next-cycle' : 'done');
    void refresh(true);
  };

  const handleToggle = (job: JobView, next: boolean) => {
    if (!next) {
      // 停用二次确认（UI 方案 §3.4 交互 7）
      setStopConfirm(job);
      return;
    }
    void doSave(job, { enabled: true }, ['enabled']);
  };

  const doSave = async (job: JobView, body: JobConfigUpdate, changed: string[]) => {
    try {
      const saved = await patchJob(job.jobKey, { ...body, expectedUpdatedAt: job.updatedAt ?? undefined });
      applySaved(saved, changed);
    } catch (err) {
      setRowError(job.jobKey, messageOf(err, '保存失败，请重试'));
    }
  };

  const handleRun = async (job: JobView) => {
    setTriggering((prev) => ({ ...prev, [job.jobKey]: true }));
    try {
      await runJob(job.jobKey);
    } catch (err) {
      if (err instanceof ApiError && err.code === 30063) {
        // 防重入（交互 3 双重保险）：后端「已在运行」收敛为运行中态，不弹错误
        void refresh(true);
        return;
      }
      setRowError(job.jobKey, messageOf(err, '触发失败，请重试'));
      return;
    } finally {
      setTriggering((prev) => ({ ...prev, [job.jobKey]: false }));
    }
    // 触发成功：立即单次刷新不等下个周期（交互 4）
    void refresh(true);
  };

  return (
    <main className="mx-auto w-full max-w-6xl p-4 sm:p-6" data-testid="task-center-page">
      <header className="mb-4">
        <h1 className="text-xl font-medium">任务执行中心</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          定时任务可手动触发、实时看状态；执行明细见 Job 日志
        </p>
      </header>

      {loading ? (
        <div className="flex flex-col gap-2" data-testid="task-center-loading">
          {Array.from({ length: 5 }, (_, i) => (
            <Skeleton key={i} className="h-12 w-full" />
          ))}
        </div>
      ) : error ? (
        <div className="flex flex-col items-start gap-2" data-testid="task-center-error">
          <p className="text-sm text-destructive" role="alert">
            {error}
          </p>
          <Button
            variant="outline"
            size="sm"
            onClick={() => void refresh(false)}
            data-testid="task-center-retry"
          >
            重试
          </Button>
        </div>
      ) : jobs == null || jobs.length === 0 ? (
        <p className="py-10 text-center text-sm text-muted-foreground" data-testid="task-center-empty">
          暂无注册任务
        </p>
      ) : (
        <Table data-testid="task-center-table">
          <TableHeader>
            <TableRow>
              <TableHead className="w-44">任务</TableHead>
              <TableHead>调度</TableHead>
              <TableHead>上次执行</TableHead>
              <TableHead className="w-40">下次执行</TableHead>
              <TableHead className="w-56">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {jobs.map((job) => (
              <TaskRow
                key={job.jobKey}
                job={job}
                note={notes[job.jobKey] ?? null}
                rowError={rowErrors[job.jobKey] ?? null}
                triggering={triggering[job.jobKey] ?? false}
                onToggle={handleToggle}
                onEdit={setEditJob}
                onRun={(target) => void handleRun(target)}
              />
            ))}
          </TableBody>
        </Table>
      )}

      {editJob ? (
        <ScheduleEditDialog
          job={editJob}
          onClose={() => setEditJob(null)}
          onSaved={applySaved}
          onError={(msg) => setRowError(editJob.jobKey, msg)}
        />
      ) : null}

      <Dialog
        open={stopConfirm != null}
        title={`停用 ${stopConfirm?.name ?? ''}`}
        description="停用后不再自动调度（立即生效），仍可手动触发。确认停用？"
        onClose={() => setStopConfirm(null)}
        footer={
          <>
            <Button variant="outline" size="sm" onClick={() => setStopConfirm(null)}>
              取消
            </Button>
            <Button
              size="sm"
              onClick={() => {
                const target = stopConfirm;
                setStopConfirm(null);
                if (target) void doSave(target, { enabled: false }, ['enabled']);
              }}
              data-testid={`task-stop-confirm-${stopConfirm?.jobKey ?? ''}`}
            >
              确认停用
            </Button>
          </>
        }
      />
    </main>
  );
}

export default TaskCenter;
