// 占位符对照栏（M5 T48，UI 方案 §3.3 交互 4 / §5.2）：编辑器右侧 sticky。
// 条目 = button（点击插入光标处）+ 一句话用途 + 当前文本使用次数；底部未注册占位符 amber 清单；
// 加载/错误态不阻断编辑（错误可重试，重试前后保存校验降级为仅硬校验 + 后端兜底）。

import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import type { PromptPlaceholderItem } from '@/types/promptTemplate';

export interface PlaceholderPanelProps {
  scenarioName: string;
  dormant: boolean;
  note?: string | null;
  items: PromptPlaceholderItem[];
  /** ready = 注册表已加载（未加载/失败时不做未注册判定）。 */
  status: 'loading' | 'error' | 'ready';
  /** 编辑器当前正文（使用次数统计 + 未注册清单同源）。 */
  text: string;
  onRetry: () => void;
  /** 点击插入：由编辑器在 textarea 光标处插入 {{key}}。 */
  onInsert: (key: string) => void;
}

/** 使用次数（键全文出现次数，非去重）。 */
function usageCount(text: string, key: string): number {
  const matches = text.match(new RegExp(`\\{\\{\\s*${key}\\s*\\}\\}`, 'g'));
  return matches ? matches.length : 0;
}

function unregisteredKeys(text: string, items: PromptPlaceholderItem[]): string[] {
  const registered = new Set(items.map((item) => item.key));
  const seen: string[] = [];
  for (const match of text.matchAll(/\{\{\s*(\w+)\s*\}\}/g)) {
    const key = match[1];
    if (!registered.has(key) && !seen.includes(key)) seen.push(key);
  }
  return seen;
}

/** 占位符对照栏：sticky、点击插入、实时使用次数、未注册 amber 警告（编辑期即可见）。 */
export function PlaceholderPanel({
  scenarioName,
  dormant,
  note,
  items,
  status,
  text,
  onRetry,
  onInsert,
}: PlaceholderPanelProps) {
  return (
    <aside
      className="flex flex-col gap-3 rounded-xl bg-card p-4 text-card-foreground shadow-sm ring-1 ring-foreground/10 lg:sticky lg:top-4"
      aria-label="占位符对照"
      data-testid="prompt-ph-panel"
    >
      <div className="flex flex-col gap-1">
        <span className="text-sm font-medium">
          {scenarioName} · {items.length} 项
        </span>
        <span className="text-xs text-muted-foreground">点击插入到光标处</span>
      </div>
      {dormant && note ? (
        <p className="rounded bg-amber-500/15 px-2 py-1 text-xs text-amber-400" data-testid="prompt-ph-dormant-note">
          {note}
        </p>
      ) : null}
      {status === 'loading' ? (
        <div className="flex flex-col gap-2" data-testid="prompt-ph-loading">
          {Array.from({ length: 6 }, (_, i) => (
            <Skeleton key={i} className="h-10 w-full" />
          ))}
        </div>
      ) : status === 'error' ? (
        <div className="flex flex-col items-start gap-2">
          <p className="text-xs text-destructive" role="alert">
            占位符清单加载失败（不影响编辑，保存校验由后端兜底）
          </p>
          <Button variant="outline" size="sm" onClick={onRetry} data-testid="prompt-ph-retry">
            重试
          </Button>
        </div>
      ) : items.length === 0 ? (
        <p className="text-xs text-muted-foreground">该场景暂无注册占位符</p>
      ) : (
        <div className="flex max-h-[28rem] flex-col gap-1 overflow-y-auto">
          {items.map((item) => {
            const count = usageCount(text, item.key);
            return (
              <button
                key={item.key}
                type="button"
                onClick={() => onInsert(item.key)}
                className="flex flex-col items-start gap-0.5 rounded-lg px-2 py-1.5 text-left outline-none transition-colors hover:bg-muted focus-visible:ring-2 focus-visible:ring-ring/50"
                data-testid={`prompt-ph-item-${item.key}`}
              >
                <span className="font-mono text-xs text-sky-300">{`{{${item.key}}}`}</span>
                <span className="flex items-center gap-1 text-xs text-muted-foreground">
                  {item.description ?? '（注册表未登记用途）'}
                  {count > 0 ? <span className="text-muted-foreground/70">×{count}</span> : null}
                </span>
              </button>
            );
          })}
        </div>
      )}
      {status === 'ready' && unregisteredKeys(text, items).length > 0 ? (
        <div className="flex flex-col gap-1" data-testid="prompt-ph-unregistered">
          <span className="text-xs text-amber-400">文本中未注册占位符</span>
          {unregisteredKeys(text, items).map((key) => (
            <span key={key} className="font-mono text-xs text-amber-400">{`{{${key}}}`}</span>
          ))}
        </div>
      ) : null}
    </aside>
  );
}
