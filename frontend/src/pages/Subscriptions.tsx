import { useCallback, useEffect, useRef, useState } from 'react';
import { Bookmark } from 'lucide-react';
import { ApiError } from '@/api/http';
import { createSubscription, listSubscriptionPage, unsubscribeSubscription } from '@/api/subscriptions';
import { fetchSubjectQuotes, type SubjectSummary } from '@/api/subject';
import { SubjectPicker } from '@/components/subject/SubjectPicker';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Dialog } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import {
  CHANNEL_LABELS,
  EVENT_TYPE_OPTIONS,
  SUB_TYPE,
  SUB_TYPE_META,
  type SubscriptionView,
} from '@/types/subscription';

// —— 常量与工具 ——

/** 首屏骨架条目数（三态 loading）。 */
const SKELETON_CARDS = 4;

/** 主题/政策主题关键词长度上限（前端校验；后端仅校非空，超长属脏输入防御）。 */
const KEYWORD_MAX_LENGTH = 50;

/** 非 ApiError 兜底文案。 */
function messageOf(err: unknown, fallback: string): string {
  return err instanceof ApiError ? err.msg : fallback;
}

/** 标的订阅 subKey（数字主键串）→ 摘要展示（未解析到时回退「标的 #id」）。 */
function subjectLabel(sub: SubscriptionView, subjects: Record<number, SubjectSummary>): string {
  const id = Number(sub.subKey);
  const summary = Number.isInteger(id) ? subjects[id] : undefined;
  if (!summary) return `标的 #${sub.subKey}`;
  return `${summary.subjectCode} ${summary.name}`;
}

/** 订阅内容展示文本（按类型：主题/政策主题为关键词、标的为代码+名称、事件类型为标签）。 */
function contentLabel(sub: SubscriptionView, subjects: Record<number, SubjectSummary>): string {
  if (sub.subType === SUB_TYPE.SUBJECT) return subjectLabel(sub, subjects);
  return sub.subKey;
}

/** 活跃订阅去重键（对齐后端幂等自然键 userId+subType+subKey 的前端投影）。 */
function duplicateKey(subType: number, subKey: string): string {
  return `${subType}:${subKey}`;
}

// —— 订阅条目卡 ——

interface SubscriptionRowProps {
  sub: SubscriptionView;
  subjects: Record<number, SubjectSummary>;
  busy: boolean;
  onRemove: (sub: SubscriptionView) => void;
  onReactivate: (sub: SubscriptionView) => void;
}

/** 订阅条目卡：类型徽章 + 订阅内容 + 渠道/状态 + 操作（退订需二次确认 / 已退订可重新订阅）。 */
function SubscriptionRow({ sub, subjects, busy, onRemove, onReactivate }: SubscriptionRowProps) {
  const meta = SUB_TYPE_META[sub.subType] ?? {
    label: `类型 ${sub.subType}`,
    className: 'bg-muted text-muted-foreground',
  };
  return (
    <Card size="sm" data-testid={`sub-item-${sub.id}`}>
      <CardContent className="flex flex-wrap items-center gap-x-3 gap-y-2">
        <Badge
          variant="ghost"
          className={`shrink-0 ${meta.className}`}
          data-testid={`sub-type-${sub.id}`}
        >
          {meta.label}
        </Badge>
        <span
          className="min-w-0 flex-1 truncate text-sm font-medium"
          title={contentLabel(sub, subjects)}
          data-testid={`sub-content-${sub.id}`}
        >
          {contentLabel(sub, subjects)}
        </span>
        <span className="text-xs text-muted-foreground">{CHANNEL_LABELS[sub.channel] ?? '应用内'}</span>
        {sub.status === 1 ? (
          <Badge
            variant="ghost"
            className="bg-emerald-500/15 text-emerald-400"
            data-testid={`sub-status-${sub.id}`}
          >
            订阅中
          </Badge>
        ) : (
          <Badge
            variant="ghost"
            className="bg-muted text-muted-foreground"
            data-testid={`sub-status-${sub.id}`}
          >
            已退订
          </Badge>
        )}
        {sub.status === 1 ? (
          <Button
            variant="outline"
            size="xs"
            disabled={busy}
            onClick={() => onRemove(sub)}
            data-testid={`sub-remove-${sub.id}`}
          >
            退订
          </Button>
        ) : (
          <Button
            variant="outline"
            size="xs"
            disabled={busy}
            onClick={() => onReactivate(sub)}
            data-testid={`sub-reactivate-${sub.id}`}
          >
            重新订阅
          </Button>
        )}
      </CardContent>
    </Card>
  );
}

// —— 新建订阅对话框 ——

interface CreateSubscriptionDialogProps {
  open: boolean;
  submitting: boolean;
  error: string | null;
  /** 已加载的活跃订阅去重键集合（本地快速判重；后端幂等为最终防线）。 */
  activeKeys: Set<string>;
  onClose: () => void;
  onSubmit: (subType: number, subKey: string) => Promise<void>;
}

/** 新建订阅类型选项（code + 徽章元数据复用列表展示）。 */
const CREATE_TYPE_OPTIONS: ReadonlyArray<{ code: number; hint: string }> = [
  { code: SUB_TYPE.TOPIC, hint: '公告 / 新闻 / 政策标题或摘要命中关键词' },
  { code: SUB_TYPE.SUBJECT, hint: '该标的的公告、新闻与同行业政策入流' },
  { code: SUB_TYPE.EVENT_TYPE, hint: '按内容类型命中（公告 / 新闻 / 政策）' },
  { code: SUB_TYPE.POLICY_THEME, hint: '政策标题或摘要命中主题词' },
];

/** 新建订阅对话框：类型四选一（chip）+ 按类型动态表单（关键词 / SubjectPicker / 事件类型 chip）。 */
function CreateSubscriptionDialog({
  open,
  submitting,
  error,
  activeKeys,
  onClose,
  onSubmit,
}: CreateSubscriptionDialogProps) {
  const [subType, setSubType] = useState<number>(SUB_TYPE.TOPIC);
  const [keyword, setKeyword] = useState('');
  const [subject, setSubject] = useState<SubjectSummary | null>(null);
  const [eventType, setEventType] = useState<string>('');
  const [validation, setValidation] = useState<string | null>(null);

  // 打开时重置表单（关闭态保留上次输入无意义）
  useEffect(() => {
    if (open) {
      setSubType(SUB_TYPE.TOPIC);
      setKeyword('');
      setSubject(null);
      setEventType('');
      setValidation(null);
    }
  }, [open]);

  const typeMeta = SUB_TYPE_META[subType] ?? { label: '主题', className: '' };

  const submit = async () => {
    let key: string;
    let keyLabel: string;
    if (subType === SUB_TYPE.SUBJECT) {
      if (!subject) {
        setValidation('请先搜索并选择标的');
        return;
      }
      key = String(subject.id);
      keyLabel = `${subject.subjectCode} ${subject.name}`;
    } else if (subType === SUB_TYPE.EVENT_TYPE) {
      if (!eventType) {
        setValidation('请选择事件类型');
        return;
      }
      key = eventType;
      keyLabel = eventType;
    } else {
      const trimmed = keyword.trim();
      if (!trimmed) {
        setValidation(subType === SUB_TYPE.POLICY_THEME ? '请输入政策主题词' : '请输入主题关键词');
        return;
      }
      if (trimmed.length > KEYWORD_MAX_LENGTH) {
        setValidation(`关键词不能超过 ${KEYWORD_MAX_LENGTH} 字`);
        return;
      }
      key = trimmed;
      keyLabel = trimmed;
    }
    if (activeKeys.has(duplicateKey(subType, key))) {
      setValidation(`已在订阅列表中（${typeMeta.label}：${keyLabel}），无需重复添加`);
      return;
    }
    setValidation(null);
    await onSubmit(subType, key);
  };

  return (
    <Dialog
      open={open}
      title="新建订阅"
      description="订阅后命中的公告、新闻与政策将进入你的信息流。"
      onClose={onClose}
    >
      <div className="flex flex-col gap-3">
        <div className="flex flex-col gap-1 text-sm">
          <span>订阅类型</span>
          <div className="flex flex-wrap gap-1.5" role="group" aria-label="订阅类型">
            {CREATE_TYPE_OPTIONS.map((option) => {
              const meta = SUB_TYPE_META[option.code];
              const selected = subType === option.code;
              return (
                <button
                  key={option.code}
                  type="button"
                  aria-pressed={selected}
                  disabled={submitting}
                  onClick={() => {
                    setSubType(option.code);
                    setValidation(null);
                  }}
                  className={`rounded-lg border px-2.5 py-1 text-sm transition-colors ${
                    selected
                      ? 'border-ring bg-accent font-medium text-accent-foreground'
                      : 'border-border text-muted-foreground hover:bg-muted'
                  }`}
                  data-testid={`subs-create-type-${option.code}`}
                >
                  {meta.label}
                </button>
              );
            })}
          </div>
          <span className="text-xs text-muted-foreground">
            {CREATE_TYPE_OPTIONS.find((option) => option.code === subType)?.hint}
          </span>
        </div>

        {subType === SUB_TYPE.SUBJECT ? (
          <div className="flex flex-col gap-1 text-sm">
            <span>标的</span>
            <SubjectPicker
              value={subject}
              onChange={(next) => {
                setSubject(next);
                setValidation(null);
              }}
              disabled={submitting}
              testId="subs-create-subject"
            />
          </div>
        ) : subType === SUB_TYPE.EVENT_TYPE ? (
          <div className="flex flex-col gap-1 text-sm">
            <span>事件类型</span>
            <div className="flex flex-wrap gap-1.5" role="group" aria-label="事件类型">
              {EVENT_TYPE_OPTIONS.map((option) => {
                const selected = eventType === option.value;
                return (
                  <button
                    key={option.value}
                    type="button"
                    aria-pressed={selected}
                    disabled={submitting}
                    onClick={() => {
                      setEventType(option.value);
                      setValidation(null);
                    }}
                    className={`rounded-lg border px-2.5 py-1 text-sm transition-colors ${
                      selected
                        ? 'border-ring bg-accent font-medium text-accent-foreground'
                        : 'border-border text-muted-foreground hover:bg-muted'
                    }`}
                    data-testid={`subs-create-event-${option.value}`}
                  >
                    {option.label}
                  </button>
                );
              })}
            </div>
          </div>
        ) : (
          <label className="flex flex-col gap-1 text-sm">
            <span>{subType === SUB_TYPE.POLICY_THEME ? '政策主题词' : '主题关键词'}</span>
            <Input
              type="text"
              maxLength={KEYWORD_MAX_LENGTH}
              placeholder={subType === SUB_TYPE.POLICY_THEME ? '如 货币政策' : '如 半导体国产替代'}
              disabled={submitting}
              value={keyword}
              onChange={(e) => {
                setKeyword(e.target.value);
                setValidation(null);
              }}
              data-testid="subs-create-keyword"
            />
          </label>
        )}

        {validation ? (
          <p className="text-sm text-destructive" role="alert" data-testid="subs-create-validation">
            {validation}
          </p>
        ) : null}
        {error ? (
          <p className="text-sm text-destructive" role="alert" data-testid="subs-create-error">
            {error}
          </p>
        ) : null}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" size="sm" onClick={onClose}>
            取消
          </Button>
          <Button
            type="button"
            size="sm"
            disabled={submitting}
            onClick={() => void submit()}
            data-testid="subs-create-submit"
          >
            {submitting ? '提交中…' : '订阅'}
          </Button>
        </div>
      </div>
    </Dialog>
  );
}

// —— 页面 ——

/**
 * 订阅管理页（体检 P1-3，#/subscriptions）。
 * - 列表：GET /subscriptions 游标分页（「加载更多」），条目卡展示类型徽章 / 订阅内容
 *   （标的订阅经 /subjects/quotes 批量解析为代码+名称，失败回退「标的 #id」不阻断）/ 渠道 / 状态。
 * - 新建：Dialog 类型四选一（主题关键词 / SubjectPicker 标的 / 事件类型 / 政策主题词），
 *   本地判重给友好文案，后端幂等（重复订阅直返不报错）为最终防线。
 * - 退订：二次确认 Dialog（全站危险操作规范）→ DELETE 软退订；已退订条目可一键重新订阅（POST 复用同键激活）。
 * - 三态：骨架 / 空态（CTA 新建订阅）/ 错误重试；401 由 http 层统一跳登录。
 */
export function Subscriptions() {
  const [items, setItems] = useState<SubscriptionView[]>([]);
  const [nextCursor, setNextCursor] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [subjects, setSubjects] = useState<Record<number, SubjectSummary>>({});
  const [createOpen, setCreateOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);
  const [removeTarget, setRemoveTarget] = useState<SubscriptionView | null>(null);
  const [removing, setRemoving] = useState(false);
  const [removeError, setRemoveError] = useState<string | null>(null);
  const [rowBusyId, setRowBusyId] = useState<number | null>(null);

  const abortRef = useRef<AbortController | null>(null);
  const subjectsAbortRef = useRef<AbortController | null>(null);

  // 标的订阅 subKey（数字主键）→ 摘要（代码+名称）：批量解析，失败静默回退占位不阻断列表
  const resolveSubjects = useCallback(async (subs: SubscriptionView[]) => {
    const missing = new Set<number>();
    for (const sub of subs) {
      if (sub.subType !== SUB_TYPE.SUBJECT) continue;
      const id = Number(sub.subKey);
      if (Number.isInteger(id) && id > 0) missing.add(id);
    }
    if (missing.size === 0) return;
    subjectsAbortRef.current?.abort();
    const ctrl = new AbortController();
    subjectsAbortRef.current = ctrl;
    try {
      const rows = await fetchSubjectQuotes([...missing], ctrl.signal);
      if (ctrl.signal.aborted) return;
      setSubjects((prev) => {
        const next = { ...prev };
        for (const row of rows) next[row.id] = row;
        return next;
      });
    } catch {
      // 解析失败保留「标的 #id」占位展示（列表面板不因此报错）
    }
  }, []);

  const loadFirst = useCallback(async () => {
    abortRef.current?.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    setLoading(true);
    setError(null);
    try {
      const page = await listSubscriptionPage({ signal: ctrl.signal });
      if (ctrl.signal.aborted) return;
      setItems(page.items);
      setNextCursor(page.nextCursor);
      void resolveSubjects(page.items);
    } catch (err) {
      if (ctrl.signal.aborted) return;
      setError(messageOf(err, '订阅列表加载失败'));
    } finally {
      if (!ctrl.signal.aborted) setLoading(false);
    }
  }, [resolveSubjects]);

  useEffect(() => {
    void loadFirst();
    return () => {
      abortRef.current?.abort();
      subjectsAbortRef.current?.abort();
    };
  }, [loadFirst]);

  // 翻页：追加不清已有条目；失败保留条目可重试
  const loadMore = useCallback(async () => {
    if (nextCursor == null || loadingMore || loading) return;
    setLoadingMore(true);
    try {
      const page = await listSubscriptionPage({ cursor: nextCursor });
      setItems((prev) => [...prev, ...page.items]);
      setNextCursor(page.nextCursor);
      void resolveSubjects(page.items);
    } catch (err) {
      setError(messageOf(err, '加载更多失败'));
    } finally {
      setLoadingMore(false);
    }
  }, [nextCursor, loadingMore, loading, resolveSubjects]);

  const submitCreate = useCallback(
    async (subType: number, subKey: string) => {
      setCreating(true);
      setCreateError(null);
      try {
        await createSubscription({ subType, subKey });
        setCreateOpen(false);
        await loadFirst();
      } catch (err) {
        setCreateError(messageOf(err, '订阅失败，请稍后重试'));
      } finally {
        setCreating(false);
      }
    },
    [loadFirst],
  );

  const confirmRemove = useCallback(async () => {
    const target = removeTarget;
    if (!target) return;
    setRemoving(true);
    setRemoveError(null);
    try {
      await unsubscribeSubscription(target.id);
      setRemoveTarget(null);
      await loadFirst();
    } catch (err) {
      setRemoveError(messageOf(err, '退订失败，请稍后重试'));
    } finally {
      setRemoving(false);
    }
  }, [removeTarget, loadFirst]);

  // 已退订条目重新订阅：POST 同键幂等激活（后端复用同一行，不新增）
  const reactivate = useCallback(
    async (sub: SubscriptionView) => {
      setRowBusyId(sub.id);
      try {
        await createSubscription({ subType: sub.subType, subKey: sub.subKey });
        await loadFirst();
      } catch (err) {
        setError(messageOf(err, '重新订阅失败'));
      } finally {
        setRowBusyId(null);
      }
    },
    [loadFirst],
  );

  const activeKeys = new Set(
    items.filter((sub) => sub.status === 1).map((sub) => duplicateKey(sub.subType, sub.subKey)),
  );

  return (
    <main className="mx-auto w-full max-w-4xl p-4 sm:p-6" data-testid="subscriptions-page">
      <header className="mb-4 flex flex-wrap items-start justify-between gap-2">
        <div>
          <h1 className="text-xl font-medium">订阅管理</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            订阅主题 / 标的 / 事件类型 / 政策主题，命中内容将进入你的信息流
          </p>
        </div>
        <Button size="sm" onClick={() => setCreateOpen(true)} data-testid="subs-create-open">
          新建订阅
        </Button>
      </header>

      {loading ? (
        <div className="flex flex-col gap-3" data-testid="subs-loading">
          {Array.from({ length: SKELETON_CARDS }, (_, index) => (
            <Skeleton key={index} className="h-16 w-full" />
          ))}
        </div>
      ) : error && items.length === 0 ? (
        <div className="flex flex-col items-start gap-2" data-testid="subs-error">
          <p className="text-sm text-destructive" role="alert">
            {error}
          </p>
          <Button variant="outline" size="sm" onClick={() => void loadFirst()} data-testid="subs-retry">
            重试
          </Button>
        </div>
      ) : items.length === 0 ? (
        <div
          className="flex flex-col items-center gap-3 py-10 text-center"
          data-testid="subs-empty"
        >
          <span className="flex size-10 items-center justify-center rounded-full bg-muted">
            <Bookmark className="size-5 text-muted-foreground" aria-hidden="true" />
          </span>
          <p className="text-sm text-muted-foreground">
            还没有订阅。创建订阅后，相关的公告、新闻与政策将进入你的信息流。
          </p>
          <Button size="sm" className="mt-1" onClick={() => setCreateOpen(true)} data-testid="subs-empty-cta">
            新建订阅
          </Button>
        </div>
      ) : (
        <>
          {error ? (
            <p className="mb-3 text-sm text-destructive" role="alert" data-testid="subs-more-error">
              {error}
            </p>
          ) : null}
          <div className="flex flex-col gap-3" data-testid="subs-list">
            {items.map((sub) => (
              <SubscriptionRow
                key={sub.id}
                sub={sub}
                subjects={subjects}
                busy={rowBusyId === sub.id}
                onRemove={(target) => {
                  setRemoveError(null);
                  setRemoveTarget(target);
                }}
                onReactivate={(target) => void reactivate(target)}
              />
            ))}
          </div>
          {nextCursor != null ? (
            <div className="mt-3 flex justify-center">
              <Button
                variant="outline"
                size="sm"
                disabled={loadingMore}
                onClick={() => void loadMore()}
                data-testid="subs-load-more"
              >
                {loadingMore ? '加载中…' : '加载更多'}
              </Button>
            </div>
          ) : null}
        </>
      )}

      <CreateSubscriptionDialog
        open={createOpen}
        submitting={creating}
        error={createError}
        activeKeys={activeKeys}
        onClose={() => {
          if (!creating) setCreateOpen(false);
        }}
        onSubmit={submitCreate}
      />

      <Dialog
        open={removeTarget != null}
        title="确认退订该订阅？"
        description={
          removeTarget
            ? `退订「${contentLabel(removeTarget, subjects)}」后，将不再推送相关信息流条目。`
            : undefined
        }
        onClose={() => {
          if (!removing) setRemoveTarget(null);
        }}
        footer={
          <>
            <Button
              variant="outline"
              size="sm"
              disabled={removing}
              onClick={() => setRemoveTarget(null)}
              data-testid="subs-remove-confirm-cancel"
            >
              取消
            </Button>
            <Button
              variant="destructive"
              size="sm"
              disabled={removing}
              onClick={() => void confirmRemove()}
              data-testid="subs-remove-confirm-ok"
            >
              {removing ? '退订中…' : '确认退订'}
            </Button>
          </>
        }
      >
        {removeError ? (
          <p className="text-sm text-destructive" role="alert" data-testid="subs-remove-error">
            {removeError}
          </p>
        ) : null}
      </Dialog>
    </main>
  );
}

export default Subscriptions;
