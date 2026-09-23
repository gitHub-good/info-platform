// 模板编辑器（M5 T48，UI 方案 §3.3，页面域组件置 pages/）：
// 页内替换式编辑视图（D4）：底稿预填 + sessionStorage 草稿兜底（D11）+ MINOR/MAJOR 策略按钮与
// 预览号（D9）+ 纯 textarea 等宽编辑（D5）+ 占位符 sticky 对照栏（D6）+ 实时校验面板（300ms 防抖）
// + 三级校验保存流（硬校验禁用 / 移除项逐项勾选放行 / unknown 仅警告，D7）。

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ApiError } from '@/api/http';
import {
  createPromptTemplate,
  getPromptPlaceholders,
  getPromptTemplateDetail,
} from '@/api/promptTemplates';
import { PlaceholderPanel } from '@/components/prompt/PlaceholderPanel';
import { TemplateTextView } from '@/components/prompt/TemplateTextView';
import { SaveFeedbackBar, type SaveFeedbackState } from '@/components/config/SaveFeedbackBar';
import { Button } from '@/components/ui/button';
import { Dialog } from '@/components/ui/dialog';
import { Skeleton } from '@/components/ui/skeleton';
import {
  diffPlaceholders,
  hardErrorsOf,
  nextVersionPreview,
  splitSections,
} from '@/lib/promptTemplate';
import type {
  PromptCreateResult,
  PromptPlaceholderItem,
  PromptRemovalConfirmation,
  PromptVersionStrategy,
} from '@/types/promptTemplate';

/** 无激活版异常态的新建底稿：预填分段标记骨架（校验面板引导补 json 前提等）。 */
const EMPTY_SKELETON = '---SYSTEM---\n\n---USER---\n';

/** 校验面板 / 草稿写入防抖（UI 方案 §3.3 交互 5/8）。 */
const DEBOUNCE_MILLIS = 300;

interface PromptDraft {
  baseVersionId: number;
  template: string;
  strategy: PromptVersionStrategy;
  savedAt: string;
}

const draftKeyOf = (briefType: number): string => `prompt-draft-${briefType}`;

function readDraft(briefType: number): PromptDraft | null {
  try {
    const raw = sessionStorage.getItem(draftKeyOf(briefType));
    const parsed = raw ? (JSON.parse(raw) as PromptDraft) : null;
    return parsed && typeof parsed.template === 'string' ? parsed : null;
  } catch {
    return null;
  }
}

function writeDraft(briefType: number, draft: PromptDraft): void {
  try {
    sessionStorage.setItem(draftKeyOf(briefType), JSON.stringify(draft));
  } catch {
    // 隐私模式等场景草稿兜底降级为内存态，不阻断编辑
  }
}

function removeDraft(briefType: number): void {
  try {
    sessionStorage.removeItem(draftKeyOf(briefType));
  } catch {
    // 同上
  }
}

function formatDraftTime(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  const p = (n: number) => String(n).padStart(2, '0');
  return `${p(date.getMonth() + 1)}-${p(date.getDate())} ${p(date.getHours())}:${p(date.getMinutes())}`;
}

function messageOf(err: unknown, fallback: string): string {
  return err instanceof ApiError ? err.msg : fallback;
}

interface RegistryState {
  status: 'loading' | 'error' | 'ready';
  scenarioName: string;
  dormant: boolean;
  note: string | null;
  items: PromptPlaceholderItem[];
}

interface PromptTemplateEditorProps {
  briefType: number;
  sceneName: string;
  /** 编辑底稿版本（「编辑」入口）或当前激活版（「新建版本」入口）；null = 场景无激活版。 */
  baseVersionId: number | null;
  baseVersionLabel: string;
  /** 该场景全部既有版本号（预览号推算基准；保存成功后由页面重拉更新）。 */
  sceneVersions: string[];
  /** 关闭编辑视图回列表（dirty 守卫已在本组件内完成）。 */
  onBack: () => void;
  /** 保存成功（201）：页面重拉列表呈现新版本。 */
  onSaved: (result: PromptCreateResult) => void;
  /** 版本冲突（30070）：页面重拉列表更新预览号。 */
  onConflict: () => void;
}

/** 保存确认 Dialog（D7）：移除项逐项勾选放行 + unknown amber 随单展示，一次决策。 */
function SaveConfirmDialog({
  sceneName,
  confirmation,
  checkedKeys,
  saving,
  onToggle,
  onCancel,
  onConfirm,
}: {
  sceneName: string;
  confirmation: PromptRemovalConfirmation;
  checkedKeys: Set<string>;
  saving: boolean;
  onToggle: (key: string) => void;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const allChecked = confirmation.removed.every((item) => checkedKeys.has(item.key));
  return (
    <Dialog
      open
      title={`保存确认 · ${sceneName}`}
      description="以下占位符将从模板移除，移除后生成简报不再注入对应数据；确认为有意精简后可保存。"
      onClose={onCancel}
      footer={
        <>
          <Button variant="outline" size="sm" onClick={onCancel}>
            返回修改
          </Button>
          <Button
            size="sm"
            disabled={!allChecked || saving}
            onClick={onConfirm}
            data-testid="prompt-save-confirm"
          >
            {saving ? '执行中…' : '仍要保存（须全勾）'}
          </Button>
        </>
      }
    >
      {confirmation.removed.length > 0 ? (
        <div className="flex flex-col gap-2">
          <span className="text-sm">将移除的占位符（须逐项确认）：</span>
          {confirmation.removed.map((item) => (
            <label key={item.key} className="flex items-start gap-2 text-sm">
              <input
                type="checkbox"
                checked={checkedKeys.has(item.key)}
                onChange={() => onToggle(item.key)}
                data-testid={`prompt-confirm-removed-${item.key}`}
                className="mt-0.5"
              />
              <span>
                确认移除 <code className="font-mono text-xs">{`{{${item.key}}}`}</code> ——{' '}
                {item.description ?? '（注册表未登记用途）'}
              </span>
            </label>
          ))}
        </div>
      ) : null}
      {confirmation.unknown.length > 0 ? (
        <div className="flex flex-col gap-2">
          <span className="text-sm">警告（无需确认，仅提醒）：</span>
          {confirmation.unknown.map((item) => (
            <span key={item.key} className="text-sm text-amber-400">
              <code className="font-mono text-xs">{`{{${item.key}}}`}</code>{' '}
              无上下文来源，渲染时将原样发给模型
            </span>
          ))}
        </div>
      ) : null}
    </Dialog>
  );
}

/** 校验面板（实时，输入防抖）：硬错误 rose 置顶 + 移除/未知 amber + 分段预览（D5 并入）。 */
function ValidationPanel({
  text,
  hardErrors,
  removed,
  unknown,
  registeredKeys,
}: {
  text: string;
  hardErrors: string[];
  removed: PromptPlaceholderItem[];
  unknown: PromptPlaceholderItem[];
  registeredKeys: Set<string> | null;
}) {
  const sectionsOk = splitSections(text) !== null;
  return (
    <section
      className="flex flex-col gap-2 rounded-xl bg-card p-4 text-card-foreground ring-1 ring-foreground/10"
      role="status"
      aria-label="校验面板"
      data-testid="prompt-validation-panel"
    >
      {hardErrors.length > 0 ? (
        hardErrors.map((error) => (
          <p key={error} className="text-xs text-rose-400" role="alert">
            {error}
          </p>
        ))
      ) : (
        <p className="text-xs text-emerald-400">✓ 分段标记与 json 前提均通过</p>
      )}
      {sectionsOk ? null : (
        <p className="text-xs text-muted-foreground">（补齐分段标记后继续校验 json 字样）</p>
      )}
      <p className="text-xs text-amber-400">
        {removed.length > 0
          ? `移除占位符 ${removed.length} 项（保存时需确认）：${removed.map((i) => `{{${i.key}}}`).join(' ')}`
          : null}
      </p>
      <p className="text-xs text-amber-400">
        {unknown.length > 0
          ? `新增未知占位符 ${unknown.length} 项（可保存，警告）：${unknown.map((i) => `{{${i.key}}}`).join(' ')}`
          : null}
      </p>
      <div className="flex flex-col gap-2">
        <span className="text-xs text-muted-foreground">分段预览</span>
        <TemplateTextView template={text} registeredKeys={registeredKeys} />
      </div>
    </section>
  );
}

/** 模板编辑器（T48）：编辑即新版本、保存即激活；成功后以新版本为新底稿重置（dirty 清零）。 */
export function PromptTemplateEditor({
  briefType,
  sceneName,
  baseVersionId,
  baseVersionLabel,
  sceneVersions,
  onBack,
  onSaved,
  onConflict,
}: PromptTemplateEditorProps) {
  const [base, setBase] = useState<{ id: number | null; template: string } | null>(null);
  const [baseLoading, setBaseLoading] = useState(baseVersionId !== null);
  const [baseError, setBaseError] = useState<string | null>(null);
  const [text, setText] = useState('');
  const [debouncedText, setDebouncedText] = useState('');
  const [initialized, setInitialized] = useState(false);
  const [strategy, setStrategy] = useState<PromptVersionStrategy>('MINOR');
  const [saving, setSaving] = useState(false);
  const [feedback, setFeedback] = useState<{ state: SaveFeedbackState; msg: string; version: string | null }>({
    state: 'idle',
    msg: '',
    version: null,
  });
  const [confirmation, setConfirmation] = useState<PromptRemovalConfirmation | null>(null);
  const [checkedKeys, setCheckedKeys] = useState<Set<string>>(new Set());
  const [leaveConfirm, setLeaveConfirm] = useState(false);
  const [draftNotice, setDraftNotice] = useState<PromptDraft | null>(null);
  const [registry, setRegistry] = useState<RegistryState>({
    status: 'loading',
    scenarioName: sceneName,
    dormant: false,
    note: null,
    items: [],
  });
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  // 底稿与注册表加载（编辑底稿与 diff 基准一律走详情单查，UI 方案 §6.2 联判 1）
  const initBase = useCallback(
    async (signal: AbortSignal) => {
      if (baseVersionId === null) {
        setBase({ id: null, template: '' });
        setText(EMPTY_SKELETON);
        setDebouncedText(EMPTY_SKELETON);
        setInitialized(true);
        return;
      }
      setBaseLoading(true);
      setBaseError(null);
      try {
        const detail = await getPromptTemplateDetail(baseVersionId, signal);
        if (signal.aborted) return;
        setBase({ id: detail.id, template: detail.template });
        setText(detail.template);
        setDebouncedText(detail.template);
        // 草稿兜底（D11）：仅底稿同版本时提示恢复，跨底稿旧草稿视为过期
        const draft = readDraft(briefType);
        setDraftNotice(draft && draft.baseVersionId === detail.id ? draft : null);
        setInitialized(true);
      } catch (err) {
        if (signal.aborted) return;
        setBaseError(messageOf(err, '底稿加载失败'));
      } finally {
        if (!signal.aborted) setBaseLoading(false);
      }
    },
    [baseVersionId, briefType],
  );

  const loadRegistry = useCallback(
    async (signal: AbortSignal) => {
      setRegistry((prev) => ({ ...prev, status: 'loading' }));
      try {
        const scenario = await getPromptPlaceholders(briefType, signal);
        if (signal.aborted) return;
        setRegistry({
          status: 'ready',
          scenarioName: scenario.name || sceneName,
          dormant: scenario.dormant,
          note: scenario.note ?? null,
          items: scenario.placeholders,
        });
      } catch {
        if (signal.aborted) return;
        // 对照是辅助能力：失败不阻断编辑，保存校验降级为仅硬校验 + 后端兜底
        setRegistry((prev) => ({ ...prev, status: 'error' }));
      }
    },
    [briefType, sceneName],
  );

  useEffect(() => {
    const ctrl = new AbortController();
    void initBase(ctrl.signal);
    void loadRegistry(ctrl.signal);
    return () => ctrl.abort();
  }, [initBase, loadRegistry]);

  // 校验面板防抖（§3.3 交互 5）
  useEffect(() => {
    const timer = window.setTimeout(() => setDebouncedText(text), DEBOUNCE_MILLIS);
    return () => window.clearTimeout(timer);
  }, [text]);

  const dirty = initialized && base !== null && text !== base.template;

  // 草稿防抖写入（D11：key 含 briefType，存底稿版本号 + 正文 + 策略）
  useEffect(() => {
    if (!initialized || !dirty || base === null) return;
    const timer = window.setTimeout(() => {
      writeDraft(briefType, {
        baseVersionId: base.id ?? 0,
        template: text,
        strategy,
        savedAt: new Date().toISOString(),
      });
    }, DEBOUNCE_MILLIS);
    return () => window.clearTimeout(timer);
  }, [briefType, base, text, strategy, dirty, initialized]);

  const registryItems = registry.status === 'ready' ? registry.items : null;
  const hardErrors = useMemo(() => hardErrorsOf(debouncedText), [debouncedText]);
  const diff = useMemo(
    () => diffPlaceholders(debouncedText, base?.template ?? null, registryItems),
    [debouncedText, base, registryItems],
  );
  const registeredKeys = useMemo(
    () => (registryItems ? new Set(registryItems.map((item) => item.key)) : null),
    [registryItems],
  );
  const minorPreview = nextVersionPreview(sceneVersions, 'MINOR');
  const majorPreview = nextVersionPreview(sceneVersions, 'MAJOR');
  const previewVersion = strategy === 'MINOR' ? minorPreview : majorPreview;

  const insertPlaceholder = (key: string) => {
    const el = textareaRef.current;
    const token = `{{${key}}}`;
    if (!el) {
      setText((prev) => prev + token);
      return;
    }
    const start = el.selectionStart ?? text.length;
    const end = el.selectionEnd ?? start;
    setText(text.slice(0, start) + token + text.slice(end));
    window.requestAnimationFrame(() => {
      el.focus();
      el.setSelectionRange(start + token.length, start + token.length);
    });
  };

  /** 提交保存（confirmedRemovedKeys：确认放行的移除键；服务端每次重算 diff）。 */
  const submit = async (confirmedRemovedKeys: string[]) => {
    setSaving(true);
    setFeedback({ state: 'saving', msg: '', version: null });
    try {
      const result = await createPromptTemplate({
        briefType,
        baseVersionId: base?.id ?? undefined,
        template: text,
        versionStrategy: strategy,
        confirmedRemovedKeys,
      });
      removeDraft(briefType);
      setBase({ id: result.id, template: text }); // 新版本为新底稿，dirty 清零
      setDraftNotice(null);
      setDebouncedText(text);
      setConfirmation(null);
      setCheckedKeys(new Set());
      setFeedback({ state: 'success', msg: '', version: result.version });
      onSaved(result);
    } catch (err) {
      if (err instanceof ApiError && err.code === 30068 && err.data) {
        // 服务端重算清单与前端不一致（并发/口径差）：重新弹 Dialog 并预勾选本次已确认项
        const data = err.data as PromptRemovalConfirmation;
        setConfirmation({ removed: data.removed ?? [], unknown: data.unknown ?? [] });
        setCheckedKeys(new Set(confirmedRemovedKeys));
        setFeedback({ state: 'idle', msg: '', version: null });
      } else if (err instanceof ApiError && err.code === 30070) {
        setFeedback({ state: 'error', msg: '版本号冲突（可能列表已过期），已刷新，请重试', version: null });
        onConflict();
      } else {
        setFeedback({ state: 'error', msg: messageOf(err, '保存失败，请重试'), version: null });
      }
    } finally {
      setSaving(false);
    }
  };

  const handleSave = () => {
    // 前端先跑硬校验（面板同款逻辑，双保险）；有移除项才进确认 Dialog——
    // 仅未知占位符不弹（§3.4 线框注：面板 amber 警告已足够，不多一层弹窗；后端 30068 兜底）
    const freshHard = hardErrorsOf(text);
    if (freshHard.length > 0) {
      setDebouncedText(text); // 立即刷新面板（跳过防抖）
      return;
    }
    const freshDiff = diffPlaceholders(text, base?.template ?? null, registryItems);
    if (freshDiff.removed.length > 0) {
      setConfirmation(freshDiff);
      setCheckedKeys(new Set()); // 勾选不跨打开持久（§3.4 交互 3）
      return;
    }
    void submit([]);
  };

  const handleBack = () => {
    if (dirty) {
      setLeaveConfirm(true);
      return;
    }
    onBack();
  };

  const canSave = initialized && dirty && !saving && hardErrors.length === 0;

  if (baseError) {
    return (
      <main className="mx-auto w-full max-w-6xl p-4 sm:p-6" data-testid="prompt-editor">
        <header className="mb-4 flex items-center gap-3">
          <h1 className="text-xl font-medium">编辑 · {sceneName}</h1>
          <Button
            variant="outline"
            size="sm"
            className="ml-auto"
            onClick={onBack}
            data-testid="prompt-editor-back"
          >
            返回列表
          </Button>
        </header>
        <div className="flex flex-col items-start gap-2" data-testid="prompt-editor-error">
          <p className="text-sm text-destructive" role="alert">
            {baseError}
          </p>
          <div className="flex gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => {
                const ctrl = new AbortController();
                void initBase(ctrl.signal);
              }}
              data-testid="prompt-editor-retry"
            >
              重试
            </Button>
          </div>
        </div>
      </main>
    );
  }

  return (
    <main className="mx-auto w-full max-w-6xl p-4 sm:p-6" data-testid="prompt-editor">
      <header className="mb-2 flex flex-wrap items-center gap-3">
        <h1 className="text-xl font-medium" data-testid="prompt-editor-title">
          编辑 · {sceneName}
          {baseVersionLabel ? `（基于 ${baseVersionLabel}）` : ''}
        </h1>
        <Button
          variant="outline"
          size="sm"
          className="ml-auto"
          onClick={handleBack}
          data-testid="prompt-editor-back"
        >
          返回列表
        </Button>
      </header>
      <p className="mb-4 text-sm text-muted-foreground">
        保存将落为新版本并激活
        {baseVersionLabel ? `；${baseVersionLabel} 自动置废留痕（可随时切回）` : ''}
      </p>

      {draftNotice ? (
        <div
          className="mb-4 flex flex-wrap items-center gap-2 rounded bg-amber-500/15 px-3 py-2 text-sm text-amber-400"
          data-testid="prompt-draft-notice"
        >
          <span>
            检测到未保存草稿（基于 {baseVersionLabel}，{formatDraftTime(draftNotice.savedAt)}）
          </span>
          <Button
            variant="outline"
            size="sm"
            className="ml-auto"
            onClick={() => {
              setText(draftNotice.template);
              setStrategy(draftNotice.strategy);
              setDraftNotice(null);
            }}
            data-testid="prompt-draft-restore"
          >
            恢复草稿
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={() => {
              removeDraft(briefType);
              setDraftNotice(null);
            }}
            data-testid="prompt-draft-discard"
          >
            丢弃
          </Button>
        </div>
      ) : null}

      <div className="mb-4 flex flex-wrap items-center gap-2 text-sm" data-testid="prompt-strategy">
        <span className="text-muted-foreground">版本策略</span>
        <div className="inline-flex overflow-hidden rounded-md border">
          <Button
            type="button"
            size="sm"
            variant={strategy === 'MINOR' ? 'default' : 'ghost'}
            className="rounded-none"
            onClick={() => setStrategy('MINOR')}
            data-testid="prompt-strategy-minor"
          >
            次版本 {minorPreview}
          </Button>
          <Button
            type="button"
            size="sm"
            variant={strategy === 'MAJOR' ? 'default' : 'ghost'}
            className="rounded-none"
            onClick={() => setStrategy('MAJOR')}
            data-testid="prompt-strategy-major"
          >
            主版本 {majorPreview}
          </Button>
        </div>
        <span className="text-muted-foreground">→ 将保存为 {previewVersion} 并激活</span>
      </div>
      <p className="mb-4 text-xs text-muted-foreground">保存后自动激活，原激活版本置废留痕</p>

      {baseLoading ? (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-[1fr_280px]">
          <Skeleton className="h-96 w-full" />
          <Skeleton className="h-64 w-full" />
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-[1fr_280px]">
          <div className="flex flex-col gap-4">
            <textarea
              ref={textareaRef}
              value={text}
              onChange={(e) => setText(e.target.value)}
              spellCheck={false}
              aria-label="模板正文"
              className="min-h-96 w-full resize-y rounded-md border border-input bg-transparent px-3 py-2 font-mono text-sm whitespace-pre outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
              data-testid="prompt-editor-textarea"
            />
            <ValidationPanel
              text={debouncedText}
              hardErrors={hardErrors}
              removed={diff.removed}
              unknown={diff.unknown}
              registeredKeys={registeredKeys}
            />
            <div className="flex flex-col gap-2">
              <div>
                <Button
                  size="sm"
                  disabled={!canSave}
                  title={hardErrors.length > 0 ? hardErrors[0] : undefined}
                  onClick={handleSave}
                  data-testid="prompt-editor-save"
                >
                  {saving ? '保存中…' : `保存并激活（${previewVersion}）`}
                </Button>
              </div>
              <SaveFeedbackBar
                state={feedback.state}
                hotFields={
                  feedback.state === 'success' && feedback.version
                    ? [
                        `${sceneName} ${feedback.version} 已保存并激活；下一次生成即用新版本（旧缓存自动失效）`,
                      ]
                    : []
                }
                message={feedback.msg}
                testId="prompt-editor-feedback"
              />
            </div>
          </div>
          <PlaceholderPanel
            scenarioName={registry.scenarioName}
            dormant={registry.dormant}
            note={registry.note}
            items={registry.items}
            status={registry.status}
            text={text}
            onRetry={() => {
              const ctrl = new AbortController();
              void loadRegistry(ctrl.signal);
            }}
            onInsert={insertPlaceholder}
          />
        </div>
      )}

      {confirmation ? (
        <SaveConfirmDialog
          sceneName={sceneName}
          confirmation={confirmation}
          checkedKeys={checkedKeys}
          saving={saving}
          onToggle={(key) =>
            setCheckedKeys((prev) => {
              const next = new Set(prev);
              if (next.has(key)) {
                next.delete(key);
              } else {
                next.add(key);
              }
              return next;
            })
          }
          onCancel={() => setConfirmation(null)}
          onConfirm={() => void submit([...checkedKeys])}
        />
      ) : null}

      <Dialog
        open={leaveConfirm}
        title="未保存的修改"
        description="未保存的修改将丢弃（草稿已自动留存，重进编辑器可恢复），确认返回？"
        onClose={() => setLeaveConfirm(false)}
        footer={
          <>
            <Button variant="outline" size="sm" onClick={() => setLeaveConfirm(false)}>
              取消
            </Button>
            <Button
              size="sm"
              onClick={() => {
                setLeaveConfirm(false);
                onBack();
              }}
              data-testid="prompt-editor-leave-confirm"
            >
              确认返回
            </Button>
          </>
        }
      />
    </main>
  );
}

export default PromptTemplateEditor;
