import { useCallback, useEffect, useRef, useState } from 'react';
import { Sparkles } from 'lucide-react';
import { ApiError } from '@/api/http';
import { getPersonalFeed } from '@/api/feed';
import { getDailyRecommendation } from '@/api/recommendation';
import { Badge } from '@/components/ui/badge';
import { Button, buttonVariants } from '@/components/ui/button';
import { Card, CardContent, CardHeader } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { DAILY_RECOMMENDATION_STATUS } from '@/types/recommendation';
import { cn } from '@/lib/utils';

/** 推荐条目展示模型（rank 取列表序位 1..5，与后端 rank 升序一致）。 */
interface RecItem {
  code: string;
  name: string;
  reason: string;
}

/** 卡片相位：读检查中 / 未生成 / 生成中 / 就绪 / 空池 / 错误。 */
type Phase = 'loading' | 'pending' | 'generating' | 'ready' | 'emptyPool' | 'error';

/** 免责声明（对齐后端 BriefContent.DEFAULT_DISCLAIMER，feed 只读路径无该字段时兜底）。 */
const DISCLAIMER = 'AI 生成，非投资建议';

/** 非 ApiError 兜底文案。 */
function messageOf(err: unknown, fallback: string): string {
  return err instanceof ApiError ? err.msg : fallback;
}

/** 推荐理由首行（多行理由截取首行展示）。 */
function firstLine(text: string | null): string {
  return (text ?? '').split('\n')[0]?.trim() ?? '';
}

/**
 * 今日推荐卡（体检 P1-4，概览「今日」区主位）。
 *
 * 数据流（后端不动，双端点分工）：
 * - 挂载只读判读：GET /feed/personal 首页（readDaily 只读语义，不触发生成）——
 *   `recommendationPending=true` → 未生成空态（CTA 立即生成）；
 *   `false` 且含 type=recommendation 条目 → 直接渲染 Top5（就绪态推荐条目确定性排在首页头部）；
 *   `false` 且无推荐条目 → 空池空态（CTA 去自选清单）。
 * - 「立即生成」：GET /recommendations/daily（触发式端点：未生成则受理异步生成并服务端轮询至终态，
 *   已生成/在途幂等直返）——status 1 AI / 2 规则兜底（角标「规则排序」）/ 3 空池。
 * - 挂载不走触发式端点：避免概览（默认落地页）每次打开都可能触发 LLM 重试（FAILED 日重试）烧钱。
 */
export function RecommendationCard() {
  const [phase, setPhase] = useState<Phase>('loading');
  const [items, setItems] = useState<RecItem[]>([]);
  const [fallback, setFallback] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /** 错误来源：load=只读判读失败（重试走只读）/ generate=生成失败（重试走生成端点）。 */
  const errorKindRef = useRef<'load' | 'generate'>('load');
  const abortRef = useRef<AbortController | null>(null);

  const loadReadonly = useCallback(async () => {
    abortRef.current?.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    setPhase('loading');
    setError(null);
    try {
      const page = await getPersonalFeed(null, ctrl.signal);
      if (ctrl.signal.aborted) return;
      const recItems = (page.items ?? [])
        .filter((item) => item.type === 'recommendation')
        .map((item) => ({
          code: item.subjectCode ?? '',
          name: item.subjectName ?? item.title,
          reason: item.summary ?? '',
        }));
      if (page.recommendationPending) {
        setItems([]);
        setPhase('pending');
        return;
      }
      setFallback(false);
      setItems(recItems);
      setPhase(recItems.length > 0 ? 'ready' : 'emptyPool');
    } catch (err) {
      if (ctrl.signal.aborted) return;
      errorKindRef.current = 'load';
      setError(messageOf(err, '推荐加载失败'));
      setPhase('error');
    }
  }, []);

  useEffect(() => {
    void loadReadonly();
    return () => abortRef.current?.abort();
  }, [loadReadonly]);

  /** 立即生成：触发式端点（生成 + 服务端轮询至终态），完成后按状态渲染。 */
  const generate = useCallback(async () => {
    abortRef.current?.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    setPhase('generating');
    setError(null);
    try {
      const view = await getDailyRecommendation(ctrl.signal);
      if (ctrl.signal.aborted) return;
      if (view.status === DAILY_RECOMMENDATION_STATUS.EMPTY) {
        setItems([]);
        setFallback(false);
        setPhase('emptyPool');
        return;
      }
      setFallback(view.status === DAILY_RECOMMENDATION_STATUS.FALLBACK);
      setItems(
        [...view.topRecommend]
          .sort((a, b) => a.rank - b.rank)
          .map((top) => ({ code: top.subjectCode, name: top.subjectName, reason: top.reason })),
      );
      setPhase('ready');
    } catch (err) {
      if (ctrl.signal.aborted) return;
      errorKindRef.current = 'generate';
      setError(messageOf(err, '生成失败，请稍后重试'));
      setPhase('error');
    }
  }, []);

  const retry = useCallback(() => {
    void (errorKindRef.current === 'generate' ? generate() : loadReadonly());
  }, [generate, loadReadonly]);

  return (
    <Card className="h-full" data-testid="rec-card">
      <CardHeader>
        <div className="flex w-full items-center justify-between gap-2">
          <div className="text-xs text-muted-foreground">
            今日推荐<span className="ml-1 opacity-70">（盘前 Top5）</span>
          </div>
          {phase === 'ready' && fallback ? (
            <Badge
              variant="ghost"
              className="bg-amber-500/15 text-amber-400"
              data-testid="rec-fallback-badge"
            >
              规则排序
            </Badge>
          ) : null}
        </div>
      </CardHeader>
      <CardContent className="flex flex-1 flex-col">
        {phase === 'loading' ? (
          <div className="flex flex-col gap-2" data-testid="rec-loading">
            {Array.from({ length: 3 }, (_, index) => (
              <Skeleton key={index} className="h-10 w-full" />
            ))}
          </div>
        ) : phase === 'pending' ? (
          <div className="flex flex-1 flex-col items-center justify-center gap-2 py-6 text-center" data-testid="rec-pending">
            <span className="flex size-9 items-center justify-center rounded-full bg-muted">
              <Sparkles className="size-4 text-muted-foreground" aria-hidden="true" />
            </span>
            <p className="text-sm text-muted-foreground">今日推荐尚未生成</p>
            <p className="text-xs text-muted-foreground">
              基于你的自选池与订阅画像，生成盘前关注度 Top5
            </p>
            <Button size="sm" className="mt-1" onClick={() => void generate()} data-testid="rec-generate">
              立即生成
            </Button>
          </div>
        ) : phase === 'generating' ? (
          <div className="flex flex-col gap-2" data-testid="rec-generating">
            {Array.from({ length: 3 }, (_, index) => (
              <Skeleton key={index} className="h-10 w-full animate-pulse" />
            ))}
            <p className="py-1 text-center text-xs text-muted-foreground">
              AI 生成中，约需数秒…
            </p>
          </div>
        ) : phase === 'emptyPool' ? (
          <div className="flex flex-1 flex-col items-center justify-center gap-2 py-6 text-center" data-testid="rec-empty-pool">
            <p className="text-sm text-muted-foreground">自选池为空，暂无可推荐标的</p>
            <p className="text-xs text-muted-foreground">添加自选标的后，将按信息面活跃度生成 Top5</p>
            <a
              href="#/watchlists"
              className={cn('mt-1', buttonVariants({ size: 'sm' }))}
              data-testid="rec-empty-pool-cta"
            >
              去自选清单
            </a>
          </div>
        ) : phase === 'error' ? (
          <div className="flex flex-col items-start gap-2" data-testid="rec-error">
            <p className="text-sm text-destructive" role="alert">
              {error}
            </p>
            <Button variant="outline" size="sm" onClick={retry} data-testid="rec-retry">
              重试
            </Button>
          </div>
        ) : (
          <>
            <div className="flex flex-col" data-testid="rec-list">
              {items.map((item, index) => (
                <a
                  key={`${item.code}-${index}`}
                  href={`#/subjects/${encodeURIComponent(item.code)}`}
                  className="flex items-start gap-2.5 rounded-lg px-1.5 py-1.5 transition-colors hover:bg-muted focus-visible:ring-2 focus-visible:ring-ring/50 outline-none"
                  data-testid={`rec-item-${index + 1}`}
                >
                  <span className="w-4 shrink-0 pt-0.5 text-center text-xs tabular-nums text-muted-foreground">
                    {index + 1}
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm">
                      <span className="font-medium">{item.code}</span>
                      {item.name ? <span className="ml-2 text-muted-foreground">{item.name}</span> : null}
                    </span>
                    {item.reason ? (
                      <span className="mt-0.5 block truncate text-xs text-muted-foreground" title={firstLine(item.reason)}>
                        {firstLine(item.reason)}
                      </span>
                    ) : null}
                  </span>
                </a>
              ))}
            </div>
            <p className="mt-2 text-xs text-muted-foreground/70" data-testid="rec-disclaimer">
              {DISCLAIMER}
            </p>
          </>
        )}
      </CardContent>
    </Card>
  );
}
