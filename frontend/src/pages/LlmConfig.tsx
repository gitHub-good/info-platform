import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '@/api/http';
import {
  getLlmConfig,
  patchLlmGlobal,
  putLlmApiKey,
  putLlmProvider,
  testLlmConnectivity,
} from '@/api/llmConfig';
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
  EffectiveMode,
  LlmConfigView,
  LlmGlobalConfigView,
  LlmGlobalUpdate,
  LlmProviderConfigView,
  LlmProviderUpdate,
} from '@/types/llmConfig';

// —— 字段中文名（保存反馈枚举生效范围用，UI 方案 §4.2 逐项枚举） ——

const GLOBAL_FIELD_LABELS: Record<string, string> = {
  dailyTokenBudgetPerUser: '日预算',
  budgetWarnRatio: '告警阈值',
  timeoutSeconds: '超时',
  retry: '重试',
  cacheDefaultTtlSeconds: '缓存默认 TTL',
  cacheTtlSeconds: '缓存 TTL 分档',
  cacheMaximumSize: '缓存上限',
};

const PROVIDER_FIELD_LABELS: Record<string, string> = {
  model: '模型',
  enabled: '启用',
  isDefault: '默认',
  inputPricePerMillion: '输入单价',
  outputPricePerMillion: '输出单价',
  baseUrl: '端点',
  apiKey: 'API Key',
};

const TTL_SCENE_LABELS: Record<string, string> = {
  'brief-type-1': '个股简报',
  'brief-type-3': '政策解读',
  'brief-type-4': '每日推荐',
};

// —— 前端即时校验（UI 方案 §5.3：提交前拦截不发请求，字段级提示） ——

function positiveIntError(raw: string): string | null {
  const n = Number(raw);
  return Number.isInteger(n) && n > 0 ? null : '须为正整数';
}

function intRangeError(raw: string, min: number, max: number): string | null {
  const n = Number(raw);
  return Number.isInteger(n) && n >= min && n <= max ? null : `须为 ${min}~${max} 的整数`;
}

function ratioError(raw: string): string | null {
  const n = Number(raw);
  return Number.isFinite(n) && n > 0 && n <= 1 ? null : '告警阈值取值 (0,1]，如 0.8';
}

function nonNegativeError(raw: string): string | null {
  const n = Number(raw);
  return Number.isFinite(n) && n >= 0 ? null : '单价不能为负';
}

/** 变更字段按 effectiveMode 分组（缺失按 RESTART，宁多示不漏示）。 */
function splitByEffect(
  fields: string[],
  effectiveModes: Record<string, EffectiveMode>,
): { hot: string[]; restart: string[] } {
  const hot: string[] = [];
  const restart: string[] = [];
  for (const field of fields) {
    const label = (GLOBAL_FIELD_LABELS[field] ?? PROVIDER_FIELD_LABELS[field] ?? field);
    if (effectiveModes[field] === 'LIVE') {
      hot.push(label);
    } else {
      restart.push(label);
    }
  }
  return { hot, restart };
}

function messageOf(err: unknown, fallback: string): string {
  return err instanceof ApiError ? err.msg : fallback;
}

// —— 全局参数卡 ——

interface GlobalCardProps {
  global: LlmGlobalConfigView;
  onSaved: (next: LlmGlobalConfigView) => void;
}

function GlobalCard({ global, onSaved }: GlobalCardProps) {
  // 表单为「服务端值 + 用户覆盖」派生（不在 effect 里同步 setState）：覆盖仅在保存成功后整体清空
  const [form, setForm] = useState<{
    budget?: string;
    ratio?: string;
    timeout?: string;
    retry?: string;
    ttl?: Record<string, string>;
  }>({});
  const budget = form.budget ?? String(global.dailyTokenBudgetPerUser);
  const ratio = form.ratio ?? String(global.budgetWarnRatio);
  const timeout = form.timeout ?? String(global.timeoutSeconds);
  const retry = form.retry ?? String(global.retry);
  const ttl = form.ttl ?? {};
  const setBudget = (value: string) => setForm((f) => ({ ...f, budget: value }));
  const setRatio = (value: string) => setForm((f) => ({ ...f, ratio: value }));
  const setTimeoutSec = (value: string) => setForm((f) => ({ ...f, timeout: value }));
  const setRetry = (value: string) => setForm((f) => ({ ...f, retry: value }));
  const setTtl = (key: string, value: string) =>
    setForm((f) => ({ ...f, ttl: { ...(f.ttl ?? {}), [key]: value } }));

  const [errors, setErrors] = useState<Record<string, string>>({});
  const [state, setState] = useState<SaveFeedbackState>('idle');
  const [feedback, setFeedback] = useState<{ hot: string[]; restart: string[]; msg: string }>({
    hot: [],
    restart: [],
    msg: '',
  });
  const [budgetConfirm, setBudgetConfirm] = useState<LlmGlobalUpdate | null>(null);

  const ttlKeys = Object.keys(global.cacheTtlSeconds);

  const draft = (): { body: LlmGlobalUpdate; changed: string[] } => {
    const body: LlmGlobalUpdate = {};
    const changed: string[] = [];
    const budgetNum = Number(budget);
    if (budgetNum !== global.dailyTokenBudgetPerUser) {
      body.dailyTokenBudgetPerUser = budgetNum;
      changed.push('dailyTokenBudgetPerUser');
    }
    const ratioNum = Number(ratio);
    if (ratioNum !== global.budgetWarnRatio) {
      body.budgetWarnRatio = ratioNum;
      changed.push('budgetWarnRatio');
    }
    const timeoutNum = Number(timeout);
    if (timeoutNum !== global.timeoutSeconds) {
      body.timeoutSeconds = timeoutNum;
      changed.push('timeoutSeconds');
    }
    const retryNum = Number(retry);
    if (retryNum !== global.retry) {
      body.retry = retryNum;
      changed.push('retry');
    }
    const nextTtl = { ...global.cacheTtlSeconds };
    let ttlChanged = false;
    for (const key of ttlKeys) {
      const value = Number(ttl[key] ?? global.cacheTtlSeconds[key]);
      if (value !== global.cacheTtlSeconds[key]) {
        nextTtl[key] = value;
        ttlChanged = true;
      }
    }
    if (ttlChanged) {
      body.cacheTtlSeconds = nextTtl;
      changed.push('cacheTtlSeconds');
    }
    return { body, changed };
  };

  const dirty = draft().changed.length > 0;

  const validate = (): boolean => {
    const next: Record<string, string> = {};
    next.budget = positiveIntError(budget) ?? '';
    next.ratio = ratioError(ratio) ?? '';
    next.timeout = positiveIntError(timeout) ?? '';
    next.retry = intRangeError(retry, 0, 3) ?? '';
    for (const key of ttlKeys) {
      next[key] = positiveIntError(ttl[key] ?? String(global.cacheTtlSeconds[key])) ?? '';
    }
    setErrors(next);
    return Object.values(next).every((msg) => msg === '');
  };

  const doSave = useCallback(
    async (body: LlmGlobalUpdate, changed: string[]) => {
      setState('saving');
      try {
        const saved = await patchLlmGlobal({
          ...body,
          expectedUpdatedAt: global.updatedAt ?? undefined,
        });
        onSaved(saved);
        setForm({}); // 服务端已接受新值，清空本地覆盖
        const { hot, restart } = splitByEffect(changed, global.effectiveModes);
        setFeedback({ hot, restart, msg: '' });
        setState('success');
      } catch (err) {
        setFeedback({ hot: [], restart: [], msg: messageOf(err, '保存失败，请重试') });
        setState('error');
      }
    },
    [global, onSaved],
  );

  const handleSave = async () => {
    if (!validate()) {
      return;
    }
    const { body, changed } = draft();
    if (changed.length === 0) {
      return;
    }
    // 预算二次确认（UI 方案 §3.2 交互 3）：新预算低于今日已用 → 保存后新调用将被拦截
    if (
      body.dailyTokenBudgetPerUser !== undefined &&
      body.dailyTokenBudgetPerUser < global.todayUsedTokens
    ) {
      setBudgetConfirm(body);
      return;
    }
    await doSave(body, changed);
  };

  return (
    <Card data-testid="llm-config-global-card">
      <CardHeader>
        <CardTitle className="text-base">全局参数</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <label className="flex flex-col gap-1 text-sm">
            <span>日预算（token/日）</span>
            <Input
              value={budget}
              onChange={(e) => setBudget(e.target.value)}
              aria-label="日预算"
              data-testid="llm-config-budget-input"
            />
            {errors.budget ? (
              <span className="text-xs text-destructive" role="alert">{errors.budget}</span>
            ) : null}
            <span className="text-xs text-muted-foreground">
              今日已用 {global.todayUsedTokens.toLocaleString('zh-CN')} token，超预算新调用将被拦截
            </span>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span>告警阈值（取值 (0,1]）</span>
            <Input
              value={ratio}
              onChange={(e) => setRatio(e.target.value)}
              aria-label="告警阈值"
              data-testid="llm-config-ratio-input"
            />
            {errors.ratio ? (
              <span className="text-xs text-destructive" role="alert">{errors.ratio}</span>
            ) : null}
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span>超时（秒）</span>
            <Input
              value={timeout}
              onChange={(e) => setTimeoutSec(e.target.value)}
              aria-label="超时秒数"
              data-testid="llm-config-timeout-input"
            />
            {errors.timeout ? (
              <span className="text-xs text-destructive" role="alert">{errors.timeout}</span>
            ) : null}
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span>重试（次）</span>
            <Input
              value={retry}
              onChange={(e) => setRetry(e.target.value)}
              aria-label="重试次数"
              data-testid="llm-config-retry-input"
            />
            {errors.retry ? (
              <span className="text-xs text-destructive" role="alert">{errors.retry}</span>
            ) : null}
          </label>
          {ttlKeys.map((key) => (
            <label key={key} className="flex flex-col gap-1 text-sm">
              <span>
                {TTL_SCENE_LABELS[key] ?? key} 缓存 TTL（秒）
              </span>
              <Input
                value={ttl[key] ?? String(global.cacheTtlSeconds[key])}
                onChange={(e) => setTtl(key, e.target.value)}
                aria-label={`${TTL_SCENE_LABELS[key] ?? key} 缓存 TTL`}
                data-testid={`llm-config-ttl-${key}`}
              />
              {errors[key] ? (
                <span className="text-xs text-destructive" role="alert">{errors[key]}</span>
              ) : null}
            </label>
          ))}
          <div className="flex flex-col gap-1 text-sm">
            <span className="flex items-center gap-2">
              缓存上限（条）
              <EffectBadge mode={global.effectiveModes.cacheMaximumSize} />
            </span>
            <Input value={String(global.cacheMaximumSize)} disabled aria-label="缓存上限" />
            <span className="text-xs text-muted-foreground">只读展示，调整需改配置并重启</span>
          </div>
        </div>
        <div className="flex flex-col gap-2">
          <div>
            <Button
              size="sm"
              disabled={!dirty || state === 'saving'}
              onClick={() => void handleSave()}
              data-testid="llm-config-global-save"
            >
              {state === 'saving' ? '保存中…' : '保存全局参数'}
            </Button>
          </div>
          <SaveFeedbackBar
            state={state}
            hotFields={feedback.hot}
            restartFields={feedback.restart}
            message={feedback.msg}
            testId="llm-config-global-feedback"
          />
        </div>
        <Dialog
          open={budgetConfirm !== null}
          title="预算低于今日已用"
          description={`新日预算 ${budget} token 低于今日已用 ${global.todayUsedTokens.toLocaleString('zh-CN')} token，保存后新调用将被预算守卫拦截，确认？`}
          onClose={() => setBudgetConfirm(null)}
          footer={
            <>
              <Button variant="outline" size="sm" onClick={() => setBudgetConfirm(null)}>
                取消
              </Button>
              <Button
                size="sm"
                data-testid="llm-config-budget-confirm"
                onClick={() => {
                  const body = budgetConfirm;
                  setBudgetConfirm(null);
                  if (body) {
                    const changed: string[] = [];
                    if (body.dailyTokenBudgetPerUser !== undefined) changed.push('dailyTokenBudgetPerUser');
                    void doSave(body, changed);
                  }
                }}
              >
                仍要保存
              </Button>
            </>
          }
        />
      </CardContent>
    </Card>
  );
}

// —— Provider 卡 ——

interface ProviderCardProps {
  provider: LlmProviderConfigView;
  apiKeyWriteEnabled: boolean;
  onSaved: (next: LlmProviderConfigView) => void;
  onReload: () => void;
}

function ProviderCard({ provider, apiKeyWriteEnabled, onSaved, onReload }: ProviderCardProps) {
  // 表单为「服务端值 + 用户覆盖」派生（不在 effect 里同步 setState）：覆盖仅在保存成功后整体清空
  const [form, setForm] = useState<{ model?: string; inputPrice?: string; outputPrice?: string }>({});
  const model = form.model ?? provider.model;
  const inputPrice = form.inputPrice ?? String(provider.inputPricePerMillion);
  const outputPrice = form.outputPrice ?? String(provider.outputPricePerMillion);
  const setModel = (value: string) => setForm((f) => ({ ...f, model: value }));
  const setInputPrice = (value: string) => setForm((f) => ({ ...f, inputPrice: value }));
  const setOutputPrice = (value: string) => setForm((f) => ({ ...f, outputPrice: value }));

  const [errors, setErrors] = useState<Record<string, string>>({});
  const [state, setState] = useState<SaveFeedbackState>('idle');
  const [feedback, setFeedback] = useState<{ hot: string[]; restart: string[]; msg: string }>({
    hot: [],
    restart: [],
    msg: '',
  });
  const [keyDialogOpen, setKeyDialogOpen] = useState(false);
  const [keyInput, setKeyInput] = useState('');
  const [keyError, setKeyError] = useState<string | null>(null);
  const [keySaving, setKeySaving] = useState(false);
  const [connectivity, setConnectivity] = useState<
    'idle' | 'testing' | { ok: boolean; latencyMillis: number | null; error: string | null }
  >('idle');
  const [disableConfirm, setDisableConfirm] = useState(false);

  const dirty =
    model !== provider.model ||
    Number(inputPrice) !== provider.inputPricePerMillion ||
    Number(outputPrice) !== provider.outputPricePerMillion;

  const savePatch = async (body: LlmProviderUpdate, changed: string[]) => {
    setState('saving');
    try {
      const saved = await putLlmProvider(provider.name, {
        ...body,
        expectedUpdatedAt: provider.updatedAt ?? undefined,
      });
      onSaved(saved);
      setForm({}); // 服务端已接受新值，清空本地覆盖
      if (body.isDefault === true) {
        onReload(); // 互斥置反其他 provider，整页刷新
      }
      const { hot, restart } = splitByEffect(changed, provider.effectiveModes);
      setFeedback({ hot, restart, msg: '' });
      setState('success');
    } catch (err) {
      setFeedback({ hot: [], restart: [], msg: messageOf(err, '保存失败，请重试') });
      setState('error');
    }
  };

  const handleSave = async () => {
    const next: Record<string, string> = {};
    next.model = model.trim() === '' ? '模型不能为空' : '';
    next.inputPrice = nonNegativeError(inputPrice) ?? '';
    next.outputPrice = nonNegativeError(outputPrice) ?? '';
    setErrors(next);
    if (Object.values(next).some((msg) => msg !== '')) {
      return;
    }
    if (!dirty) {
      return;
    }
    const body: LlmProviderUpdate = {};
    const changed: string[] = [];
    if (model !== provider.model) {
      body.model = model.trim();
      changed.push('model');
    }
    if (Number(inputPrice) !== provider.inputPricePerMillion) {
      body.inputPricePerMillion = Number(inputPrice);
      changed.push('inputPricePerMillion');
    }
    if (Number(outputPrice) !== provider.outputPricePerMillion) {
      body.outputPricePerMillion = Number(outputPrice);
      changed.push('outputPricePerMillion');
    }
    await savePatch(body, changed);
  };

  const handleToggle = async (enabled: boolean) => {
    // 停用当前默认 provider 需二次确认（UI 方案 §3.2 交互 6）
    if (!enabled && provider.isDefault) {
      setDisableConfirm(true);
      return;
    }
    await savePatch({ enabled }, ['enabled']);
  };

  const handleSetDefault = () => void savePatch({ isDefault: true }, ['isDefault']);

  const handleSaveKey = async () => {
    if (keyInput.trim() === '') {
      setKeyError('留空保持不变，录入新 key 后保存');
      return;
    }
    setKeySaving(true);
    setKeyError(null);
    try {
      const saved = await putLlmApiKey(provider.name, keyInput, provider.updatedAt ?? undefined);
      onSaved(saved);
      setKeyDialogOpen(false);
      setKeyInput('');
      const { hot } = splitByEffect(['apiKey'], provider.effectiveModes);
      setFeedback({ hot, restart: [], msg: '' });
      setState('success');
    } catch (err) {
      setKeyError(messageOf(err, 'key 保存失败，请重试'));
    } finally {
      setKeySaving(false);
    }
  };

  const runConnectivity = async () => {
    setConnectivity('testing');
    try {
      setConnectivity(await testLlmConnectivity(provider.name));
    } catch (err) {
      setConnectivity({ ok: false, latencyMillis: null, error: messageOf(err, '测试请求失败') });
    }
  };

  const keyConfigured = provider.apiKey.status === 'CONFIGURED';

  return (
    <Card
      className={provider.enabled ? undefined : 'opacity-60'}
      data-testid={`llm-config-provider-${provider.name}`}
    >
      <CardHeader>
        <CardTitle className="flex flex-wrap items-center gap-2 text-base">
          <span className="font-medium">{provider.name}</span>
          {provider.isDefault ? (
            <Badge variant="secondary" data-testid={`llm-config-default-badge-${provider.name}`}>
              默认
            </Badge>
          ) : null}
          <span className="ml-auto flex items-center gap-2 text-sm text-muted-foreground">
            启用
            <Switch
              checked={provider.enabled}
              onCheckedChange={(next) => void handleToggle(next)}
              disabled={state === 'saving'}
              aria-label={`启用 ${provider.name}`}
              data-testid={`llm-config-enabled-${provider.name}`}
            />
          </span>
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <label className="flex flex-col gap-1 text-sm">
          <span className="flex items-center gap-2">
            模型
            <EffectBadge mode={provider.effectiveModes.model} />
          </span>
          <Input
            value={model}
            onChange={(e) => setModel(e.target.value)}
            aria-label={`${provider.name} 模型`}
            data-testid={`llm-config-model-${provider.name}`}
          />
          {errors.model ? (
            <span className="text-xs text-destructive" role="alert">{errors.model}</span>
          ) : null}
        </label>
        <div className="grid grid-cols-2 gap-3">
          <label className="flex flex-col gap-1 text-sm">
            <span>输入单价（元/百万 token）</span>
            <Input
              value={inputPrice}
              onChange={(e) => setInputPrice(e.target.value)}
              aria-label={`${provider.name} 输入单价`}
              data-testid={`llm-config-input-price-${provider.name}`}
            />
            {errors.inputPrice ? (
              <span className="text-xs text-destructive" role="alert">{errors.inputPrice}</span>
            ) : null}
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span>输出单价（元/百万 token）</span>
            <Input
              value={outputPrice}
              onChange={(e) => setOutputPrice(e.target.value)}
              aria-label={`${provider.name} 输出单价`}
              data-testid={`llm-config-output-price-${provider.name}`}
            />
            {errors.outputPrice ? (
              <span className="text-xs text-destructive" role="alert">{errors.outputPrice}</span>
            ) : null}
          </label>
        </div>
        <p className="text-xs text-muted-foreground">
          单价允许 0（免费档）；修改只影响新调用，历史成本不重算
        </p>
        <div className="flex flex-col gap-1 text-sm">
          <span className="text-xs text-muted-foreground">fallback（只读）</span>
          <span data-testid={`llm-config-fallback-${provider.name}`}>{provider.fallback ?? '—'}</span>
        </div>
        <div className="flex flex-col gap-1 text-sm">
          <span className="flex items-center gap-2 text-xs text-muted-foreground">
            端点
            <EffectBadge mode={provider.effectiveModes.baseUrl} />
          </span>
          <span className="truncate text-xs" title={provider.baseUrl ?? undefined}>
            {provider.baseUrl ?? '—'}
          </span>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-sm">API Key</span>
          {keyConfigured ? (
            <Badge variant="secondary" data-testid={`llm-config-key-badge-${provider.name}`}>
              已配置（尾 4 位 ****{provider.apiKey.last4}，来源 {provider.apiKey.source}）
            </Badge>
          ) : (
            <Badge variant="outline" data-testid={`llm-config-key-badge-${provider.name}`}>
              未配置
            </Badge>
          )}
          <Button
            variant="outline"
            size="sm"
            disabled={!apiKeyWriteEnabled}
            onClick={() => {
              setKeyInput('');
              setKeyError(null);
              setKeyDialogOpen(true);
            }}
            data-testid={`llm-config-key-button-${provider.name}`}
          >
            {keyConfigured ? '更换' : '录入'}
          </Button>
          {!apiKeyWriteEnabled ? (
            <span className="text-xs text-muted-foreground">
              未配置 CONFIG_SECRET，key 暂走环境变量（配置 ≥32 字节环境变量并重启后可录入）
            </span>
          ) : null}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            disabled={connectivity === 'testing' || !provider.enabled}
            onClick={() => void runConnectivity()}
            data-testid={`llm-config-connect-${provider.name}`}
          >
            {connectivity === 'testing' ? '测试中…' : '连通性测试'}
          </Button>
          {provider.enabled && !provider.isDefault ? (
            <Button variant="outline" size="sm" onClick={handleSetDefault} data-testid={`llm-config-set-default-${provider.name}`}>
              设为默认
            </Button>
          ) : null}
          <Button
            size="sm"
            className="ml-auto"
            disabled={!dirty || state === 'saving'}
            onClick={() => void handleSave()}
            data-testid={`llm-config-save-${provider.name}`}
          >
            {state === 'saving' ? '保存中…' : '保存本条'}
          </Button>
        </div>
        {connectivity !== 'idle' && connectivity !== 'testing' ? (
          <p
            className={
              connectivity.ok
                ? 'text-xs text-emerald-400'
                : 'text-xs text-rose-400'
            }
            data-testid={`llm-config-connect-result-${provider.name}`}
            role="status"
          >
            {connectivity.ok
              ? `连接成功 · ${connectivity.latencyMillis}ms`
              : `失败：${connectivity.error ?? '未知原因'}`}
          </p>
        ) : null}
        <SaveFeedbackBar
          state={state}
          hotFields={feedback.hot}
          restartFields={feedback.restart}
          message={feedback.msg}
          testId={`llm-config-feedback-${provider.name}`}
        />
        <Dialog
          open={keyDialogOpen}
          title={`${provider.name} API Key`}
          description="只写不回显：保存后仅展示脱敏态，明文不写入日志。"
          onClose={() => setKeyDialogOpen(false)}
          footer={
            <>
              <Button variant="outline" size="sm" onClick={() => setKeyDialogOpen(false)}>
                取消
              </Button>
              <Button
                size="sm"
                disabled={keySaving || keyInput.trim() === ''}
                onClick={() => void handleSaveKey()}
                data-testid={`llm-config-key-save-${provider.name}`}
              >
                {keySaving ? '保存中…' : '保存'}
              </Button>
            </>
          }
        >
          <Input
            type="password"
            autoComplete="new-password"
            placeholder="留空保持不变"
            value={keyInput}
            onChange={(e) => setKeyInput(e.target.value)}
            aria-label="新 API key"
            data-testid={`llm-config-key-input-${provider.name}`}
          />
          {keyError ? (
            <p className="text-xs text-destructive" role="alert">
              {keyError}
            </p>
          ) : null}
        </Dialog>
        <Dialog
          open={disableConfirm}
          title="停用默认 provider"
          description="停用后平台将无默认 provider，AI 调用将无法选择首选模型（可先在其他卡「设为默认」）。确认停用？"
          onClose={() => setDisableConfirm(false)}
          footer={
            <>
              <Button variant="outline" size="sm" onClick={() => setDisableConfirm(false)}>
                取消
              </Button>
              <Button
                size="sm"
                onClick={() => {
                  setDisableConfirm(false);
                  void savePatch({ enabled: false }, ['enabled']);
                }}
                data-testid={`llm-config-disable-confirm-${provider.name}`}
              >
                确认停用
              </Button>
            </>
          }
        />
      </CardContent>
    </Card>
  );
}

// —— 页面 ——

/**
 * LLM 模型配置页（T39，#/llm-config，UI 方案 §3.2）。
 * 全局参数卡 + provider 卡 ×4 双列网格；保存按分区（D6），生效范围按接口 effectiveMode
 * 逐项枚举（D3）；key 只写不回显（§4.5）；CONFIG_SECRET 未配置时 key 区降级只读。
 * 三态：加载骨架 / 空态（无 provider 属异常）/ 整页错误重试；401 由 http 层统一跳登录。
 */
export function LlmConfig() {
  const [view, setView] = useState<LlmConfigView | null>(null);
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
      const data = await getLlmConfig(ctrl.signal);
      if (ctrl.signal.aborted) return;
      setView(data);
    } catch (err) {
      if (ctrl.signal.aborted) return;
      setError(messageOf(err, '模型配置加载失败'));
    } finally {
      if (!ctrl.signal.aborted) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
    return () => abortRef.current?.abort();
  }, [load]);

  const applyGlobal = (next: LlmGlobalConfigView) => {
    setView((prev) => (prev ? { ...prev, global: next } : prev));
  };

  const applyProvider = (next: LlmProviderConfigView) => {
    setView((prev) =>
      prev
        ? {
            ...prev,
            providers: prev.providers.map((p) => (p.name === next.name ? next : p)),
          }
        : prev,
    );
  };

  return (
    <main className="mx-auto w-full max-w-6xl p-4 sm:p-6" data-testid="llm-config-page">
      <header className="mb-4">
        <h1 className="text-xl font-medium">模型配置</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          单价 / 预算 / 告警 / 缓存 / 超时前端可改；API Key 只写不回显
        </p>
      </header>

      {loading ? (
        <div className="flex flex-col gap-4" data-testid="llm-config-loading">
          <Skeleton className="h-48 w-full" />
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
            {Array.from({ length: 4 }, (_, i) => (
              <Skeleton key={i} className="h-64 w-full" />
            ))}
          </div>
        </div>
      ) : error ? (
        <div className="flex flex-col items-start gap-2" data-testid="llm-config-error">
          <p className="text-sm text-destructive" role="alert">
            {error}
          </p>
          <Button variant="outline" size="sm" onClick={() => void load()} data-testid="llm-config-retry">
            重试
          </Button>
        </div>
      ) : view ? (
        <div className="flex flex-col gap-4">
          <GlobalCard global={view.global} onSaved={applyGlobal} />
          {view.providers.length === 0 ? (
            <p
              className="py-10 text-center text-sm text-muted-foreground"
              data-testid="llm-config-empty"
            >
              暂无 provider 配置（新增条目不在本期范围）
            </p>
          ) : (
            <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
              {view.providers.map((provider) => (
                <ProviderCard
                  key={provider.name}
                  provider={provider}
                  apiKeyWriteEnabled={view.apiKeyWriteEnabled}
                  onSaved={applyProvider}
                  onReload={() => void load()}
                />
              ))}
            </div>
          )}
        </div>
      ) : null}
    </main>
  );
}

export default LlmConfig;
