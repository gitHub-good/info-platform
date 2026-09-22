import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '@/api/http';
import {
  getDatasourceConfigs,
  patchAggregationGlobal,
  patchDatasourceSource,
  testDatasourceConnectivity,
} from '@/api/datasourceConfig';
import { EffectBadge } from '@/components/config/EffectBadge';
import { SaveFeedbackBar, type SaveFeedbackState } from '@/components/config/SaveFeedbackBar';
import { Switch } from '@/components/config/Switch';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import type {
  AggregationGlobalView,
  DataSourceCardView,
  DataSourceConfigUpdate,
  DataSourceConfigView,
  DataSourceConnectivityResult,
  DataSourceMode,
} from '@/types/datasourceConfig';
import type { EffectiveMode } from '@/types/llmConfig';

// —— 参数键位与字段中文名（保存反馈枚举生效范围用，UI 方案 §4.2 逐项枚举） ——

const FIELD_LABELS: Record<string, string> = {
  enabled: '启用',
  mode: '运行模式',
  timeoutMillis: '超时',
  retries: '重试',
  cacheTtlSeconds: '缓存 TTL',
  params: '外呼参数',
};

/** 各源 URL 类参数（卡内截断展示 + 「编辑参数」Dialog 编辑）。 */
const URL_PARAMS: Record<string, Array<{ key: string; label: string }>> = {
  QUOTE: [
    { key: 'quoteUrl', label: '行情端点 URL' },
    { key: 'fields', label: '行情字段列表' },
  ],
  FINANCE: [
    { key: 'financeUrl', label: '财务端点 URL' },
    { key: 'financeReferer', label: '财务 Referer' },
  ],
  VALUATION: [
    { key: 'quoteUrl', label: '估值端点 URL' },
    { key: 'valuationFields', label: '估值字段列表' },
  ],
  ANNOUNCE: [
    { key: 'announceUrl', label: '公告端点 URL' },
    { key: 'announceDetailUrlTemplate', label: '公告详情直链模板' },
  ],
  NEWS: [
    { key: 'newsUrl', label: '新闻端点 URL' },
    { key: 'newsReferer', label: '新闻 Referer' },
  ],
  POLICY: [
    { key: 'policyUrl', label: '政策端点 URL' },
    { key: 'policyReferer', label: '政策 Referer' },
  ],
};

/** 有分页语义的源才展示取数条数（UI 方案 §3.3 交互 6），其余显示 —。 */
const COUNT_PARAM: Record<string, { key: string; label: string }> = {
  ANNOUNCE: { key: 'announcePageSize', label: '取数条数' },
  NEWS: { key: 'newsPageSize', label: '取数条数' },
};

const EVENT_SOURCE = 'EVENT';

// —— 前端即时校验（UI 方案 §5.3：提交前拦截不发请求，字段级提示） ——

function positiveIntError(raw: string): string | null {
  const n = Number(raw);
  return Number.isInteger(n) && n > 0 ? null : '须为正整数';
}

function retryError(raw: string): string | null {
  const n = Number(raw);
  return Number.isInteger(n) && n >= 0 && n <= 3 ? null : '须为 0~3 的整数';
}

function urlError(raw: string): string | null {
  return /^(https?):\/\/\S+$/.test(raw.trim()) ? null : 'URL 须以 http(s):// 开头且格式合法';
}

/** URL 类参数才做 http(s) 校验；字段列表类参数仅非空（对齐后端校验器后缀规则）。 */
function paramError(key: string, value: string): string | null {
  if (key.endsWith('Url') || key.endsWith('Referer') || key.endsWith('Template')) {
    return urlError(value);
  }
  return value.trim() === '' ? '不能为空' : null;
}

function messageOf(err: unknown, fallback: string): string {
  return err instanceof ApiError ? err.msg : fallback;
}

function splitByEffect(
  fields: string[],
  effectiveModes: Record<string, EffectiveMode>,
): { hot: string[]; restart: string[] } {
  const hot: string[] = [];
  const restart: string[] = [];
  for (const field of fields) {
    if (effectiveModes[field] === 'LIVE') {
      hot.push(FIELD_LABELS[field] ?? field);
    } else {
      restart.push(FIELD_LABELS[field] ?? field);
    }
  }
  return { hot, restart };
}

// —— 健康徽章（交互 1：颜色语义带文字，沿用 JobLog 状态徽章惯例） ——

function healthBadgeOf(health: DataSourceCardView['health']) {
  if (!health.lastEventType) {
    return { text: '暂无抓取记录', className: 'bg-muted text-muted-foreground' };
  }
  const time = health.lastEventAt ? ` ${formatTime(health.lastEventAt)}` : '';
  switch (health.lastEventType) {
    case 'OK':
      return { text: `成功${time}`, className: 'bg-emerald-500/15 text-emerald-400' };
    case 'TIMEOUT':
      return { text: `超时${time}`, className: 'bg-amber-500/15 text-amber-400' };
    case 'ERROR':
      return { text: `失败${time}`, className: 'bg-rose-500/15 text-rose-400' };
    case 'LIMITED':
      return { text: `限频${time}`, className: 'bg-amber-500/15 text-amber-400' };
    default:
      return { text: `无数据${time}`, className: 'bg-muted text-muted-foreground' };
  }
}

function formatTime(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  return `${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(
    date.getHours(),
  ).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
}

// —— 聚合总超时条（即时生效区，同 LLM 全局卡结构） ——

interface AggregationCardProps {
  aggregation: AggregationGlobalView;
  onSaved: (next: AggregationGlobalView) => void;
}

function AggregationCard({ aggregation, onSaved }: AggregationCardProps) {
  const [form, setForm] = useState<{ timeout?: string }>({});
  const timeout = form.timeout ?? String(aggregation.detailTimeoutMillis);
  const [error, setError] = useState<string | null>(null);
  const [state, setState] = useState<SaveFeedbackState>('idle');
  const [feedback, setFeedback] = useState<{ hot: string[]; restart: string[]; msg: string }>({
    hot: [],
    restart: [],
    msg: '',
  });

  const dirty = Number(timeout) !== aggregation.detailTimeoutMillis;

  const handleSave = async () => {
    const validation = positiveIntError(timeout);
    setError(validation);
    if (validation || !dirty) {
      return;
    }
    setState('saving');
    try {
      const saved = await patchAggregationGlobal({
        detailTimeoutMillis: Number(timeout),
        expectedUpdatedAt: aggregation.updatedAt ?? undefined,
      });
      onSaved(saved);
      setForm({});
      // 生效范围按 effectiveMode 枚举（§4.2：禁止静默反馈）
      if (aggregation.effectiveModes.detailTimeoutMillis === 'LIVE') {
        setFeedback({ hot: ['聚合总超时'], restart: [], msg: '' });
      } else {
        setFeedback({ hot: [], restart: ['聚合总超时'], msg: '' });
      }
      setState('success');
    } catch (err) {
      setFeedback({ hot: [], restart: [], msg: messageOf(err, '保存失败，请重试') });
      setState('error');
    }
  };

  return (
    <Card data-testid="datasource-aggregation-card">
      <CardHeader>
        <CardTitle className="flex flex-wrap items-center gap-2 text-base">
          聚合编排总超时
          <EffectBadge mode={aggregation.effectiveModes.detailTimeoutMillis} />
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1 text-sm">
          <span>总超时（ms，500~10000）</span>
          <Input
            value={timeout}
            onChange={(e) => setForm((f) => ({ ...f, timeout: e.target.value }))}
            aria-label="聚合总超时毫秒"
            data-testid="datasource-aggregation-timeout"
            className="w-40"
          />
          {error ? (
            <span className="text-xs text-destructive" role="alert">
              {error}
            </span>
          ) : null}
        </label>
        <Button
          size="sm"
          disabled={!dirty || state === 'saving'}
          onClick={() => void handleSave()}
          data-testid="datasource-aggregation-save"
        >
          {state === 'saving' ? '保存中…' : '保存'}
        </Button>
        <div className="min-w-48 flex-1">
          <SaveFeedbackBar
            state={state}
            hotFields={feedback.hot}
            restartFields={feedback.restart}
            message={feedback.msg}
            testId="datasource-aggregation-feedback"
          />
        </div>
      </CardContent>
    </Card>
  );
}

// —— 源卡片 ——

interface SourceCardProps {
  source: DataSourceCardView;
  onSaved: (next: DataSourceCardView) => void;
}

function SourceCard({ source, onSaved }: SourceCardProps) {
  const isEvent = source.sourceCode === EVENT_SOURCE;
  const urlParams = URL_PARAMS[source.sourceCode] ?? [];
  const countParam = COUNT_PARAM[source.sourceCode];
  const primaryUrlKey = urlParams[0]?.key;

  // 表单为「服务端值 + 用户覆盖」派生（不在 effect 里同步 setState）：覆盖仅在保存成功后整体清空
  const [form, setForm] = useState<{
    timeout?: string;
    retries?: string;
    count?: string;
    urls?: Record<string, string>;
  }>({});
  const timeout = form.timeout ?? String(source.timeoutMillis);
  const retries = form.retries ?? String(source.retries);
  const count = form.count ?? (countParam ? String(source.params[countParam.key]) : '');
  const urls = form.urls ?? {};
  const displayedUrl = primaryUrlKey ? String(source.params[primaryUrlKey] ?? '—') : '—';

  const [errors, setErrors] = useState<Record<string, string>>({});
  const [state, setState] = useState<SaveFeedbackState>('idle');
  const [feedback, setFeedback] = useState<{ hot: string[]; restart: string[]; msg: string }>({
    hot: [],
    restart: [],
    msg: '',
  });
  const [modeConfirm, setModeConfirm] = useState<DataSourceMode | null>(null);
  const [paramsOpen, setParamsOpen] = useState(false);
  const [paramErrors, setParamErrors] = useState<Record<string, string>>({});
  const [connectivity, setConnectivity] = useState<
    'idle' | 'testing' | (DataSourceConnectivityResult & { ok: boolean })
  >('idle');

  const dirty =
    Number(timeout) !== source.timeoutMillis ||
    Number(retries) !== source.retries ||
    (countParam !== undefined && Number(count) !== Number(source.params[countParam.key])) ||
    urlParams.some(
      (p) => (urls[p.key] ?? String(source.params[p.key] ?? '')) !== String(source.params[p.key] ?? ''),
    );

  const buildUpdate = (modeOverride?: DataSourceMode): {
    body: DataSourceConfigUpdate;
    changed: string[];
  } => {
    const body: DataSourceConfigUpdate = {};
    const changed: string[] = [];
    if (modeOverride && modeOverride !== source.mode) {
      body.mode = modeOverride;
      changed.push('mode');
    }
    if (!isEvent) {
      if (Number(timeout) !== source.timeoutMillis) {
        body.timeoutMillis = Number(timeout);
        changed.push('timeoutMillis');
      }
      if (Number(retries) !== source.retries) {
        body.retries = Number(retries);
        changed.push('retries');
      }
      const nextParams: Record<string, string | number> = { ...source.params };
      let paramsChanged = false;
      if (countParam && Number(count) !== Number(source.params[countParam.key])) {
        nextParams[countParam.key] = Number(count);
        paramsChanged = true;
      }
      for (const p of urlParams) {
        const edited = urls[p.key];
        if (edited !== undefined && edited !== String(source.params[p.key] ?? '')) {
          nextParams[p.key] = edited.trim();
          paramsChanged = true;
        }
      }
      if (paramsChanged) {
        body.params = nextParams;
        changed.push('params');
      }
    }
    return { body, changed };
  };

  const doSave = async (body: DataSourceConfigUpdate, changed: string[]) => {
    setState('saving');
    try {
      const saved = await patchDatasourceSource(source.sourceCode, {
        ...body,
        expectedUpdatedAt: source.updatedAt ?? undefined,
      });
      onSaved(saved);
      setForm({});
      const { hot, restart } = splitByEffect(changed, source.effectiveModes);
      setFeedback({ hot, restart, msg: '' });
      setState('success');
    } catch (err) {
      // 单卡失败仅本卡反馈（三态规范）：原值保留、其他卡不受影响
      setFeedback({ hot: [], restart: [], msg: messageOf(err, '保存失败，请重试') });
      setState('error');
    }
  };

  const validate = (): boolean => {
    if (isEvent) {
      return true;
    }
    const next: Record<string, string> = {};
    next.timeout = positiveIntError(timeout) ?? '';
    next.retries = retryError(retries) ?? '';
    if (countParam) {
      next.count = positiveIntError(count) ?? '';
    }
    setErrors(next);
    return Object.values(next).every((msg) => msg === '');
  };

  const handleSave = async () => {
    if (!validate()) {
      return;
    }
    const { body, changed } = buildUpdate();
    if (changed.length === 0) {
      return;
    }
    await doSave(body, changed);
  };

  const handleMode = (next: DataSourceMode) => {
    if (next === source.mode) {
      return;
    }
    // 真实→mock 切换保存前确认（UI 方案 §3.3 交互 2：mock 数据非真实，验证完成记得切回）
    if (next === 'MOCK') {
      setModeConfirm(next);
      return;
    }
    void doSave({ mode: next }, ['mode']);
  };

  const handleToggle = (enabled: boolean) => {
    void doSave({ enabled }, ['enabled']);
  };

  const handleSaveParams = async () => {
    const next: Record<string, string> = {};
    for (const p of urlParams) {
      const value = urls[p.key] ?? String(source.params[p.key] ?? '');
      next[p.key] = paramError(p.key, value) ?? '';
    }
    setParamErrors(next);
    if (Object.values(next).some((msg) => msg !== '')) {
      return;
    }
    setParamsOpen(false);
    if (!validate()) {
      return;
    }
    const { body, changed } = buildUpdate();
    if (changed.length === 0) {
      return;
    }
    await doSave(body, changed);
  };

  const runConnectivity = async () => {
    setConnectivity('testing');
    try {
      setConnectivity(await testDatasourceConnectivity(source.sourceCode));
    } catch (err) {
      setConnectivity({
        ok: false,
        latencyMillis: null,
        itemCount: null,
        mode: source.mode,
        error: messageOf(err, '测试请求失败'),
        note: null,
      });
    }
  };

  const health = healthBadgeOf(source.health);

  return (
    <Card
      className={source.enabled ? undefined : 'opacity-60'}
      data-testid={`datasource-card-${source.sourceCode}`}
    >
      <CardHeader>
        <CardTitle className="flex flex-wrap items-center gap-2 text-base">
          <span className="font-medium">{source.label}</span>
          <Badge className={health.className} data-testid={`datasource-health-${source.sourceCode}`}>
            {health.text}
          </Badge>
          {source.health.errors24h > 0 ? (
            <Badge variant="outline" className="text-rose-400">
              24h 异常 {source.health.errors24h}
            </Badge>
          ) : null}
          <span className="ml-auto flex items-center gap-2 text-sm text-muted-foreground">
            启用
            <Switch
              checked={source.enabled}
              onCheckedChange={(next) => handleToggle(next)}
              disabled={state === 'saving'}
              aria-label={`启用 ${source.label}`}
              data-testid={`datasource-enabled-${source.sourceCode}`}
            />
          </span>
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {source.mode === 'MOCK' && !isEvent ? (
          <p
            className="rounded bg-amber-500/15 px-2 py-1 text-xs text-amber-400"
            data-testid={`datasource-mock-banner-${source.sourceCode}`}
          >
            当前为 mock 模式，数据非真实
          </p>
        ) : null}
        {isEvent ? (
          <p className="text-xs text-muted-foreground">读取本地异动表，无外部端点</p>
        ) : (
          <>
            <div className="flex flex-wrap items-center gap-2 text-sm">
              <span className="text-muted-foreground">运行模式</span>
              <div className="inline-flex overflow-hidden rounded-md border">
                <Button
                  type="button"
                  size="sm"
                  variant={source.mode === 'REAL' ? 'default' : 'ghost'}
                  className="rounded-none"
                  onClick={() => handleMode('REAL')}
                  data-testid={`datasource-mode-real-${source.sourceCode}`}
                >
                  真实
                </Button>
                <Button
                  type="button"
                  size="sm"
                  variant={source.mode === 'MOCK' ? 'default' : 'ghost'}
                  className="rounded-none"
                  onClick={() => handleMode('MOCK')}
                  data-testid={`datasource-mode-mock-${source.sourceCode}`}
                >
                  mock
                </Button>
              </div>
            </div>
            <div className="grid grid-cols-3 gap-2">
              <label className="flex flex-col gap-1 text-sm">
                <span>超时（ms）</span>
                <Input
                  value={timeout}
                  onChange={(e) => setForm((f) => ({ ...f, timeout: e.target.value }))}
                  aria-label={`${source.label} 超时毫秒`}
                  data-testid={`datasource-timeout-${source.sourceCode}`}
                />
                {errors.timeout ? (
                  <span className="text-xs text-destructive" role="alert">
                    {errors.timeout}
                  </span>
                ) : null}
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span>重试（次）</span>
                <Input
                  value={retries}
                  onChange={(e) => setForm((f) => ({ ...f, retries: e.target.value }))}
                  aria-label={`${source.label} 重试次数`}
                  data-testid={`datasource-retries-${source.sourceCode}`}
                />
                {errors.retries ? (
                  <span className="text-xs text-destructive" role="alert">
                    {errors.retries}
                  </span>
                ) : null}
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span>{countParam ? '取数条数' : '取数条数'}</span>
                {countParam ? (
                  <>
                    <Input
                      value={count}
                      onChange={(e) => setForm((f) => ({ ...f, count: e.target.value }))}
                      aria-label={`${source.label} 取数条数`}
                      data-testid={`datasource-count-${source.sourceCode}`}
                    />
                    {errors.count ? (
                      <span className="text-xs text-destructive" role="alert">
                        {errors.count}
                      </span>
                    ) : null}
                  </>
                ) : (
                  <span className="py-2 text-muted-foreground">—</span>
                )}
              </label>
            </div>
            <div className="flex flex-col gap-1 text-sm">
              <span className="text-xs text-muted-foreground">端点 URL</span>
              <span className="truncate text-xs" title={displayedUrl}>
                {displayedUrl}
              </span>
            </div>
          </>
        )}
        <div className="flex flex-wrap items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            disabled={connectivity === 'testing' || !source.enabled}
            onClick={() => void runConnectivity()}
            data-testid={`datasource-connect-${source.sourceCode}`}
          >
            {connectivity === 'testing' ? '抓取中…' : '测试抓取'}
          </Button>
          {!isEvent ? (
            <Button
              variant="outline"
              size="sm"
              onClick={() => {
                setParamErrors({});
                setParamsOpen(true);
              }}
              data-testid={`datasource-edit-params-${source.sourceCode}`}
            >
              编辑参数
            </Button>
          ) : null}
          <Button
            size="sm"
            className="ml-auto"
            disabled={!dirty || state === 'saving'}
            onClick={() => void handleSave()}
            data-testid={`datasource-save-${source.sourceCode}`}
          >
            {state === 'saving' ? '保存中…' : '保存本条'}
          </Button>
        </div>
        {connectivity !== 'idle' && connectivity !== 'testing' ? (
          <p
            className={connectivity.ok ? 'text-xs text-emerald-400' : 'text-xs text-rose-400'}
            role="status"
            data-testid={`datasource-connect-result-${source.sourceCode}`}
          >
            {connectivity.ok
              ? `抓取成功 · ${connectivity.latencyMillis}ms · ${connectivity.itemCount} 条${connectivity.note ? `（${connectivity.note}）` : ''}`
              : `失败：${connectivity.error ?? '未知原因'}`}
          </p>
        ) : null}
        <SaveFeedbackBar
          state={state}
          hotFields={feedback.hot}
          restartFields={feedback.restart}
          message={feedback.msg}
          testId={`datasource-feedback-${source.sourceCode}`}
        />
        <Dialog
          open={modeConfirm === 'MOCK'}
          title="切换为 mock 模式"
          description="切换后该源返回模拟数据，验证完成请记得切回。确认切换？"
          onClose={() => setModeConfirm(null)}
          footer={
            <>
              <Button variant="outline" size="sm" onClick={() => setModeConfirm(null)}>
                取消
              </Button>
              <Button
                size="sm"
                onClick={() => {
                  const next = modeConfirm;
                  setModeConfirm(null);
                  if (next) {
                    void doSave({ mode: next }, ['mode']);
                  }
                }}
                data-testid={`datasource-mode-confirm-${source.sourceCode}`}
              >
                确认切换
              </Button>
            </>
          }
        />
        <Dialog
          open={paramsOpen}
          title={`${source.label} · 外呼参数`}
          description="URL 类参数保存后下一次抓取即按新值生效（即时生效）。"
          onClose={() => setParamsOpen(false)}
          footer={
            <>
              <Button variant="outline" size="sm" onClick={() => setParamsOpen(false)}>
                取消
              </Button>
              <Button size="sm" onClick={() => void handleSaveParams()} data-testid={`datasource-params-save-${source.sourceCode}`}>
                保存参数
              </Button>
            </>
          }
        >
          {urlParams.map((p) => (
            <label key={p.key} className="flex flex-col gap-1 py-1 text-sm">
              <span>{p.label}</span>
              <Input
                value={urls[p.key] ?? String(source.params[p.key] ?? '')}
                onChange={(e) =>
                  setForm((f) => ({ ...f, urls: { ...(f.urls ?? {}), [p.key]: e.target.value } }))
                }
                aria-label={p.label}
                data-testid={`datasource-param-${source.sourceCode}-${p.key}`}
              />
              {paramErrors[p.key] ? (
                <span className="text-xs text-destructive" role="alert">
                  {paramErrors[p.key]}
                </span>
              ) : null}
            </label>
          ))}
        </Dialog>
      </CardContent>
    </Card>
  );
}

// —— 页面 ——

/**
 * 数据源配置页（T40，#/datasource-config，UI 方案 §3.3）。
 * 聚合总超时条 + 7 源卡网格（事件源只读卡）；模式用「真实/mock 分段按钮」（mock ≠ 停用）；健康徽章取
 * data_source_event 最近一条（含 OK 心跳）；保存按卡（单卡失败不污染他卡）。
 * 三态：加载骨架 / 「暂无抓取记录」空态（单源）/ 整页错误重试；401 由 http 层统一跳登录。
 */
export function DatasourceConfig() {
  const [view, setView] = useState<DataSourceConfigView | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  const load = useCallback(async () => {
    abortRef.current?.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    setLoading(true);
    setError(null);
    try {
      const data = await getDatasourceConfigs(ctrl.signal);
      if (ctrl.signal.aborted) return;
      setView(data);
    } catch (err) {
      if (ctrl.signal.aborted) return;
      setError(messageOf(err, '数据源配置加载失败'));
    } finally {
      if (!ctrl.signal.aborted) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
    return () => abortRef.current?.abort();
  }, [load]);

  const applyAggregation = (next: AggregationGlobalView) => {
    setView((prev) => (prev ? { ...prev, aggregation: next } : prev));
  };

  const applySource = (next: DataSourceCardView) => {
    setView((prev) =>
      prev
        ? {
            ...prev,
            sources: prev.sources.map((s) => (s.sourceCode === next.sourceCode ? next : s)),
          }
        : prev,
    );
  };

  return (
    <main className="mx-auto w-full max-w-6xl p-4 sm:p-6" data-testid="datasource-config-page">
      <header className="mb-4">
        <h1 className="text-xl font-medium">数据源配置</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          7 源开关 / 运行模式 / 弹性参数与健康状态一页管理
        </p>
      </header>

      {loading ? (
        <div className="flex flex-col gap-4" data-testid="datasource-config-loading">
          <Skeleton className="h-20 w-full" />
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-2 2xl:grid-cols-3">
            {Array.from({ length: 7 }, (_, i) => (
              <Skeleton key={i} className="h-56 w-full" />
            ))}
          </div>
        </div>
      ) : error ? (
        <div className="flex flex-col items-start gap-2" data-testid="datasource-config-error">
          <p className="text-sm text-destructive" role="alert">
            {error}
          </p>
          <Button
            variant="outline"
            size="sm"
            onClick={() => void load()}
            data-testid="datasource-config-retry"
          >
            重试
          </Button>
        </div>
      ) : view ? (
        <div className="flex flex-col gap-4">
          <AggregationCard aggregation={view.aggregation} onSaved={applyAggregation} />
          {view.sources.length === 0 ? (
            <p
              className="py-10 text-center text-sm text-muted-foreground"
              data-testid="datasource-config-empty"
            >
              暂无数据源配置（异常场景，请检查后端种子）
            </p>
          ) : (
            <div className="grid grid-cols-1 gap-4 lg:grid-cols-2 2xl:grid-cols-3">
              {view.sources.map((source) => (
                <SourceCard key={source.sourceCode} source={source} onSaved={applySource} />
              ))}
            </div>
          )}
        </div>
      ) : null}
    </main>
  );
}

export default DatasourceConfig;
