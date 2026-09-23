// 提示词模板治理页（M5 T47+T48，#/prompt-templates，UI 方案 §3.1/§3.5）。
// 单列纵向：4 场景分区 × 版本卡网格 + 版本操作（编辑/新建 → 页内替换式编辑视图 D4；
// 激活切换与删除 → 二次确认 Dialog + 操作后重拉列表不做乐观更新）+ 场景级 SaveFeedbackBar。

import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '@/api/http';
import {
  activatePromptTemplate,
  deletePromptTemplate,
  getPromptPlaceholders,
  getPromptTemplateDetail,
  getPromptTemplates,
} from '@/api/promptTemplates';
import { VersionCard } from '@/components/prompt/VersionCard';
import { SaveFeedbackBar, type SaveFeedbackState } from '@/components/config/SaveFeedbackBar';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog } from '@/components/ui/dialog';
import { Skeleton } from '@/components/ui/skeleton';
import { compareVersions } from '@/lib/promptTemplate';
import { PromptTemplateEditor } from '@/pages/PromptTemplateEditor';
import type {
  PromptCreateResult,
  PromptDetailView,
  PromptGroupView,
  PromptListView,
  PromptScenarioView,
  PromptVersionView,
} from '@/types/promptTemplate';

function messageOf(err: unknown, fallback: string): string {
  return err instanceof ApiError ? err.msg : fallback;
}

/** 场景内排序：激活卡置顶，其余按版本号数值倒序（UI 方案 §3.1 线框脚注）。 */
function orderVersions(versions: PromptVersionView[]): PromptVersionView[] {
  return [...versions].sort((a, b) => {
    if (a.status === 'ACTIVE' && b.status !== 'ACTIVE') return -1;
    if (b.status === 'ACTIVE' && a.status !== 'ACTIVE') return 1;
    return compareVersions(b.version, a.version);
  });
}

/** 场景级操作反馈（激活/删除共用一条反馈条，UI 方案 §4.2）。 */
interface SceneFeedback {
  state: SaveFeedbackState;
  label: string;
  hot: string[];
  msg: string;
}

const IDLE_FEEDBACK: SceneFeedback = { state: 'idle', label: '', hot: [], msg: '' };

interface SceneSectionProps {
  group: PromptGroupView;
  /** 该场景注册表（首张卡展开详情时懒加载；null = 未拉取，分区头不显示注册数）。 */
  registry: PromptScenarioView | null;
  expanded: Record<number, boolean>;
  details: Record<number, PromptDetailView>;
  detailLoading: Record<number, boolean>;
  detailErrors: Record<number, string>;
  busy: boolean;
  feedback: SceneFeedback;
  onToggleDetail: (group: PromptGroupView, version: PromptVersionView) => void;
  onNewVersion: (group: PromptGroupView) => void;
  onEdit: (group: PromptGroupView, version: PromptVersionView) => void;
  onActivate: (group: PromptGroupView, version: PromptVersionView) => void;
  onDelete: (group: PromptGroupView, version: PromptVersionView) => void;
}

/** 场景分区：分区头（场景名 + 当前使用徽章 + 注册数 + 新建版本）+ 警示 + 版本卡网格 + 反馈条。 */
function SceneSection({
  group,
  registry,
  expanded,
  details,
  detailLoading,
  detailErrors,
  busy,
  feedback,
  onToggleDetail,
  onNewVersion,
  onEdit,
  onActivate,
  onDelete,
}: SceneSectionProps) {
  const briefType = group.briefType;
  const activeVersion = group.versions.find((v) => v.id === group.activeVersionId) ?? null;
  const registeredKeys = registry
    ? new Set(registry.placeholders.map((item) => item.key))
    : null;

  return (
    <Card data-testid={`prompt-scene-${briefType}`}>
      <CardHeader>
        <CardTitle className="flex flex-wrap items-center gap-2 text-base">
          <span>{group.name}</span>
          {activeVersion && group.activeCount === 1 ? (
            <Badge className="bg-emerald-500/15 text-emerald-400">
              当前使用 {activeVersion.version}
            </Badge>
          ) : null}
          {registry ? (
            <span className="text-xs text-muted-foreground">
              占位符注册 {registry.placeholders.length} 项
            </span>
          ) : null}
          <Button
            size="sm"
            className="ml-auto"
            disabled={busy}
            title="以当前激活版本为底稿创建新版本，保存后自动激活"
            onClick={() => onNewVersion(group)}
            data-testid={`prompt-new-${briefType}`}
          >
            新建版本
          </Button>
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {group.activeCount !== 1 ? (
          <p
            className="rounded bg-rose-500/15 px-2 py-1 text-xs text-rose-400"
            role="alert"
            data-testid={`prompt-scene-warning-${briefType}`}
          >
            {group.activeCount === 0
              ? '该场景无启用模板，AI 生成将失败'
              : '检测到多个启用版本，生成将取其一，请重新切换激活修复'}
          </p>
        ) : null}
        {group.versions.length === 0 ? (
          <p className="py-6 text-center text-sm text-muted-foreground">
            该场景暂无模板版本（播种数据应保证至少 1 版，此态属数据异常）
          </p>
        ) : (
          <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
            {orderVersions(group.versions).map((version) => (
              <VersionCard
                key={version.id}
                briefType={briefType}
                version={version}
                expanded={expanded[version.id] ?? false}
                detail={details[version.id] ?? null}
                detailLoading={detailLoading[version.id] ?? false}
                detailError={detailErrors[version.id] ?? null}
                registeredKeys={registeredKeys}
                busy={busy}
                onToggleDetail={(v) => onToggleDetail(group, v)}
                onEdit={(v) => onEdit(group, v)}
                onActivate={(v) => onActivate(group, v)}
                onDelete={(v) => onDelete(group, v)}
              />
            ))}
          </div>
        )}
        <SaveFeedbackBar
          state={feedback.state}
          successLabel={feedback.label}
          hotFields={feedback.hot}
          message={feedback.msg}
          testId={`prompt-scene-feedback-${briefType}`}
        />
      </CardContent>
    </Card>
  );
}

/**
 * 提示词模板管理页：4 场景 × 全部版本一页可见；编辑视图为同路由 state 切换（D4）。
 * 列表为轻列表；详情全文与场景注册表按需懒加载（页面级缓存）；
 * 激活/删除/保存成功后一律重拉列表（不做乐观更新，不变量以后端为准，UI 方案 §3.5 交互 4）。
 */
export function PromptTemplates() {
  const [view, setView] = useState<PromptListView | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<Record<number, boolean>>({});
  const [details, setDetails] = useState<Record<number, PromptDetailView>>({});
  const [detailLoading, setDetailLoading] = useState<Record<number, boolean>>({});
  const [detailErrors, setDetailErrors] = useState<Record<number, string>>({});
  const [registries, setRegistries] = useState<Record<number, PromptScenarioView>>({});
  const abortRef = useRef<AbortController | null>(null);

  // 编辑视图（D4：单路由两视图，hash 不变）
  const [editorTarget, setEditorTarget] = useState<{
    briefType: number;
    sceneName: string;
    baseVersionId: number | null;
    baseVersionLabel: string;
  } | null>(null);

  // 版本操作（二次确认 Dialog 承载，场景级互斥）
  const [activateTarget, setActivateTarget] = useState<{
    group: PromptGroupView;
    version: PromptVersionView;
  } | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<{
    group: PromptGroupView;
    version: PromptVersionView;
  } | null>(null);
  const [sceneBusy, setSceneBusy] = useState<Record<number, boolean>>({});
  const [sceneFeedback, setSceneFeedback] = useState<Record<number, SceneFeedback>>({});

  const load = useCallback(async () => {
    abortRef.current?.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    setLoading(true);
    setError(null);
    try {
      const data = await getPromptTemplates(ctrl.signal);
      if (ctrl.signal.aborted) return;
      setView(data);
    } catch (err) {
      if (ctrl.signal.aborted) return;
      setError(messageOf(err, '模板列表加载失败'));
    } finally {
      if (!ctrl.signal.aborted) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
    return () => abortRef.current?.abort();
  }, [load]);

  /** 展开详情：懒加载版本全文（编辑底稿与展示同源）。 */
  const ensureDetail = useCallback(async (id: number, signal: AbortSignal) => {
    setDetailLoading((prev) => ({ ...prev, [id]: true }));
    setDetailErrors((prev) => ({ ...prev, [id]: '' }));
    try {
      const detail = await getPromptTemplateDetail(id, signal);
      if (signal.aborted) return;
      setDetails((prev) => ({ ...prev, [id]: detail }));
    } catch (err) {
      if (signal.aborted) return;
      setDetailErrors((prev) => ({ ...prev, [id]: messageOf(err, '版本详情加载失败') }));
    } finally {
      if (!signal.aborted) {
        setDetailLoading((prev) => ({ ...prev, [id]: false }));
      }
    }
  }, []);

  /** 场景注册表懒加载（详情占位符着色用；失败静默降级为全部按已注册配色，可重试）。 */
  const ensureRegistry = useCallback(async (briefType: number, signal: AbortSignal) => {
    try {
      const scenario = await getPromptPlaceholders(briefType, signal);
      if (signal.aborted) return;
      setRegistries((prev) => ({ ...prev, [briefType]: scenario }));
    } catch {
      // 着色降级非关键路径：注册数/amber 标注缺失不阻断浏览
    }
  }, []);

  const toggleDetail = useCallback(
    (group: PromptGroupView, version: PromptVersionView) => {
      const next = !(expanded[version.id] ?? false);
      setExpanded((prev) => ({ ...prev, [version.id]: next }));
      if (next) {
        const ctrl = new AbortController();
        if (!details[version.id]) void ensureDetail(version.id, ctrl.signal);
        if (!registries[group.briefType]) void ensureRegistry(group.briefType, ctrl.signal);
      }
    },
    [details, ensureDetail, ensureRegistry, expanded, registries],
  );

  /** 打开编辑器：编辑入口以所点版本为底稿；新建入口以当前激活版为底稿（§2.2）。 */
  const openEditor = (group: PromptGroupView, base: PromptVersionView | null) => {
    setEditorTarget({
      briefType: group.briefType,
      sceneName: group.name,
      baseVersionId: base ? base.id : null,
      baseVersionLabel: base ? base.version : '',
    });
  };

  const handleNewVersion = (group: PromptGroupView) => {
    const active = group.versions.find((v) => v.id === group.activeVersionId) ?? null;
    openEditor(group, active);
  };

  /** 确认激活切换：POST activate → 反馈条 + 重拉列表（唯一不变量以后端为准）。 */
  const confirmActivate = async () => {
    if (!activateTarget) return;
    const { group, version } = activateTarget;
    setSceneBusy((prev) => ({ ...prev, [group.briefType]: true }));
    try {
      await activatePromptTemplate(version.id);
      setActivateTarget(null);
      setSceneFeedback((prev) => ({
        ...prev,
        [group.briefType]: {
          state: 'success',
          label: `已激活 ${version.version}`,
          hot: [`下一次${group.name}生成即用该版本`],
          msg: '',
        },
      }));
      await load();
    } catch (err) {
      setSceneFeedback((prev) => ({
        ...prev,
        [group.briefType]: {
          state: 'error',
          label: '',
          hot: [],
          msg: messageOf(err, '激活切换失败，请重试'),
        },
      }));
    } finally {
      setSceneBusy((prev) => ({ ...prev, [group.briefType]: false }));
    }
  };

  /** 确认删除置废版本：DELETE → 反馈条 + 重拉列表（激活版由后端 30069 守卫兜底）。 */
  const confirmDelete = async () => {
    if (!deleteTarget) return;
    const { group, version } = deleteTarget;
    setSceneBusy((prev) => ({ ...prev, [group.briefType]: true }));
    try {
      await deletePromptTemplate(version.id);
      setDeleteTarget(null);
      setSceneFeedback((prev) => ({
        ...prev,
        [group.briefType]: {
          state: 'success',
          label: `已删除 ${version.version}`,
          hot: [],
          msg: '',
        },
      }));
      await load();
    } catch (err) {
      setSceneFeedback((prev) => ({
        ...prev,
        [group.briefType]: {
          state: 'error',
          label: '',
          hot: [],
          msg: messageOf(err, '删除失败，请重试'),
        },
      }));
    } finally {
      setSceneBusy((prev) => ({ ...prev, [group.briefType]: false }));
    }
  };

  /** 保存成功：重拉列表呈现新版本（编辑器保持打开，手动返回）。 */
  const handleEditorSaved = (_result: PromptCreateResult) => {
    void load();
  };

  // 编辑视图渲染（页内替换式：列表整棵卸载，hash 不变）
  if (editorTarget) {
    const sceneVersions =
      view?.groups
        .find((g) => g.briefType === editorTarget.briefType)
        ?.versions.map((v) => v.version) ?? [];
    return (
      <PromptTemplateEditor
        key={editorTarget.briefType}
        briefType={editorTarget.briefType}
        sceneName={editorTarget.sceneName}
        baseVersionId={editorTarget.baseVersionId}
        baseVersionLabel={editorTarget.baseVersionLabel}
        sceneVersions={sceneVersions}
        onBack={() => setEditorTarget(null)}
        onSaved={handleEditorSaved}
        onConflict={() => void load()}
      />
    );
  }

  // 4 场景全空 = 整页数据异常（UI 方案 §3.1 三态表 empty 档）→ 按错误态处理
  const anomalyEmpty =
    view !== null && (view.groups.length === 0 || view.groups.every((g) => g.versions.length === 0));

  return (
    <main className="mx-auto w-full max-w-6xl p-4 sm:p-6" data-testid="prompt-templates-page">
      <header className="mb-4">
        <h1 className="text-xl font-medium">提示词模板</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          4 个 AI 场景的提示词一页治理：版本全量可见、编辑即时生效、可回滚
        </p>
      </header>

      {loading ? (
        <div className="flex flex-col gap-4" data-testid="prompt-templates-loading">
          {Array.from({ length: 4 }, (_, i) => (
            <div key={i} className="flex flex-col gap-3">
              <Skeleton className="h-10 w-full" />
              <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
                <Skeleton className="h-24 w-full" />
                <Skeleton className="h-24 w-full" />
              </div>
            </div>
          ))}
        </div>
      ) : error ? (
        <div className="flex flex-col items-start gap-2" data-testid="prompt-templates-error">
          <p className="text-sm text-destructive" role="alert">
            {error}
          </p>
          <Button variant="outline" size="sm" onClick={() => void load()} data-testid="prompt-templates-retry">
            重试
          </Button>
        </div>
      ) : anomalyEmpty ? (
        <div className="flex flex-col items-start gap-2" data-testid="prompt-templates-error">
          <p className="text-sm text-destructive" role="alert">
            4 个场景均无模板版本（播种数据应保证至少 1 版），属数据异常
          </p>
          <Button variant="outline" size="sm" onClick={() => void load()} data-testid="prompt-templates-retry">
            重试
          </Button>
        </div>
      ) : view ? (
        <div className="flex flex-col gap-4">
          {view.groups.map((group) => (
            <SceneSection
              key={group.briefType}
              group={group}
              registry={registries[group.briefType] ?? null}
              expanded={expanded}
              details={details}
              detailLoading={detailLoading}
              detailErrors={detailErrors}
              busy={sceneBusy[group.briefType] ?? false}
              feedback={sceneFeedback[group.briefType] ?? IDLE_FEEDBACK}
              onToggleDetail={toggleDetail}
              onNewVersion={handleNewVersion}
              onEdit={(g, v) => openEditor(g, v)}
              onActivate={(g, v) => setActivateTarget({ group: g, version: v })}
              onDelete={(g, v) => setDeleteTarget({ group: g, version: v })}
            />
          ))}
        </div>
      ) : null}

      {activateTarget ? (
        <Dialog
          open
          title={`切换激活 · ${activateTarget.group.name}`}
          description={`将激活 ${activateTarget.version.version}（回滚），当前激活版本将自动置废留痕。即时生效：下一次${activateTarget.group.name}生成即用 ${activateTarget.version.version} 模板。`}
          onClose={() => setActivateTarget(null)}
          footer={
            <>
              <Button variant="outline" size="sm" onClick={() => setActivateTarget(null)}>
                取消
              </Button>
              <Button
                size="sm"
                disabled={sceneBusy[activateTarget.group.briefType]}
                onClick={() => void confirmActivate()}
                data-testid={`prompt-activate-confirm-${activateTarget.group.briefType}-${activateTarget.version.version}`}
              >
                {sceneBusy[activateTarget.group.briefType] ? '执行中…' : '确认切换并激活'}
              </Button>
            </>
          }
        />
      ) : null}

      {deleteTarget ? (
        <Dialog
          open
          title={`删除版本 · ${deleteTarget.group.name} ${deleteTarget.version.version}`}
          description="物理删除不可恢复；该版本已置废、不在生成链路使用，删除不影响其余版本与激活状态。"
          onClose={() => setDeleteTarget(null)}
          footer={
            <>
              <Button variant="outline" size="sm" onClick={() => setDeleteTarget(null)}>
                取消
              </Button>
              <Button
                variant="outline"
                size="sm"
                className="text-rose-400 hover:text-rose-400"
                disabled={sceneBusy[deleteTarget.group.briefType]}
                onClick={() => void confirmDelete()}
                data-testid={`prompt-delete-confirm-${deleteTarget.group.briefType}-${deleteTarget.version.version}`}
              >
                {sceneBusy[deleteTarget.group.briefType] ? '执行中…' : '确认删除'}
              </Button>
            </>
          }
        />
      ) : null}
    </main>
  );
}

export default PromptTemplates;
