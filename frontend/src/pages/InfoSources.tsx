import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '@/api/http';
import {
  archiveInfoSource,
  createInfoSource,
  getInfoSources,
  patchInfoSource,
  pollInfoSourceNow,
  restoreInfoSource,
  testInfoSourceConnectivity,
} from '@/api/infoSource';
import { SaveFeedbackBar, type SaveFeedbackState } from '@/components/config/SaveFeedbackBar';
import { Switch } from '@/components/config/Switch';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import type {
  CreatableAdapterType,
  InfoSourceCardView,
  InfoSourceConfigPayload,
  InfoSourceConnectivityResult,
  InfoSourcesView,
} from '@/types/infoSource';

// 资讯源管理页（M13 T105，#/info-sources 全站第 14 页——UI 设计 §3）。
// 卡片分组网格（category 分组 + 归档区）/五态徽章/新增单 Dialog 类型切换字段集/JSON 轻量映射/
// 编辑热生效/启停/软删二次确认/恢复/立即抓取 202+3s 轻轮询/连通性测试/新增保存后引导条。
// 刷新策略（UI §6.4）：编辑/启停/归档/恢复按卡局部更新；仅手动抓取触发 3s 轻轮询（上限 30s，document.hidden 暂停）。

const CATEGORIES = ['快讯', '媒体', '政策', '宏观', '国际', '自建'] as const;
const DEFAULT_CATEGORY = '自建';
const DEFAULT_INTERVAL = 15;

/** 手动抓取轻轮询参数（UI D6）。 */
const POLL_REFRESH_MILLIS = 3_000;
const POLL_REFRESH_CAP_MILLIS = 30_000;

function messageOf(err: unknown, fallback: string): string {
  return err instanceof ApiError ? err.msg : fallback;
}

function isInFlightError(err: unknown): boolean {
  return err instanceof ApiError && err.code === 30074;
}

function formatTime(iso: string | null): string {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  return `${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(
    date.getHours(),
  ).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
}

/** 本轮新增数（last_round_detail 段式 new=n）。 */
function newOfDetail(detail: string | null): number | null {
  const matched = detail?.match(/new=(\d+)/);
  return matched ? Number(matched[1]) : null;
}

function truncateText(text: string, max: number): string {
  return text.length <= max ? text : `${text.slice(0, max)}…`;
}

/** 类型徽章（RSS=sky / JSON=violet / 预置=amber，纯分类语义）。 */
function typeBadgeOf(adapterType: InfoSourceCardView['adapterType']): {
  label: string;
  className: string;
} {
  switch (adapterType) {
    case 'rss':
      return { label: 'RSS', className: 'bg-sky-500/15 text-sky-400' };
    case 'json_api':
      return { label: 'JSON', className: 'bg-violet-500/15 text-violet-400' };
    default:
      return { label: '预置', className: 'bg-amber-500/15 text-amber-400' };
  }
}

/** 最近一轮轮询徽章矩阵（UI §4.2：成功/抓取中/失败/退避中/暂未抓取）。 */
function statusBadgeOf(
  card: InfoSourceCardView,
  nowMillis: number,
  polling: boolean,
): { text: string; className: string; title?: string } {
  if (polling) {
    return { text: '抓取中…', className: 'bg-amber-500/15 text-amber-400 animate-pulse' };
  }
  const state = card.state;
  if (!state.lastAttemptAt) {
    return { text: '暂未抓取', className: 'bg-muted text-muted-foreground' };
  }
  const backoffAt = state.backoffUntil ? Date.parse(state.backoffUntil) : 0;
  const attemptAt = Date.parse(state.lastAttemptAt);
  const successAt = state.lastSuccessAt ? Date.parse(state.lastSuccessAt) : 0;
  if (state.consecutiveFailures >= 2 && backoffAt > nowMillis) {
    return {
      text: `连续失败 ${state.consecutiveFailures} 次 · ${formatTime(state.backoffUntil)} 后重试`,
      className: 'bg-amber-500/15 text-amber-400',
    };
  }
  if (state.lastError && attemptAt > successAt) {
    return {
      text: `失败 · ${truncateText(state.lastError, 24)}`,
      className: 'bg-rose-500/15 text-rose-400',
      title: state.lastError,
    };
  }
  const added = newOfDetail(state.lastRoundDetail);
  const addedText = added == null ? '' : ` · 新${added}`;
  return {
    text: `成功 ${formatTime(state.lastSuccessAt)}${addedText}`,
    className: 'bg-emerald-500/15 text-emerald-400',
  };
}

// —— 源表单（新增/编辑共用，UI §3.3 单步；编辑时类型分段只读） ——

interface SourceFormDialogProps {
  open: boolean;
  editing: InfoSourceCardView | null;
  onClose: () => void;
  onSaved: (card: InfoSourceCardView) => void;
  /** 新增保存成功后对新建源立即触发手动抓取（§4.3 引导条动作）。 */
  onPollNow: (card: InfoSourceCardView) => void;
}

interface SourceFormState {
  type: CreatableAdapterType;
  name: string;
  category: string;
  url: string;
  interval: string;
  listPath: string;
  mappingTitle: string;
  mappingTime: string;
  mappingId: string;
  mappingSummary: string;
  stripPrefix: string;
  userAgent: string;
  referer: string;
}

function emptyForm(): SourceFormState {
  return {
    type: 'rss',
    name: '',
    category: DEFAULT_CATEGORY,
    url: '',
    interval: String(DEFAULT_INTERVAL),
    listPath: '',
    mappingTitle: '',
    mappingTime: '',
    mappingId: '',
    mappingSummary: '',
    stripPrefix: '',
    userAgent: '',
    referer: '',
  };
}

function formOf(card: InfoSourceCardView): SourceFormState {
  const mappingOf = (target: string) =>
    card.config.itemMapping.find((m) => m.target === target)?.source ?? '';
  return {
    type: card.adapterType === 'json_api' ? 'json_api' : 'rss',
    name: card.name,
    category: card.category,
    url: card.endpoint,
    interval: String(card.intervalMinutes),
    listPath: card.config.listPath ?? '',
    mappingTitle: mappingOf('title'),
    mappingTime: mappingOf('publishedAt'),
    mappingId: mappingOf('externalId'),
    mappingSummary: mappingOf('summary'),
    stripPrefix: card.config.stripPrefix ?? '',
    userAgent: card.config.headers['User-Agent'] ?? '',
    referer: card.config.headers['Referer'] ?? '',
  };
}

/** 轻量映射表单 → config 载荷（UI §6.5：RSS 不带映射键；transform 按目标字段缺省）。 */
function configPayloadOf(form: SourceFormState): InfoSourceConfigPayload | undefined {
  if (form.type === 'rss') {
    return undefined;
  }
  const itemMapping = [
    { source: form.mappingTitle.trim(), target: 'title', transform: 'to_string' },
    {
      source: form.mappingTime.trim(),
      target: 'publishedAt',
      transform: 'to_iso_datetime',
    },
    { source: form.mappingId.trim(), target: 'externalId', transform: 'to_string' },
    { source: form.mappingSummary.trim(), target: 'summary', transform: 'strip_html' },
  ].filter((m) => m.source !== '');
  const headers: Record<string, string> = {};
  if (form.userAgent.trim()) headers['User-Agent'] = form.userAgent.trim();
  if (form.referer.trim()) headers.Referer = form.referer.trim();
  return {
    listPath: form.listPath.trim() || undefined,
    stripPrefix: form.stripPrefix.trim() || undefined,
    itemMapping,
    headers,
  };
}

function formErrorsOf(form: SourceFormState): Partial<Record<keyof SourceFormState, string>> {
  const errors: Partial<Record<keyof SourceFormState, string>> = {};
  if (form.name.trim() === '') errors.name = '名称不能为空';
  if (!/^(https?):\/\/\S+$/.test(form.url.trim())) {
    errors.url = 'URL 须以 http(s):// 开头且格式合法';
  }
  const interval = Number(form.interval);
  if (!Number.isInteger(interval) || interval < 1 || interval > 60) {
    errors.interval = '间隔须为 1~60 的整数（分钟）';
  }
  if (form.type === 'json_api' && form.mappingTitle.trim() === '') {
    errors.mappingTitle = '标题映射字段必填（诊断映射错误的最低要求）';
  }
  return errors;
}

function SourceFormDialog({ open, editing, onClose, onSaved, onPollNow }: SourceFormDialogProps) {
  const [form, setForm] = useState<SourceFormState>(emptyForm);
  const [errors, setErrors] = useState<Partial<Record<keyof SourceFormState, string>>>({});
  const [saving, setSaving] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);
  // 新增保存成功：不自动关，顶部引导条 + 按钮组变 [立即抓取][完成]（UI §4.3）
  const [created, setCreated] = useState<InfoSourceCardView | null>(null);
  const [advancedOpen, setAdvancedOpen] = useState(false);

  useEffect(() => {
    if (open) {
      setForm(editing ? formOf(editing) : emptyForm());
      setErrors({});
      setServerError(null);
      setCreated(null);
      setAdvancedOpen(false);
      setSaving(false);
    }
  }, [open, editing]);

  const set = (patch: Partial<SourceFormState>) => setForm((prev) => ({ ...prev, ...patch }));
  const isJson = form.type === 'json_api';

  const handleSave = async () => {
    const validation = formErrorsOf(form);
    setErrors(validation);
    if (Object.keys(validation).length > 0) return;
    setSaving(true);
    setServerError(null);
    try {
      if (editing) {
        const saved = await patchInfoSource(editing.id, {
          name: form.name.trim(),
          category: form.category,
          endpoint: form.url.trim(),
          intervalMinutes: Number(form.interval),
          config: configPayloadOf(form),
        });
        onSaved(saved);
        onClose();
      } else {
        const saved = await createInfoSource({
          name: form.name.trim(),
          category: form.category,
          adapterType: form.type,
          endpoint: form.url.trim(),
          intervalMinutes: Number(form.interval),
          enabled: true,
          config: configPayloadOf(form),
        });
        setCreated(saved);
        onSaved(saved);
      }
    } catch (err) {
      // 30072/30075 内联回显后端具体原因，表单保持原值（UI §6.3）
      setServerError(messageOf(err, '保存失败，请重试'));
    } finally {
      setSaving(false);
    }
  };

  const handleFinish = () => {
    onClose();
  };

  return (
    <Dialog
      open={open}
      title={editing ? `编辑 · ${editing.name}` : '新增资讯源'}
      description={
        editing
          ? '源配置改动下一轮抓取即按新参数执行（热生效）；类型不可切换。'
          : '保存并启用后首次抓取将在约 1 分钟内执行（调度周期 60s）。'
      }
      onClose={onClose}
      footer={
        created ? (
          <>
            <Button
              size="sm"
              onClick={() => {
                onPollNow(created);
                onClose();
              }}
              data-testid="info-source-add-poll"
            >
              立即抓取
            </Button>
            <Button variant="outline" size="sm" onClick={handleFinish} data-testid="info-source-add-done">
              完成
            </Button>
          </>
        ) : (
          <>
            <Button variant="outline" size="sm" onClick={onClose}>
              取消
            </Button>
            <Button
              size="sm"
              disabled={saving}
              onClick={() => void handleSave()}
              data-testid="info-source-add-save"
            >
              {saving ? '保存中…' : editing ? '保存' : '保存并启用'}
            </Button>
          </>
        )
      }
    >
      {created ? (
        <p
          className="rounded bg-emerald-500/15 px-3 py-2 text-sm text-emerald-400"
          aria-live="polite"
          data-testid="info-source-add-guide"
        >
          已启用 · 首次抓取将在约 1 分钟内执行（调度周期 60s），可点「立即抓取」立即验证出数。
        </p>
      ) : (
        <>
          {serverError ? (
            <p className="rounded bg-rose-500/15 px-3 py-2 text-sm text-rose-400" role="alert">
              {serverError}
            </p>
          ) : null}
          <div className="flex flex-wrap items-center gap-3">
            <span className="text-sm text-muted-foreground">类型</span>
            <div className="inline-flex overflow-hidden rounded-md border" data-testid="info-source-add-type">
              <Button
                type="button"
                size="sm"
                variant={form.type === 'rss' ? 'default' : 'ghost'}
                className="rounded-none"
                disabled={editing != null}
                onClick={() => set({ type: 'rss' })}
                data-testid="info-source-add-type-rss"
              >
                RSS
              </Button>
              <Button
                type="button"
                size="sm"
                variant={form.type === 'json_api' ? 'default' : 'ghost'}
                className="rounded-none"
                disabled={editing != null}
                onClick={() => set({ type: 'json_api' })}
                data-testid="info-source-add-type-json"
              >
                JSON
              </Button>
            </div>
            {editing ? (
              <span className="text-xs text-muted-foreground">类型不可切换</span>
            ) : null}
          </div>
          <label className="flex flex-col gap-1 text-sm">
            <span>名称</span>
            <Input
              value={form.name}
              onChange={(e) => set({ name: e.target.value })}
              aria-label="源名称"
              data-testid="info-source-add-name"
            />
            {errors.name ? (
              <span className="text-xs text-destructive" role="alert">
                {errors.name}
              </span>
            ) : null}
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span>分组</span>
            <select
              value={form.category}
              onChange={(e) => set({ category: e.target.value })}
              aria-label="分组"
              data-testid="info-source-add-category"
              className="h-9 rounded-lg border border-input bg-input/30 px-3 text-sm text-foreground shadow-sm transition-colors focus-visible:border-ring focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
            >
              {CATEGORIES.map((category) => (
                <option key={category} value={category}>
                  {category}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span>端点 URL</span>
            <Input
              value={form.url}
              onChange={(e) => set({ url: e.target.value })}
              aria-label="端点 URL"
              data-testid="info-source-add-url"
            />
            {errors.url ? (
              <span className="text-xs text-destructive" role="alert">
                {errors.url}
              </span>
            ) : null}
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span>轮询间隔（分钟，1~60）</span>
            <Input
              value={form.interval}
              onChange={(e) => set({ interval: e.target.value })}
              aria-label="轮询间隔分钟"
              data-testid="info-source-add-interval"
              className="w-28"
            />
            {errors.interval ? (
              <span className="text-xs text-destructive" role="alert">
                {errors.interval}
              </span>
            ) : null}
          </label>
          {isJson ? (
            <>
              <label className="flex flex-col gap-1 text-sm">
                <span>条目数组路径（可空 = 根数组）</span>
                <Input
                  value={form.listPath}
                  onChange={(e) => set({ listPath: e.target.value })}
                  aria-label="条目数组路径"
                  data-testid="info-source-add-list-path"
                  placeholder="result.data"
                />
              </label>
              <fieldset className="flex flex-col gap-2 rounded-lg border p-3">
                <legend className="px-1 text-xs text-muted-foreground">
                  字段映射（源字段名；标题必填）
                </legend>
                <div className="grid grid-cols-2 gap-2">
                  <label className="flex flex-col gap-1 text-sm">
                    <span>标题字段*</span>
                    <Input
                      value={form.mappingTitle}
                      onChange={(e) => set({ mappingTitle: e.target.value })}
                      aria-label="标题映射字段"
                      data-testid="info-source-add-mapping-title"
                      placeholder="title"
                    />
                    {errors.mappingTitle ? (
                      <span className="text-xs text-destructive" role="alert">
                        {errors.mappingTitle}
                      </span>
                    ) : null}
                  </label>
                  <label className="flex flex-col gap-1 text-sm">
                    <span>时间字段</span>
                    <Input
                      value={form.mappingTime}
                      onChange={(e) => set({ mappingTime: e.target.value })}
                      aria-label="时间映射字段"
                      data-testid="info-source-add-mapping-time"
                      placeholder="time"
                    />
                  </label>
                  <label className="flex flex-col gap-1 text-sm">
                    <span>ID 字段</span>
                    <Input
                      value={form.mappingId}
                      onChange={(e) => set({ mappingId: e.target.value })}
                      aria-label="ID 映射字段"
                      data-testid="info-source-add-mapping-id"
                      placeholder="id"
                    />
                  </label>
                  <label className="flex flex-col gap-1 text-sm">
                    <span>摘要字段</span>
                    <Input
                      value={form.mappingSummary}
                      onChange={(e) => set({ mappingSummary: e.target.value })}
                      aria-label="摘要映射字段"
                      data-testid="info-source-add-mapping-summary"
                      placeholder="content"
                    />
                  </label>
                </div>
              </fieldset>
              <div>
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  onClick={() => setAdvancedOpen((v) => !v)}
                  aria-expanded={advancedOpen}
                  data-testid="info-source-add-advanced-toggle"
                >
                  {advancedOpen ? '收起高级' : '高级（包装剥离 / 请求头）'}
                </Button>
                {advancedOpen ? (
                  <div className="mt-2 flex flex-col gap-2">
                    <label className="flex flex-col gap-1 text-sm">
                      <span>包装前缀（如 var newest=）</span>
                      <Input
                        value={form.stripPrefix}
                        onChange={(e) => set({ stripPrefix: e.target.value })}
                        aria-label="包装前缀"
                        data-testid="info-source-add-strip-prefix"
                      />
                    </label>
                    <label className="flex flex-col gap-1 text-sm">
                      <span>User-Agent</span>
                      <Input
                        value={form.userAgent}
                        onChange={(e) => set({ userAgent: e.target.value })}
                        aria-label="User-Agent"
                        data-testid="info-source-add-ua"
                      />
                    </label>
                    <label className="flex flex-col gap-1 text-sm">
                      <span>Referer</span>
                      <Input
                        value={form.referer}
                        onChange={(e) => set({ referer: e.target.value })}
                        aria-label="Referer"
                        data-testid="info-source-add-referer"
                      />
                    </label>
                  </div>
                ) : null}
              </div>
            </>
          ) : null}
        </>
      )}
    </Dialog>
  );
}

// —— 源卡片 ——

interface SourceCardProps {
  source: InfoSourceCardView;
  polling: boolean;
  nowMillis: number;
  onSaved: (next: InfoSourceCardView) => void;
  onEdit: (source: InfoSourceCardView) => void;
  onArchive: (source: InfoSourceCardView) => void;
  onPoll: (source: InfoSourceCardView) => void;
}

function SourceCard({
  source,
  polling,
  nowMillis,
  onSaved,
  onEdit,
  onArchive,
  onPoll,
}: SourceCardProps) {
  const code = source.sourceCode;
  const type = typeBadgeOf(source.adapterType);
  const status = statusBadgeOf(source, nowMillis, polling);
  const [feedback, setFeedback] = useState<{ state: SaveFeedbackState; msg: string; label: string }>(
    { state: 'idle', msg: '', label: '' },
  );
  const [connectivity, setConnectivity] = useState<
    'idle' | 'testing' | (InfoSourceConnectivityResult & { ok: boolean })
  >('idle');

  const doFeedback = async (action: () => Promise<InfoSourceCardView>, label: string) => {
    setFeedback({ state: 'saving', msg: '', label });
    try {
      const saved = await action();
      onSaved(saved);
      setFeedback({ state: 'success', msg: '', label });
    } catch (err) {
      setFeedback({ state: 'error', msg: messageOf(err, '操作失败，请重试'), label });
    }
  };

  const handleToggle = (enabled: boolean) => {
    void doFeedback(
      () => patchInfoSource(source.id, { enabled }),
      enabled ? '已启用 · 下一轮调度生效' : '已停用 · 下一轮调度摘除',
    );
  };

  const runConnectivity = async () => {
    setConnectivity('testing');
    try {
      const result = await testInfoSourceConnectivity(source.id);
      setConnectivity({ ...result, ok: result.reachable });
    } catch (err) {
      setConnectivity({
        ok: false,
        reachable: false,
        robotsAllowed: true,
        latencyMillis: null,
        parsedCount: null,
        error: messageOf(err, '测试请求失败'),
        sampleItems: [],
      });
    }
  };

  return (
    <Card
      className={source.enabled ? undefined : 'opacity-60'}
      data-testid={`info-source-card-${code}`}
    >
      <CardHeader>
        <CardTitle className="flex flex-wrap items-center gap-2 text-base">
          <span className="font-medium">{source.name}</span>
          <Badge className={type.className} data-testid={`info-source-type-${code}`}>
            {type.label}
          </Badge>
          <Badge
            className={status.className}
            title={status.title}
            data-testid={`info-source-status-${code}`}
          >
            {status.text}
          </Badge>
          <span className="ml-auto flex items-center gap-2 text-sm text-muted-foreground">
            启用
            <Switch
              checked={source.enabled}
              onCheckedChange={(next) => handleToggle(next)}
              disabled={feedback.state === 'saving'}
              aria-label={`启用 ${source.name}`}
              data-testid={`info-source-enabled-${code}`}
            />
          </span>
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
          <span className="text-muted-foreground">间隔 每 {source.intervalMinutes} 分钟</span>
          <span className="text-muted-foreground">今日 +{source.today.newCount}</span>
          <span className="max-w-full truncate text-xs" title={source.endpoint}>
            {source.endpoint}
          </span>
        </div>
        {source.preset ? (
          <p className="text-xs text-muted-foreground">
            预置适配 · adapter bean 只读（{source.adapterRef ?? 'preset'}）
          </p>
        ) : null}
        <div className="flex flex-wrap items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            disabled={connectivity === 'testing'}
            onClick={() => void runConnectivity()}
            data-testid={`info-source-connect-${code}`}
          >
            {connectivity === 'testing' ? '测试中…' : '测试连通'}
          </Button>
          <Button
            variant="outline"
            size="sm"
            disabled={polling}
            onClick={() => onPoll(source)}
            data-testid={`info-source-poll-${code}`}
          >
            {polling ? '抓取中…' : '立即抓取'}
          </Button>
          <Button variant="outline" size="sm" onClick={() => onEdit(source)} data-testid={`info-source-edit-${code}`}>
            编辑
          </Button>
          {!source.preset ? (
            <Button
              variant="outline"
              size="sm"
              className="ml-auto hover:text-rose-400"
              onClick={() => onArchive(source)}
              data-testid={`info-source-archive-${code}`}
            >
              停用并归档
            </Button>
          ) : null}
        </div>
        {connectivity !== 'idle' && connectivity !== 'testing' ? (
          <p
            className={connectivity.ok ? 'text-xs text-emerald-400' : 'text-xs text-rose-400'}
            role="status"
            title={connectivity.error ?? undefined}
            data-testid={`info-source-connect-result-${code}`}
          >
            {connectivity.ok
              ? `连通 · ${connectivity.latencyMillis}ms · 解析 ${connectivity.parsedCount} 条（robots ${
                  connectivity.robotsAllowed ? '允许' : '禁止'
                }）`
              : `失败：${connectivity.error ?? '未知原因'}`}
          </p>
        ) : null}
        <SaveFeedbackBar
          state={feedback.state}
          message={feedback.msg}
          successLabel={feedback.label || '已保存'}
          testId={`info-source-feedback-${code}`}
        />
      </CardContent>
    </Card>
  );
}

// —— 归档卡片（仅恢复 + 端点只读，UI §3.4） ——

interface ArchivedCardProps {
  source: InfoSourceCardView;
  onSaved: (next: InfoSourceCardView) => void;
}

function ArchivedCard({ source, onSaved }: ArchivedCardProps) {
  const code = source.sourceCode;
  const [feedback, setFeedback] = useState<{ state: SaveFeedbackState; msg: string }>({
    state: 'idle',
    msg: '',
  });
  const handleRestore = async () => {
    setFeedback({ state: 'saving', msg: '' });
    try {
      const restored = await restoreInfoSource(source.id);
      onSaved(restored);
      setFeedback({ state: 'success', msg: '' });
    } catch (err) {
      setFeedback({ state: 'error', msg: messageOf(err, '恢复失败，请重试') });
    }
  };
  return (
    <Card className="opacity-60" data-testid={`info-source-card-${code}`}>
      <CardHeader>
        <CardTitle className="flex flex-wrap items-center gap-2 text-base">
          <span className="font-medium">{source.name}</span>
          <Badge className="bg-muted text-muted-foreground">已归档</Badge>
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <p className="text-xs text-muted-foreground">归档中：停止调度，已入库条目保留不删</p>
        <span className="truncate text-xs text-muted-foreground" title={source.endpoint}>
          {source.endpoint}
        </span>
        <div className="flex flex-wrap items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            disabled={feedback.state === 'saving'}
            onClick={() => void handleRestore()}
            data-testid={`info-source-restore-${code}`}
          >
            恢复
          </Button>
          <SaveFeedbackBar
            state={feedback.state}
            message={feedback.msg}
            successLabel="已恢复 · 停用态（启用请开关确认时机）"
            testId={`info-source-feedback-${code}`}
          />
        </div>
      </CardContent>
    </Card>
  );
}

// —— 页面 ——

export function InfoSources() {
  const [view, setView] = useState<InfoSourcesView | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showArchived, setShowArchived] = useState(false);
  const [nowMillis, setNowMillis] = useState(() => Date.now());
  const [addOpen, setAddOpen] = useState(false);
  const [editing, setEditing] = useState<InfoSourceCardView | null>(null);
  const [archiveTarget, setArchiveTarget] = useState<InfoSourceCardView | null>(null);
  const [pollingCodes, setPollingCodes] = useState<Set<string>>(new Set());
  const abortRef = useRef<AbortController | null>(null);
  // 手动抓取轻轮询登记：baseline = 触发时 lastAttemptAt（出现变化即出终态）
  const pollTrackerRef = useRef<Map<string, { baseline: string | null; until: number }>>(new Map());
  const pollTimerRef = useRef<number | null>(null);

  const load = useCallback(async () => {
    abortRef.current?.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    try {
      const data = await getInfoSources(ctrl.signal);
      if (ctrl.signal.aborted) return;
      setView(data);
      setError(null);
    } catch (err) {
      if (ctrl.signal.aborted) return;
      setError(messageOf(err, '资讯源列表加载失败'));
    } finally {
      if (!ctrl.signal.aborted) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
    return () => abortRef.current?.abort();
  }, [load]);

  // 徽章时间基准周期对齐（退避截止/成功时刻展示不滞后）
  useEffect(() => {
    const timer = window.setInterval(() => setNowMillis(Date.now()), 30_000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(
    () => () => {
      if (pollTimerRef.current != null) window.clearInterval(pollTimerRef.current);
    },
    [],
  );

  /** 3s 轻轮询开关（document.hidden 暂停 = 隐藏期间跳过本轮不请求，D6）。 */
  const startPollRefresh = useCallback(() => {
    if (pollTimerRef.current != null) window.clearInterval(pollTimerRef.current);
    pollTimerRef.current = window.setInterval(() => {
      if (document.hidden) return;
      void load();
    }, POLL_REFRESH_MILLIS);
  }, [load]);

  const stopPollRefresh = useCallback(() => {
    if (pollTimerRef.current != null) {
      window.clearInterval(pollTimerRef.current);
      pollTimerRef.current = null;
    }
  }, []);

  /** 轮询登记收敛：lastAttemptAt 变化或超 30s 出「抓取中」态（D6）。 */
  const prunePolling = useCallback(
    (data: InfoSourcesView) => {
      const cards = [...data.groups.flatMap((g) => g.sources), ...data.archived];
      const tracker = pollTrackerRef.current;
      for (const [code, entry] of [...tracker.entries()]) {
        const card = cards.find((c) => c.sourceCode === code);
        const finished =
          card == null || card.state.lastAttemptAt !== entry.baseline || Date.now() > entry.until;
        if (finished) tracker.delete(code);
      }
      setPollingCodes(new Set(tracker.keys()));
      if (tracker.size > 0) {
        startPollRefresh();
      } else {
        stopPollRefresh();
      }
    },
    [startPollRefresh, stopPollRefresh],
  );

  // 列表刷新后收敛轮询登记（含手动 load 与轻轮询 load）
  useEffect(() => {
    if (view) prunePolling(view);
  }, [view, prunePolling]);

  const handlePoll = useCallback(
    async (source: InfoSourceCardView) => {
      const tracker = pollTrackerRef.current;
      const entry = tracker.get(source.sourceCode);
      const baseline = entry?.baseline ?? source.state.lastAttemptAt;
      tracker.set(source.sourceCode, {
        baseline,
        until: Date.now() + POLL_REFRESH_CAP_MILLIS,
      });
      setPollingCodes(new Set(tracker.keys()));
      startPollRefresh();
      try {
        await pollInfoSourceNow(source.id);
      } catch (err) {
        // 30074 在飞 / 重复点击：收敛为同一「抓取中…」态不弹错；其余失败退出抓取中态（D6）
        if (!isInFlightError(err)) {
          tracker.delete(source.sourceCode);
          setPollingCodes(new Set(tracker.keys()));
        }
      }
    },
    [startPollRefresh],
  );

  const applyCard = useCallback((next: InfoSourceCardView) => {
    setView((prev) => {
      if (!prev) return prev;
      const inActive = prev.groups.some((g) =>
        g.sources.some((s) => s.sourceCode === next.sourceCode),
      );
      const wasArchived = prev.archived.some((s) => s.sourceCode === next.sourceCode);
      if (wasArchived && !next.deleted) {
        // 恢复：归档区移除，以停用态回到所属分组（分组不存在则追加）
        const groupExists = prev.groups.some((g) => g.category === next.category);
        const groups = groupExists
          ? prev.groups.map((g) =>
              g.category === next.category ? { ...g, sources: [...g.sources, next] } : g,
            )
          : [...prev.groups, { category: next.category, sources: [next] }];
        return {
          groups,
          archived: prev.archived.filter((s) => s.sourceCode !== next.sourceCode),
        };
      }
      if (next.deleted) {
        return {
          groups: prev.groups.map((g) => ({
            ...g,
            sources: g.sources.filter((s) => s.sourceCode !== next.sourceCode),
          })),
          archived: inActive ? [...prev.archived, next] : prev.archived,
        };
      }
      // 新建源（尚不在任何分组）：追加到所属分组（分组缺失则新建）
      if (!inActive) {
        const groupExists = prev.groups.some((g) => g.category === next.category);
        return {
          groups: groupExists
            ? prev.groups.map((g) =>
                g.category === next.category ? { ...g, sources: [...g.sources, next] } : g,
              )
            : [...prev.groups, { category: next.category, sources: [next] }],
          archived: prev.archived,
        };
      }
      return {
        groups: prev.groups.map((g) => ({
          ...g,
          sources: g.sources.map((s) => (s.sourceCode === next.sourceCode ? next : s)),
        })),
        archived: prev.archived,
      };
    });
  }, []);

  const confirmArchive = async () => {
    const target = archiveTarget;
    setArchiveTarget(null);
    if (!target) return;
    try {
      const archived = await archiveInfoSource(target.id);
      applyCard(archived);
    } catch (err) {
      // 归档失败仅提示不迁移卡片（无卡内反馈句柄，置全局错误行）
      setError(messageOf(err, '归档失败，请重试'));
    }
  };

  const cards = view ? view.groups.flatMap((g) => g.sources) : [];

  return (
    <main className="mx-auto w-full max-w-6xl p-4 sm:p-6" data-testid="info-sources-page">
      <header className="mb-4">
        <h1 className="text-xl font-medium">资讯源管理</h1>
        <div className="mt-1 flex flex-wrap items-center gap-3">
          <p className="max-w-3xl text-sm text-muted-foreground">
            资讯源 7×24 分钟级轮询入库，本页管理源的启停 / 参数 / 新增 / 归档；按标的请求驱动的六源业务域仍在「数据源配置」页管理。
          </p>
          <Button size="sm" className="ml-auto" onClick={() => setAddOpen(true)} data-testid="info-sources-add">
            ＋新增源
          </Button>
        </div>
      </header>

      <label className="mb-3 flex items-center gap-2 text-sm text-muted-foreground">
        <input
          type="checkbox"
          checked={showArchived}
          onChange={(e) => setShowArchived(e.target.checked)}
          data-testid="info-sources-show-archived"
        />
        显示已归档源
      </label>

      {loading ? (
        <div className="flex flex-col gap-4" data-testid="info-sources-loading">
          <div className="flex flex-col gap-3">
            <Skeleton className="h-4 w-24" />
            <div className="grid grid-cols-1 gap-4 lg:grid-cols-2 2xl:grid-cols-3">
              {Array.from({ length: 6 }, (_, i) => (
                <Skeleton key={i} className="h-48 w-full" />
              ))}
            </div>
          </div>
        </div>
      ) : error && !view ? (
        <div className="flex flex-col items-start gap-2" data-testid="info-sources-error">
          <p className="text-sm text-destructive" role="alert">
            {error}
          </p>
          <Button variant="outline" size="sm" onClick={() => void load()} data-testid="info-sources-retry">
            重试
          </Button>
        </div>
      ) : view ? (
        <div className="flex flex-col gap-4">
          {cards.length === 0 && view.archived.length === 0 ? (
            <p
              className="py-10 text-center text-sm text-muted-foreground"
              data-testid="info-sources-empty"
            >
              暂无资讯源（异常场景，请检查后端种子）
            </p>
          ) : (
            view.groups.map((group) => (
              <section key={group.category} className="flex flex-col gap-3">
                <h2 className="text-xs text-muted-foreground">{group.category}</h2>
                <div className="grid grid-cols-1 gap-4 lg:grid-cols-2 2xl:grid-cols-3">
                  {group.sources.map((source) => (
                    <SourceCard
                      key={source.sourceCode}
                      source={source}
                      polling={pollingCodes.has(source.sourceCode)}
                      nowMillis={nowMillis}
                      onSaved={applyCard}
                      onEdit={setEditing}
                      onArchive={setArchiveTarget}
                      onPoll={(s) => void handlePoll(s)}
                    />
                  ))}
                </div>
              </section>
            ))
          )}
          {showArchived ? (
            <section className="flex flex-col gap-3">
              <h2 className="text-xs text-muted-foreground">已归档</h2>
              {view.archived.length === 0 ? (
                <p className="text-sm text-muted-foreground" data-testid="info-sources-archived-empty">
                  暂无归档源
                </p>
              ) : (
                <div className="grid grid-cols-1 gap-4 lg:grid-cols-2 2xl:grid-cols-3">
                  {view.archived.map((source) => (
                    <ArchivedCard key={source.sourceCode} source={source} onSaved={applyCard} />
                  ))}
                </div>
              )}
            </section>
          ) : null}
          {error ? (
            <p className="text-sm text-destructive" role="alert">
              {error}
            </p>
          ) : null}
        </div>
      ) : null}

      <SourceFormDialog
        open={addOpen || editing != null}
        editing={editing}
        onClose={() => {
          setAddOpen(false);
          setEditing(null);
        }}
        onSaved={applyCard}
        onPollNow={(card) => void handlePoll(card)}
      />

      <Dialog
        open={archiveTarget != null}
        title="停用并归档"
        description="停用并归档后该源停止调度、移入归档；已入库条目保留不删。确认归档？"
        onClose={() => setArchiveTarget(null)}
        footer={
          <>
            <Button variant="outline" size="sm" onClick={() => setArchiveTarget(null)}>
              取消
            </Button>
            <Button
              size="sm"
              onClick={() => void confirmArchive()}
              data-testid={
                archiveTarget ? `info-source-archive-confirm-${archiveTarget.sourceCode}` : undefined
              }
            >
              确认归档
            </Button>
          </>
        }
      />
    </main>
  );
}

export default InfoSources;
