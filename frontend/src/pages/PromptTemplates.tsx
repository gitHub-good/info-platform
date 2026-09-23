// 提示词模板治理页（M5 T47，#/prompt-templates，UI 方案 §3.1）。
// 单列纵向：4 场景分区（Section Card）× 版本卡网格；版本详情内联展开（D3）；
// activeCount 0/≥2 异常警示不静默；三态：骨架 / 空态两档 / 整页错误重试。
// 编辑视图（页内替换式，D4）与版本操作由 T48 增补。

import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '@/api/http';
import {
  getPromptPlaceholders,
  getPromptTemplateDetail,
  getPromptTemplates,
} from '@/api/promptTemplates';
import { VersionCard } from '@/components/prompt/VersionCard';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { compareVersions } from '@/lib/promptTemplate';
import type {
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

interface SceneSectionProps {
  group: PromptGroupView;
  /** 该场景注册表（首张卡展开详情时懒加载；null = 未拉取，分区头不显示注册数）。 */
  registry: PromptScenarioView | null;
  expanded: Record<number, boolean>;
  details: Record<number, PromptDetailView>;
  detailLoading: Record<number, boolean>;
  detailErrors: Record<number, string>;
  onToggleDetail: (group: PromptGroupView, version: PromptVersionView) => void;
}

/** 场景分区：分区头（场景名 + 当前使用徽章 + 注册数）+ activeCount 警示 + 版本卡网格。 */
function SceneSection({
  group,
  registry,
  expanded,
  details,
  detailLoading,
  detailErrors,
  onToggleDetail,
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
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {group.activeCount === 0 ? (
          <p
            className="rounded bg-rose-500/15 px-2 py-1 text-xs text-rose-400"
            role="alert"
            data-testid={`prompt-scene-warning-${briefType}`}
          >
            该场景无启用模板，AI 生成将失败
          </p>
        ) : null}
        {group.activeCount >= 2 ? (
          <p
            className="rounded bg-rose-500/15 px-2 py-1 text-xs text-rose-400"
            role="alert"
            data-testid={`prompt-scene-warning-${briefType}`}
          >
            检测到多个启用版本，生成将取其一，请重新切换激活修复
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
                onToggleDetail={(v) => onToggleDetail(group, v)}
              />
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

/**
 * 提示词模板管理页（T47）：4 场景 × 全部版本一页可见。
 * 列表为轻列表；详情全文与场景注册表按需懒加载（展开首张卡时触发，页面级缓存）。
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
              onToggleDetail={toggleDetail}
            />
          ))}
        </div>
      ) : null}
    </main>
  );
}

export default PromptTemplates;
