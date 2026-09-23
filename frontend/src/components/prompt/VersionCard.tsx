// 版本卡（M5 T47+T48，UI 方案 §3.1/§3.5/§5.2）：卡头常驻（版本号/状态徽章/元信息）+
// 操作行（按状态分化：激活卡仅编辑；置废卡设为激活/编辑/删除）+ 内联详情开关。
// 激活卡 ring-1 ring-emerald-500/40 + emerald「使用中」；置废卡 opacity-60 hover 恢复（D8）。

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { TemplateTextView } from '@/components/prompt/TemplateTextView';
import type { PromptDetailView, PromptVersionView } from '@/types/promptTemplate';
import { cn } from 'cn';

/** 元信息时间格式 MM-dd HH:mm（沿 DatasourceConfig formatTime 口径）。 */
function formatTime(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  const p = (n: number) => String(n).padStart(2, '0');
  return `${p(date.getMonth() + 1)}-${p(date.getDate())} ${p(date.getHours())}:${p(date.getMinutes())}`;
}

export interface VersionCardProps {
  briefType: number;
  version: PromptVersionView;
  /** 详情展开态。 */
  expanded: boolean;
  /** 展开区数据（页面缓存懒加载；null = 未加载/加载中由 detailLoading 区分）。 */
  detail: PromptDetailView | null;
  detailLoading: boolean;
  detailError: string | null;
  /** 注册表键集（占位符着色：未注册 amber；null = 未加载全部按已注册配色）。 */
  registeredKeys: Set<string> | null;
  /** 同场景操作在途锁（激活/删除互斥，UI 方案 §3.5 交互 3）。 */
  busy?: boolean;
  onToggleDetail: (version: PromptVersionView) => void;
  /** 编辑 = 基于此版本创建新版本（保存后自动激活）。 */
  onEdit: (version: PromptVersionView) => void;
  onActivate: (version: PromptVersionView) => void;
  onDelete: (version: PromptVersionView) => void;
}

/** 版本卡：徽章/元信息/操作行（按状态分化）/内联详情开关（UI 方案 §3.1 交互 2~4）。 */
export function VersionCard({
  briefType,
  version,
  expanded,
  detail,
  detailLoading,
  detailError,
  registeredKeys,
  busy = false,
  onToggleDetail,
  onEdit,
  onActivate,
  onDelete,
}: VersionCardProps) {
  const isActive = version.status === 'ACTIVE';
  const idSuffix = `${briefType}-${version.version}`;
  const editTitle = '基于此版本创建新版本，保存后自动激活';

  return (
    <Card
      className={cn(
        'transition-opacity',
        isActive ? 'ring-1 ring-emerald-500/40' : 'opacity-60 hover:opacity-100',
      )}
      data-testid={`prompt-card-${idSuffix}`}
    >
      <CardHeader>
        <CardTitle className="flex flex-wrap items-center gap-2 text-base">
          <span className="font-mono">{version.version}</span>
          {isActive ? (
            <Badge className="bg-emerald-500/15 text-emerald-400">使用中</Badge>
          ) : (
            <Badge variant="secondary">已置废</Badge>
          )}
          <span className="ml-auto text-xs font-normal text-muted-foreground">
            更新 {formatTime(version.updatedAt)} · 占位符 {version.placeholderCount} 项
          </span>
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center gap-2">
          {isActive ? (
            <Button
              size="sm"
              title={editTitle}
              disabled={busy}
              onClick={() => onEdit(version)}
              data-testid={`prompt-edit-${idSuffix}`}
            >
              编辑
            </Button>
          ) : (
            <>
              <Button
                size="sm"
                disabled={busy}
                onClick={() => onActivate(version)}
                data-testid={`prompt-activate-${idSuffix}`}
              >
                设为激活
              </Button>
              <Button
                variant="outline"
                size="sm"
                title={editTitle}
                disabled={busy}
                onClick={() => onEdit(version)}
                data-testid={`prompt-edit-${idSuffix}`}
              >
                编辑
              </Button>
              <Button
                variant="outline"
                size="sm"
                className="hover:text-rose-400"
                disabled={busy}
                onClick={() => onDelete(version)}
                data-testid={`prompt-delete-${idSuffix}`}
              >
                删除
              </Button>
            </>
          )}
          <Button
            variant="ghost"
            size="sm"
            aria-expanded={expanded}
            onClick={() => onToggleDetail(version)}
            data-testid={`prompt-detail-toggle-${idSuffix}`}
          >
            {expanded ? '详情 ∧' : '详情 ∨'}
          </Button>
        </div>
        {expanded ? (
          <div data-testid={`prompt-detail-${idSuffix}`}>
            {detailLoading ? (
              <div className="flex flex-col gap-2" data-testid={`prompt-detail-loading-${idSuffix}`}>
                <Skeleton className="h-16 w-full" />
                <Skeleton className="h-16 w-full" />
              </div>
            ) : detailError ? (
              <p className="text-xs text-destructive" role="alert">
                {detailError}
              </p>
            ) : detail ? (
              <TemplateTextView template={detail.template} registeredKeys={registeredKeys} />
            ) : null}
          </div>
        ) : null}
      </CardContent>
    </Card>
  );
}
