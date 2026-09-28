import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '@/api/http';
import { getRetentionWindows, patchRetentionWindows } from '@/api/retention';
import { getScoreWeights, patchScoreWeights } from '@/api/valueScore';
import { getMarketTopConfig, patchMarketTopConfig } from '@/api/marketTop';
import { getJobs, patchJob, runJob } from '@/api/taskCenter';
import { Switch } from '@/components/config/Switch';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Dialog } from '@/components/ui/dialog';
import { EmptyState } from '@/components/ui/EmptyState';
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
import type { RetentionFieldLimits, RetentionWindowField } from '@/types/retention';
import type { ScoreWeightField, ScoreWeightsView } from '@/types/valueScore';
import type { MarketTopConfigField, MarketTopConfigView } from '@/types/marketTop';
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

// —— RETENTION_CLEANUP 保留窗口分组（T73，M10 技术方案增补 §3.5：页面载体=任务中心编辑 Dialog） ——

/** 留痕清理任务键（窗口分组仅该任务显示，对齐 userIds 仅 DAILY_RECOMMEND 的条件渲染先例）。 */
const RETENTION_JOB_KEY = 'RETENTION_CLEANUP';

/** 窗口字段元数据：含义文案（REQ 场景 5 口径交前端静态维护）+ 静态下限兜底（GET limits 优先）。newsItemDays 为 M14 T113 扩键、recommendationCardDays 为 M16 T134 扩键（推荐两表共窗）。 */
const RETENTION_FIELDS: { field: RetentionWindowField; label: string; fallbackMin: number }[] = [
  { field: 'jobExecutionLogDays', label: 'Job 执行日志保留天数', fallbackMin: 7 },
  { field: 'dataSourceEventDays', label: '数据源事件保留天数', fallbackMin: 2 },
  { field: 'llmCallLogDays', label: 'LLM 调用日志保留天数', fallbackMin: 35 },
  { field: 'readingEventDays', label: '阅读行为保留天数', fallbackMin: 35 },
  { field: 'newsItemDays', label: '资讯条目保留天数', fallbackMin: 30 },
  { field: 'recommendationCardDays', label: '推荐卡片与反馈保留天数', fallbackMin: 30 },
];

/** 窗口字段粗校验：整型且 ≥ 下限（对齐后端 RetentionConfigValidator 下限口径）。 */
function windowDaysError(label: string, raw: string, min: number): string | null {
  const n = Number(raw);
  return Number.isInteger(n) && n >= min ? null : `${label}须为 ≥ ${min} 的整数`;
}

// —— FACTOR_SNAPSHOT 评分权重分组（T172，M20 方案 §4.7.3 + ADR-0058 裁决 4：编辑面挂任务中心 Dialog 零新页） ——

/** 因子快照任务键（权重分组仅该任务显示，对齐 RETENTION 窗口分组的条件渲染先例）。 */
const FACTOR_JOB_KEY = 'FACTOR_SNAPSHOT';

/** 五维权重字段（0~1；合成自动归一，Σ 须 > 0 且 ≤ 1.05 归一容差——对齐后端 ValuationConfigValidator）。 */
const WEIGHT_FIELDS: { field: ScoreWeightField; label: string }[] = [
  { field: 'wCatalyst', label: '事件催化权重' },
  { field: 'wConduction', label: '行业传导权重' },
  { field: 'wFundamental', label: '基本面边际权重' },
  { field: 'wRisk', label: '风险安全权重' },
  { field: 'wValuation', label: '估值水平权重' },
];

/** 「有突破」三阈值字段（0~100 整数）。 */
const THRESHOLD_FIELDS: { field: ScoreWeightField; label: string }[] = [
  { field: 'btCatalystMin', label: '「有突破」事件催化下限' },
  { field: 'btConductionMin', label: '「有突破」行业传导下限' },
  { field: 'btRiskMin', label: '「有突破」风险安全下限' },
];

const SCORE_WEIGHT_FIELDS = [...WEIGHT_FIELDS, ...THRESHOLD_FIELDS];

/** 权重和归一容差上界（对齐后端 30087 拦截口径，提交前先拦）。 */
const WEIGHT_SUM_TOLERANCE = 1.05;

/** 权重字段粗校验：数值 0~1（对齐后端校验器区间）。 */
function weightError(label: string, raw: string): string | null {
  const n = Number(raw);
  return Number.isFinite(n) && n >= 0 && n <= 1 ? null : `${label}须为 0 ~ 1 的数值`;
}

/** 阈值字段粗校验：0~100 整数。 */
function thresholdError(label: string, raw: string): string | null {
  const n = Number(raw);
  return Number.isInteger(n) && n >= 0 && n <= 100 ? null : `${label}须为 0 ~ 100 的整数`;
}

/** 五维权重和校验（Σ=0 除零 / Σ>1.05 视为误配——合成按 Σ 归一）。 */
function weightSumError(values: Record<ScoreWeightField, string>): string | null {
  const sum = WEIGHT_FIELDS.reduce((acc, { field }) => acc + Number(values[field] || 0), 0);
  const rounded = Math.round(sum * 1000) / 1000;
  if (rounded <= 0) return '五维权重和须 > 0（全零无意义）';
  if (rounded > WEIGHT_SUM_TOLERANCE) return `五维权重和须 ≤ ${WEIGHT_SUM_TOLERANCE}（当前 ${rounded}）`;
  return null;
}

/** GET 视图 → 八个可编辑字段字符串态（预填与 dirty 基线同构）。 */
function weightValuesOf(view: ScoreWeightsView): Record<ScoreWeightField, string> {
  return {
    wCatalyst: String(view.wCatalyst),
    wConduction: String(view.wConduction),
    wFundamental: String(view.wFundamental),
    wRisk: String(view.wRisk),
    wValuation: String(view.wValuation),
    btCatalystMin: String(view.btCatalystMin),
    btConductionMin: String(view.btConductionMin),
    btRiskMin: String(view.btRiskMin),
  };
}

// —— MARKET_TOP_JOB 漏斗配置分组（M21 T181，方案 §4.7.3：FACTOR_SNAPSHOT 权重 Dialog 同款先例，零新页面） ——

/** 榜单任务键（漏斗配置分组仅该任务显示；Job 本体随 T183 收编，行出现前分组自然不渲染）。 */
const MARKET_TOP_JOB_KEY = 'MARKET_TOP_JOB';

/** 漏斗配置字段元数据：含义 + 区间（对齐后端 MarketTopConfigValidator 30091 拦截口径）。 */
const MARKET_TOP_FIELDS: {
  field: MarketTopConfigField;
  label: string;
  kind: 'int' | 'decimal';
  min: number;
  max: number;
  hint: string;
}[] = [
  { field: 'poolSize', label: '粗筛池大小', kind: 'int', min: 100, max: 800, hint: '全量快照 → 池收敛 ≤10%' },
  {
    field: 'deepDiveLimit',
    label: 'LLM 深析候选数',
    kind: 'int',
    min: 30,
    max: 50,
    hint: '蓝图区间硬校验（防全量逐股）',
  },
  {
    field: 'deepDiveCostCapRatio',
    label: '深析成本护栏占比',
    kind: 'decimal',
    min: 0.05,
    max: 1.0,
    hint: 'scene-10 占管道日预算上限',
  },
  {
    field: 'diveCostEstimateMicros',
    label: '单次深析成本预估（μ¥）',
    kind: 'int',
    min: 1,
    max: 100000000,
    hint: '预检口径保守值',
  },
  {
    field: 'memberCoverageFloor',
    label: '行业成员覆盖率阈值',
    kind: 'decimal',
    min: 0,
    max: 1,
    hint: '低于即触发阶段 0 回填',
  },
];

/** 漏斗配置字段粗校验：整型/小数按区间（对齐后端 30091 字段级拦截，提交前先拦）。 */
function marketTopValueError(
  label: string,
  raw: string,
  kind: 'int' | 'decimal',
  min: number,
  max: number,
): string | null {
  const n = Number(raw);
  if (!Number.isFinite(n) || n < min || n > max) {
    return `${label}须为 ${min} ~ ${max} 的${kind === 'int' ? '整数' : '数值'}`;
  }
  if (kind === 'int' && !Number.isInteger(n)) {
    return `${label}须为整数`;
  }
  return null;
}

/** GET 视图 → 五个可编辑字段字符串态（预填与 dirty 基线同构）。 */
function marketTopValuesOf(view: MarketTopConfigView): Record<MarketTopConfigField, string> {
  return {
    poolSize: String(view.poolSize),
    deepDiveLimit: String(view.deepDiveLimit),
    deepDiveCostCapRatio: String(view.deepDiveCostCapRatio),
    diveCostEstimateMicros: String(view.diveCostEstimateMicros),
    memberCoverageFloor: String(view.memberCoverageFloor),
  };
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

/** 行内生效提示：LIVE_NEXT_CYCLE → amber「下一调度周期生效」；RESTART → amber「重启后生效」（均常驻）；LIVE → emerald「已生效」（短暂）。 */
type ScheduleNoteKind = 'done' | 'next-cycle' | 'restart';

function ScheduleNote({ note }: { note: ScheduleNoteKind }) {
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
  if (note === 'restart') {
    return (
      <Badge
        variant="outline"
        className="bg-amber-500/15 text-amber-400"
        data-testid="task-note-restart"
      >
        重启后生效
      </Badge>
    );
  }
  return (
    <span className="text-xs text-emerald-400" data-testid="task-note-done">
      已生效
    </span>
  );
}

// —— 调度编辑 Dialog（间隔秒数 / cron / 每日推荐 userIds / 留痕清理保留窗口） ——

/** 保存结果回执：saved=null 表示本次仅窗口变更（无调度 PATCH），jobKey 供行内提示定位。 */
interface EditSavedResult {
  saved: JobView | null;
  changed: string[];
  jobKey: string;
}

interface EditDialogProps {
  job: JobView;
  onClose: () => void;
  onSaved: (result: EditSavedResult) => void;
}

function ScheduleEditDialog({ job, onClose, onSaved }: EditDialogProps) {
  const isCron = job.scheduleType === 'CRON';
  const showUserIds = job.jobKey === 'DAILY_RECOMMEND';
  const showWindows = job.jobKey === RETENTION_JOB_KEY;
  const showWeights = job.jobKey === FACTOR_JOB_KEY;
  const showMarketTop = job.jobKey === MARKET_TOP_JOB_KEY;
  const [intervalSeconds, setIntervalSeconds] = useState(
    job.intervalMillis != null ? String(Math.round(job.intervalMillis / 1000)) : '',
  );
  const [cron, setCron] = useState(job.cron ?? '');
  const [userIds, setUserIds] = useState(job.userIds ?? '');
  // 保留窗口五字段（字符串态）+ 加载基线（dirty 比对）+ 下限/默认（GET limits，静态兜底）+ 防呆时间戳
  const [windows, setWindows] = useState<Record<RetentionWindowField, string> | null>(null);
  const [windowsLoaded, setWindowsLoaded] = useState<Record<RetentionWindowField, string> | null>(
    null,
  );
  const [windowsLimits, setWindowsLimits] = useState<
    Record<RetentionWindowField, RetentionFieldLimits> | null
  >(null);
  const [windowsUpdatedAt, setWindowsUpdatedAt] = useState<string | null>(null);
  const [windowsError, setWindowsError] = useState<string | null>(null);
  const [windowFieldError, setWindowFieldError] = useState<string | null>(null);
  // 评分权重分组（T172）：GET 全量视图（PATCH 13 字段全量——窗口/K/半衰期五字段随当前值回传）+
  // 八个可编辑字段字符串态 + 加载基线 + 防呆时间戳
  const [weightsView, setWeightsView] = useState<ScoreWeightsView | null>(null);
  const [weightValues, setWeightValues] = useState<Record<ScoreWeightField, string> | null>(null);
  const [weightValuesLoaded, setWeightValuesLoaded] = useState<
    Record<ScoreWeightField, string> | null
  >(null);
  const [weightsError, setWeightsError] = useState<string | null>(null);
  const [weightFieldError, setWeightFieldError] = useState<string | null>(null);
  // 漏斗配置分组（M21 T181）：GET 视图（PATCH 5 字段全量）+ 五个可编辑字段字符串态 + 加载基线 + 防呆时间戳
  const [marketTopView, setMarketTopView] = useState<MarketTopConfigView | null>(null);
  const [marketTopValues, setMarketTopValues] = useState<Record<MarketTopConfigField, string> | null>(
    null,
  );
  const [marketTopValuesLoaded, setMarketTopValuesLoaded] = useState<
    Record<MarketTopConfigField, string> | null
  >(null);
  const [marketTopError, setMarketTopError] = useState<string | null>(null);
  const [marketTopFieldError, setMarketTopFieldError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  // 保存失败文案（Dialog 内字段下方展示，保持 Dialog 打开不丢输入）
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  // 双 PATCH 部分成功标记：cron/权重已保存后重试不再重复提交（避免 expectedUpdatedAt 过期 30065）
  const cronSavedRef = useRef(false);
  const weightsSavedRef = useRef(false);
  const marketTopSavedRef = useRef(false);

  // Dialog 打开即 GET 预填（窗口的操作心智挂在这个清理任务上，方案 §3.5）
  useEffect(() => {
    if (!showWindows) {
      return;
    }
    let cancelled = false;
    getRetentionWindows()
      .then((view) => {
        if (cancelled) return;
        const loaded: Record<RetentionWindowField, string> = {
          jobExecutionLogDays: String(view.windows.jobExecutionLogDays),
          dataSourceEventDays: String(view.windows.dataSourceEventDays),
          llmCallLogDays: String(view.windows.llmCallLogDays),
          readingEventDays: String(view.windows.readingEventDays),
          newsItemDays: String(view.windows.newsItemDays),
          recommendationCardDays: String(view.windows.recommendationCardDays),
        };
        setWindows(loaded);
        setWindowsLoaded(loaded);
        setWindowsLimits(view.limits);
        setWindowsUpdatedAt(view.updatedAt);
      })
      .catch((err) => {
        if (!cancelled) {
          setWindowsError(`保留窗口加载失败：${messageOf(err, '请稍后重试')}`);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [showWindows]);

  // Dialog 打开即 GET 预填（权重分组的操作心智挂在因子快照任务上，M20 方案 §4.7.3）
  useEffect(() => {
    if (!showWeights) {
      return;
    }
    let cancelled = false;
    getScoreWeights()
      .then((view) => {
        if (cancelled) return;
        const loaded = weightValuesOf(view);
        setWeightsView(view);
        setWeightValues(loaded);
        setWeightValuesLoaded(loaded);
      })
      .catch((err) => {
        if (!cancelled) {
          setWeightsError(`评分权重加载失败：${messageOf(err, '请稍后重试')}`);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [showWeights]);

  // Dialog 打开即 GET 预填（漏斗配置分组的操作心智挂在榜单任务上，M21 方案 §4.7.3）
  useEffect(() => {
    if (!showMarketTop) {
      return;
    }
    let cancelled = false;
    getMarketTopConfig()
      .then((view) => {
        if (cancelled) return;
        const loaded = marketTopValuesOf(view);
        setMarketTopView(view);
        setMarketTopValues(loaded);
        setMarketTopValuesLoaded(loaded);
      })
      .catch((err) => {
        if (!cancelled) {
          setMarketTopError(`漏斗配置加载失败：${messageOf(err, '请稍后重试')}`);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [showMarketTop]);

  const minOf = (field: RetentionWindowField): number => {
    const meta = RETENTION_FIELDS.find((item) => item.field === field);
    return windowsLimits?.[field]?.min ?? meta?.fallbackMin ?? 1;
  };

  const windowsChanged =
    showWindows &&
    windows != null &&
    windowsLoaded != null &&
    RETENTION_FIELDS.some(({ field }) => windows[field].trim() !== windowsLoaded[field]);

  const weightsChanged =
    showWeights &&
    weightValues != null &&
    weightValuesLoaded != null &&
    SCORE_WEIGHT_FIELDS.some(({ field }) => weightValues[field].trim() !== weightValuesLoaded[field]);

  const marketTopChanged =
    showMarketTop &&
    marketTopValues != null &&
    marketTopValuesLoaded != null &&
    MARKET_TOP_FIELDS.some(({ field }) => marketTopValues[field].trim() !== marketTopValuesLoaded[field]);

  const dirty =
    (isCron
      ? cron.trim() !== (job.cron ?? '') ||
        (showUserIds && userIds.trim() !== (job.userIds ?? ''))
      : Number(intervalSeconds) * 1000 !== job.intervalMillis) ||
    windowsChanged ||
    weightsChanged ||
    marketTopChanged;

  const handleSave = async () => {
    const validation = isCron ? cronError(cron) : positiveSecondsError(intervalSeconds);
    const usersValidation = showUserIds ? userIdsError(userIds) : null;
    const windowValidation =
      showWindows && windows != null
        ? (RETENTION_FIELDS.map(({ field, label }) =>
            windowDaysError(label, windows[field], minOf(field)),
          ).find((message) => message != null) ?? null)
        : null;
    const weightValidation =
      showWeights && weightValues != null
        ? (SCORE_WEIGHT_FIELDS.map(({ field, label }) =>
            THRESHOLD_FIELDS.some((item) => item.field === field)
              ? thresholdError(label, weightValues[field])
              : weightError(label, weightValues[field]),
          ).find((message) => message != null) ?? weightSumError(weightValues))
        : null;
    const marketTopValidation =
      showMarketTop && marketTopValues != null
        ? (MARKET_TOP_FIELDS.map(({ field, label, kind, min, max }) =>
            marketTopValueError(label, marketTopValues[field], kind, min, max),
          ).find((message) => message != null) ?? null)
        : null;
    setError(validation ?? usersValidation);
    setWindowFieldError(windowValidation);
    setWeightFieldError(weightValidation);
    setMarketTopFieldError(marketTopValidation);
    setSaveError(null);
    if (
      validation ||
      usersValidation ||
      windowValidation ||
      weightValidation ||
      marketTopValidation ||
      !dirty
    ) {
      return;
    }
    const changed: string[] = [];
    setSaving(true);
    try {
      // 双 PATCH 分域提交（方案 §3.5 同款）：调度走既有 /jobs/{jobKey}，窗口走 /retention/windows，权重走 /value-scores/weights
      let saved: JobView | null = null;
      if (isCron && !cronSavedRef.current) {
        const body: JobConfigUpdate = { expectedUpdatedAt: job.updatedAt ?? undefined };
        if (cron.trim() !== (job.cron ?? '')) {
          body.cron = cron.trim();
          changed.push('cron');
        }
        if (showUserIds && userIds.trim() !== (job.userIds ?? '')) {
          body.userIds = userIds.trim();
          changed.push('userIds');
        }
        if (body.cron != null || body.userIds != null) {
          saved = await patchJob(job.jobKey, body);
          cronSavedRef.current = true;
        }
      } else if (!isCron && Number(intervalSeconds) * 1000 !== job.intervalMillis) {
        saved = await patchJob(job.jobKey, {
          intervalMillis: Number(intervalSeconds) * 1000,
          expectedUpdatedAt: job.updatedAt ?? undefined,
        });
        changed.push('intervalMillis');
      }
      if (windowsChanged) {
        await patchRetentionWindows({
          jobExecutionLogDays: Number(windows?.jobExecutionLogDays),
          dataSourceEventDays: Number(windows?.dataSourceEventDays),
          llmCallLogDays: Number(windows?.llmCallLogDays),
          readingEventDays: Number(windows?.readingEventDays),
          newsItemDays: Number(windows?.newsItemDays),
          recommendationCardDays: Number(windows?.recommendationCardDays),
          expectedUpdatedAt: windowsUpdatedAt ?? undefined,
        });
        changed.push('windows');
      }
      if (weightsChanged && !weightsSavedRef.current) {
        // 13 字段全量替换：八个编辑字段取输入，窗口/半衰期/K 五字段随 GET 视图当前值回传
        const view = weightsView;
        const values = weightValues as Record<ScoreWeightField, string>;
        await patchScoreWeights({
          wCatalyst: Number(values.wCatalyst),
          wConduction: Number(values.wConduction),
          wFundamental: Number(values.wFundamental),
          wRisk: Number(values.wRisk),
          wValuation: Number(values.wValuation),
          catalystWindowDays: view?.catalystWindowDays ?? 10,
          assocWindowDays: view?.assocWindowDays ?? 30,
          halfLifeDays: view?.halfLifeDays ?? 5.0,
          k1Saturation: view?.k1Saturation ?? 3.0,
          k3Saturation: view?.k3Saturation ?? 1.5,
          btCatalystMin: Number(values.btCatalystMin),
          btConductionMin: Number(values.btConductionMin),
          btRiskMin: Number(values.btRiskMin),
          expectedUpdatedAt: view?.updatedAt ?? undefined,
        });
        weightsSavedRef.current = true;
        changed.push('weights');
      }
      if (marketTopChanged && !marketTopSavedRef.current) {
        // 5 字段全量替换 + expectedUpdatedAt 并发防呆（后端 30091 字段级 / 30065 冲突）
        const values = marketTopValues as Record<MarketTopConfigField, string>;
        await patchMarketTopConfig({
          poolSize: Number(values.poolSize),
          deepDiveLimit: Number(values.deepDiveLimit),
          deepDiveCostCapRatio: Number(values.deepDiveCostCapRatio),
          diveCostEstimateMicros: Number(values.diveCostEstimateMicros),
          memberCoverageFloor: Number(values.memberCoverageFloor),
          expectedUpdatedAt: marketTopView?.updatedAt ?? undefined,
        });
        marketTopSavedRef.current = true;
        changed.push('marketTop');
      }
      onSaved({ saved, changed, jobKey: job.jobKey });
      onClose();
    } catch (err) {
      // 保存失败：Dialog 保持打开、输入保留，错误渲染在字段下方供就地重试
      // （已成功的分域不重复提交：cronSavedRef/weightsSavedRef 守卫）
      setSaveError(messageOf(err, '保存失败，请重试'));
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
      {showWindows ? (
        <div className="flex flex-col gap-2">
          <span className="text-sm font-medium">保留窗口（天）</span>
          <p className="text-xs text-muted-foreground">
            保存即热生效——下一轮清理按新窗口；改大窗口不会恢复已删数据。
          </p>
          {windowsError ? (
            <p
              className="text-xs text-destructive"
              role="alert"
              data-testid={`task-edit-window-error-${job.jobKey}`}
            >
              {windowsError}
            </p>
          ) : null}
          {RETENTION_FIELDS.map(({ field, label }) => (
            <label key={field} className="flex flex-col gap-1 text-sm">
              <span>{`${label}（≥${minOf(field)}）`}</span>
              <Input
                value={windows ? windows[field] : ''}
                onChange={(e) =>
                  setWindows((prev) => (prev ? { ...prev, [field]: e.target.value } : prev))
                }
                aria-label={label}
                inputMode="numeric"
                disabled={windows == null || windowsError != null}
                data-testid={`task-edit-window-${job.jobKey}-${field}`}
              />
            </label>
          ))}
          {windowFieldError ? (
            <span className="text-xs text-destructive" role="alert">
              {windowFieldError}
            </span>
          ) : null}
        </div>
      ) : null}
      {showWeights ? (
        <div className="flex flex-col gap-2">
          <span className="text-sm font-medium">评分权重与「有突破」阈值</span>
          <p className="text-xs text-muted-foreground">
            保存即热生效——下一轮 17:30 快照按新参数计算；五维权重合成自动归一（估值水平默认 0 =
            未启用）。
          </p>
          {weightsError ? (
            <p
              className="text-xs text-destructive"
              role="alert"
              data-testid={`task-edit-weight-error-${job.jobKey}`}
            >
              {weightsError}
            </p>
          ) : null}
          {WEIGHT_FIELDS.map(({ field, label }) => (
            <label key={field} className="flex flex-col gap-1 text-sm">
              <span>{`${label}（0~1）`}</span>
              <Input
                value={weightValues ? weightValues[field] : ''}
                onChange={(e) =>
                  setWeightValues((prev) => (prev ? { ...prev, [field]: e.target.value } : prev))
                }
                aria-label={label}
                inputMode="decimal"
                disabled={weightValues == null || weightsError != null}
                data-testid={`task-edit-weight-${job.jobKey}-${field}`}
              />
            </label>
          ))}
          {weightValues ? (
            <p className="text-xs text-muted-foreground" data-testid={`task-edit-weight-sum-${job.jobKey}`}>
              {`当前五维权重和 ${Math.round(
                WEIGHT_FIELDS.reduce((acc, { field }) => acc + Number(weightValues[field] || 0), 0) * 1000,
              ) / 1000}（须 > 0 且 ≤ ${WEIGHT_SUM_TOLERANCE}）`}
            </p>
          ) : null}
          {THRESHOLD_FIELDS.map(({ field, label }) => (
            <label key={field} className="flex flex-col gap-1 text-sm">
              <span>{`${label}（0~100）`}</span>
              <Input
                value={weightValues ? weightValues[field] : ''}
                onChange={(e) =>
                  setWeightValues((prev) => (prev ? { ...prev, [field]: e.target.value } : prev))
                }
                aria-label={label}
                inputMode="numeric"
                disabled={weightValues == null || weightsError != null}
                data-testid={`task-edit-weight-${job.jobKey}-${field}`}
              />
            </label>
          ))}
          {weightFieldError ? (
            <span className="text-xs text-destructive" role="alert">
              {weightFieldError}
            </span>
          ) : null}
        </div>
      ) : null}
      {showMarketTop ? (
        <div className="flex flex-col gap-2">
          <span className="text-sm font-medium">全市场榜单漏斗配置</span>
          <p className="text-xs text-muted-foreground">
            保存即热生效——下一轮 18:00 榜单按新参数；深析候选数 30~50 为蓝图区间硬校验（防全量逐股）。
          </p>
          {marketTopError ? (
            <p
              className="text-xs text-destructive"
              role="alert"
              data-testid={`task-edit-mtconfig-error-${job.jobKey}`}
            >
              {marketTopError}
            </p>
          ) : null}
          {MARKET_TOP_FIELDS.map(({ field, label, kind, min, max, hint }) => (
            <label key={field} className="flex flex-col gap-1 text-sm">
              <span>{`${label}（${min}~${max}，${hint}）`}</span>
              <Input
                value={marketTopValues ? marketTopValues[field] : ''}
                onChange={(e) =>
                  setMarketTopValues((prev) => (prev ? { ...prev, [field]: e.target.value } : prev))
                }
                aria-label={label}
                inputMode={kind === 'int' ? 'numeric' : 'decimal'}
                disabled={marketTopValues == null || marketTopError != null}
                data-testid={`task-edit-mtconfig-${job.jobKey}-${field}`}
              />
            </label>
          ))}
          {marketTopFieldError ? (
            <span className="text-xs text-destructive" role="alert">
              {marketTopFieldError}
            </span>
          ) : null}
        </div>
      ) : null}
      {saveError ? (
        <p
          className="text-sm text-destructive"
          role="alert"
          data-testid={`task-edit-error-${job.jobKey}`}
        >
          {saveError}
        </p>
      ) : null}
    </Dialog>
  );
}

// —— 单行 ——

interface TaskRowProps {
  job: JobView;
  note: ScheduleNoteKind | null;
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
  const [notes, setNotes] = useState<Record<string, ScheduleNoteKind>>({});
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

  const setNote = (jobKey: string, note: ScheduleNoteKind) => {
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

  const applySaved = ({ saved, changed, jobKey }: EditSavedResult) => {
    // 生效方式按接口 effectiveMode 渲染（UI 方案 §4.2/D3）：
    // RESTART 常驻「重启后生效」/ LIVE_NEXT_CYCLE 常驻「下一调度周期生效」/ LIVE 短暂「已生效」
    if (saved) {
      setJobs((prev) => prev?.map((job) => (job.jobKey === saved.jobKey ? saved : job)) ?? prev);
      const modes: Record<string, JobEffectiveMode> = saved.effectiveModes ?? {};
      const restart = changed.some((field) => modes[field] === 'RESTART');
      const nextCycle = changed.some((field) => modes[field] === 'LIVE_NEXT_CYCLE');
      setNote(jobKey, restart ? 'restart' : nextCycle ? 'next-cycle' : 'done');
    } else if (changed.includes('windows') || changed.includes('weights') || changed.includes('marketTop')) {
      // 仅窗口/权重/漏斗配置变更（无调度 PATCH）：下一轮清理/快照/榜单按新参数（热生效，M10 T73 / M20 T172 / M21 T181）
      setNote(jobKey, 'next-cycle');
    }
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
      applySaved({ saved, changed, jobKey: job.jobKey });
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
        <EmptyState title="暂无注册任务" testId="task-center-empty" />
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
