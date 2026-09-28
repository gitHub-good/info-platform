import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '@/api/http';
import {
  getRecommendationCardsPaged,
  markRecommendationRead,
  postRecommendationFeedback,
} from '@/api/recommendation';
import { trackReadingOnce } from '@/api/readingEvent';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Pagination } from '@/components/ui/pagination';
import { Skeleton } from '@/components/ui/skeleton';
import { currentRoute, queryOf } from '@/lib/navigation';
import { formatDateTime } from '@/lib/format';
import { cn } from '@/lib/utils';
import {
  DIRECTION_LABELS,
  EVENT_TYPE_LABELS,
  IMPORTANCE_LABELS,
  labelOf,
} from '@/types/industryHeat';
import {
  REC_LEVEL_LABELS,
  type RecommendationCardItem,
  type RecommendationFeedbackAction,
} from '@/types/recommendation';

// 推荐中心页（M16 T135，#/recommendations 全站第 18 页「分析」组——方案 §4.8 + REQ 故事 4）。
// 动态推荐卡片流：事件头徽章（类型/重要度/方向/层级）/logicChain 逻辑链突出展示（零新增事实红线）/
// 标的区 chips（inWatchlist 标识 + 跳详情 + 加自选）/关键数字/原文引用外链；
// 操作条四动作（有用/不感兴趣/加自选/撤销降频）反馈后即时态更新；
// 级别/类型/方向三维筛选变更回第 1 页骨架重查；M25 T224 分页化：beforeId 游标「加载更多」退役，
// 接入 M9 Pagination（page/size 页码模式消费 T220 契约——翻页在途保留/失败重试/空页回退）；
// focus 参数定位高亮（SSE 跳转落地，从简裁量：聚焦当前页命中行 + 首页自动已读）+
// 曝光（RECOMMENDATION_VIEW）/点击（read 展开即采纳）埋点；三态齐备。

/** 缺省每页条数（后端 size 缺省 20，上限 50）。 */
const DEFAULT_PAGE_SIZE = 20;

function messageOf(err: unknown, fallback: string): string {
  return err instanceof ApiError && err.msg ? err.msg : fallback;
}

/** 方向徽章（沿 A 股惯例：利好红 / 利空绿 / 中性灰）。 */
function DirectionBadge({ item }: { item: RecommendationCardItem }) {
  const tone =
    item.direction === 'BULLISH'
      ? 'bg-red-500/15 text-red-500'
      : item.direction === 'BEARISH'
        ? 'bg-green-500/15 text-green-500'
        : 'bg-muted text-muted-foreground';
  return (
    <Badge className={tone} data-testid={`rec-direction-${item.id}`}>
      {labelOf(DIRECTION_LABELS, item.direction)}
    </Badge>
  );
}

/** 层级徽章（P1 标的直接 / P2 行业 / P3 订阅）。 */
function LevelBadge({ item }: { item: RecommendationCardItem }) {
  return (
    <Badge className="bg-violet-500/15 text-violet-400" data-testid={`rec-level-${item.id}`}>
      {labelOf(REC_LEVEL_LABELS, item.level)}
    </Badge>
  );
}

/** 页内卡片态（feedbackAction/muted/read 等即时更新；feedbackError 就地提示）。 */
interface MutableCard extends RecommendationCardItem {
  feedbackError: string | null;
}

/** 单卡操作反馈错误（就地提示，不打断卡片流）。 */
function FeedbackError({ cardId, message }: { cardId: number; message: string | null }) {
  if (!message) return null;
  return (
    <p className="text-xs text-destructive" role="alert" data-testid={`rec-feedback-error-${cardId}`}>
      {message}
    </p>
  );
}

/** 推荐卡片（九区块 + 操作条；反馈后即时态更新由父层 items 替换承载）。 */
function RecommendationCardView({
  item,
  focused,
  onFeedback,
  onRead,
}: {
  item: MutableCard;
  focused: boolean;
  onFeedback: (
    item: RecommendationCardItem,
    action: Exclude<RecommendationFeedbackAction, null>,
    subjectCode?: string,
  ) => void;
  onRead: (item: RecommendationCardItem) => void;
}) {
  const usefulDone = item.feedbackAction === 'USEFUL' || item.feedbackAction === 'ADD_WATCHLIST';
  return (
    <Card data-testid={`rec-card-${item.id}`} className={cn(focused && 'ring-2 ring-primary')}>
      <CardContent className="flex flex-col gap-2 p-4">
        {focused ? (
          <span className="sr-only" data-testid={`rec-focus-${item.id}`}>
            推荐卡片定位
          </span>
        ) : null}
        <div className="flex flex-wrap items-center gap-2">
          <Badge className="bg-sky-500/15 text-sky-400" data-testid={`rec-type-${item.id}`}>
            {labelOf(EVENT_TYPE_LABELS, item.eventType)}
          </Badge>
          <DirectionBadge item={item} />
          <Badge
            className={
              item.importance === 'HIGH'
                ? 'bg-amber-500/25 font-medium text-amber-300'
                : 'bg-amber-500/15 text-amber-400'
            }
            data-testid={`rec-importance-${item.id}`}
          >
            重要度 {labelOf(IMPORTANCE_LABELS, item.importance)}
          </Badge>
          <LevelBadge item={item} />
          {item.muted ? (
            <Badge className="bg-muted text-muted-foreground" data-testid={`rec-muted-${item.id}`}>
              已降频
            </Badge>
          ) : null}
          {!item.read ? (
            <span
              className="size-1.5 shrink-0 rounded-full bg-primary"
              aria-label="未读"
              data-testid={`rec-unread-${item.id}`}
            />
          ) : null}
          <span
            className="ml-auto text-xs text-muted-foreground"
            data-testid={`rec-time-${item.id}`}
          >
            {item.eventTime ? formatDateTime(item.eventTime) : '--'}
          </span>
        </div>

        <p className="text-sm font-medium">{item.summary ?? `事件 #${item.eventId}`}</p>

        {/* 逻辑链突出展示（可解释红线：三环节全部来自结构化事实） */}
        <p
          className="rounded-md border border-primary/20 bg-primary/5 px-2 py-1.5 text-sm text-foreground/90"
          data-testid={`rec-logic-${item.id}`}
        >
          {item.logicChain}
        </p>

        {item.industries.length > 0 ? (
          <div className="flex flex-wrap gap-1" data-testid={`rec-industries-${item.id}`}>
            {item.industries.map((industry) => (
              <a
                key={industry}
                href={`#/industry-heat?industry=${encodeURIComponent(industry)}`}
                className="rounded bg-secondary px-1.5 py-0.5 text-xs text-secondary-foreground underline-offset-2 transition-colors hover:bg-secondary/70 hover:underline"
              >
                {industry}
              </a>
            ))}
          </div>
        ) : null}

        {item.figures.length > 0 ? (
          <div className="flex flex-wrap gap-1" data-testid={`rec-figures-${item.id}`}>
            {item.figures.map((figure, idx) => (
              <Badge key={idx} className="bg-muted font-mono text-muted-foreground">
                {figure.label}: {figure.value}
                {figure.unit}
              </Badge>
            ))}
          </div>
        ) : null}

        {item.subjects.length > 0 ? (
          <div className="flex flex-wrap items-center gap-1" data-testid={`rec-subjects-${item.id}`}>
            {item.subjects.map((subject, idx) => (
              <span key={idx} className="inline-flex items-center gap-1">
                {subject.code ? (
                  <a
                    href={`#/subjects/${subject.code}`}
                    onClick={() => onRead(item)}
                    title={subject.industry ? `${subject.name} · ${subject.industry}` : subject.name ?? ''}
                    data-testid={`rec-subject-${subject.code}`}
                    className="rounded bg-primary/10 px-1.5 py-0.5 text-xs text-primary underline underline-offset-2 transition-colors hover:bg-primary/20"
                  >
                    {subject.name}
                  </a>
                ) : (
                  <Badge variant="outline">{subject.name}</Badge>
                )}
                {subject.inWatchlist ? (
                  <Badge
                    variant="secondary"
                    className="text-[10px]"
                    data-testid={`rec-inwatch-${subject.code}`}
                  >
                    已自选
                  </Badge>
                ) : subject.code ? (
                  <button
                    type="button"
                    onClick={() => onFeedback(item, 'ADD_WATCHLIST', subject.code ?? undefined)}
                    data-testid={`rec-addwatch-${item.id}-${subject.code}`}
                    className="rounded border border-border px-1.5 py-0.5 text-[10px] text-muted-foreground transition-colors hover:border-primary/40 hover:text-primary"
                  >
                    加自选
                  </button>
                ) : null}
              </span>
            ))}
          </div>
        ) : null}

        {item.quote ? (
          <p
            className="border-l-2 border-border pl-2 text-xs text-muted-foreground"
            data-testid={`rec-quote-${item.id}`}
          >
            原文引用：「{item.quote}」
          </p>
        ) : null}

        <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
          <span className="min-w-0 truncate" title={item.newsTitle ?? undefined}>
            {item.newsTitle ?? `资讯 #${item.newsId}`}
          </span>
          {item.newsUrl ? (
            <a
              href={item.newsUrl}
              target="_blank"
              rel="noreferrer"
              onClick={() => onRead(item)}
              data-testid={`rec-link-${item.id}`}
              className="shrink-0 underline underline-offset-2 hover:text-foreground"
            >
              查看原文
            </a>
          ) : null}
        </div>

        {/* 操作条：有用 / 不感兴趣 / 撤销降频（muted 时）——反馈后即时态更新 */}
        <div className="flex flex-wrap items-center gap-2 border-t border-border pt-2">
          <Button
            variant="outline"
            size="sm"
            disabled={usefulDone}
            onClick={() => onFeedback(item, 'USEFUL')}
            data-testid={`rec-useful-${item.id}`}
          >
            {usefulDone ? '已反馈有用' : '有用'}
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={() => onFeedback(item, 'DISLIKE')}
            data-testid={`rec-dislike-${item.id}`}
          >
            不感兴趣
          </Button>
          {item.muted ? (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => onFeedback(item, 'UNDO_MUTE')}
              data-testid={`rec-undomute-${item.id}`}
            >
              撤销降频
            </Button>
          ) : null}
        </div>
        <FeedbackError cardId={item.id} message={item.feedbackError} />
        <p className="text-[10px] text-muted-foreground">AI 分析仅供参考</p>
      </CardContent>
    </Card>
  );
}

interface FilterState {
  level: string;
  eventType: string;
  direction: string;
}

const INITIAL_FILTERS: FilterState = { level: '', eventType: '', direction: '' };

/** 下拉筛选行（级别 P1/P2/P3 / 类型 9 枚举 / 方向；变更即回第 1 页重拉）。 */
function FilterRow({
  filters,
  onChange,
}: {
  filters: FilterState;
  onChange: (key: keyof FilterState, value: string) => void;
}) {
  const selectClass =
    'h-8 rounded-md border bg-background px-2 text-xs text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50';
  return (
    <div className="flex flex-wrap items-center gap-2" data-testid="rec-filters">
      <select
        aria-label="关联层级"
        data-testid="rec-filter-level"
        value={filters.level}
        onChange={(e) => onChange('level', e.target.value)}
        className={selectClass}
      >
        <option value="">全部层级</option>
        {Object.entries(REC_LEVEL_LABELS).map(([value, label]) => (
          <option key={value} value={value}>
            {label}
          </option>
        ))}
      </select>
      <select
        aria-label="事件类型"
        data-testid="rec-filter-type"
        value={filters.eventType}
        onChange={(e) => onChange('eventType', e.target.value)}
        className={selectClass}
      >
        <option value="">全部类型</option>
        {Object.entries(EVENT_TYPE_LABELS).map(([value, label]) => (
          <option key={value} value={value}>
            {label}
          </option>
        ))}
      </select>
      <select
        aria-label="方向"
        data-testid="rec-filter-direction"
        value={filters.direction}
        onChange={(e) => onChange('direction', e.target.value)}
        className={selectClass}
      >
        <option value="">全部方向</option>
        {Object.entries(DIRECTION_LABELS).map(([value, label]) => (
          <option key={value} value={value}>
            {label}
          </option>
        ))}
      </select>
    </div>
  );
}

/** 推荐中心页（第 18 页，「分析」组；M25 T224 分页化）。 */
export function Recommendations() {
  const [filters, setFilters] = useState<FilterState>(INITIAL_FILTERS);
  const [items, setItems] = useState<MutableCard[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  // pageSize 的稳定读取点：翻页回调不随渲染闭包漂移
  const pageSizeRef = useRef(DEFAULT_PAGE_SIZE);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [pageLoading, setPageLoading] = useState(false);
  const [pageError, setPageError] = useState<{ page: number; message: string } | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  // SSE 跳转落地：#/recommendations?focus={refId} 定位高亮（render 期惰性解析一次，纯读 hash 零 effect）
  const [focusId] = useState<number | null>(() => {
    if (typeof window === 'undefined') return null;
    const raw = queryOf(currentRoute()).get('focus');
    const focus = raw != null && raw !== '' ? Number(raw) : NaN;
    return Number.isFinite(focus) ? focus : null;
  });

  /** 空页防御回退（M9 §5.3）：响应 items 空且 total>0 且 page>1 → 页界漂移，静默重发末页。 */
  const fetchView = useCallback(
    async (
      query: { level: string; eventType: string; direction: string },
      target: number,
      size: number,
      signal: AbortSignal,
    ): Promise<{ landed: number; total: number; items: RecommendationCardItem[] }> => {
      const call = (pageNo: number) => getRecommendationCardsPaged({ ...query, page: pageNo, size }, signal);
      let view = await call(target);
      if (view.items.length === 0 && view.total > 0 && target > 1) {
        const last = Math.ceil(view.total / size);
        if (last >= 1 && last < target) {
          view = await call(last);
          return { landed: last, total: view.total, items: view.items };
        }
      }
      return { landed: target, total: view.total, items: view.items };
    },
    [],
  );

  /** 首页落地处理：focus 命中自动已读（SSE 落地语义）+ 曝光埋点（adopt-v1 会话内同卡一次）。 */
  const applyPageArrival = useCallback(
    (cards: RecommendationCardItem[], isLanding: boolean): MutableCard[] => {
      const withErrorState = cards.map((item) => ({ ...item, feedbackError: null }) satisfies MutableCard);
      // SSE 铃铛跳转落地：首页命中 focus 卡即自动已读（展开即采纳，fire-and-forget）
      if (isLanding && focusId != null) {
        const idx = withErrorState.findIndex((item) => item.id === focusId);
        if (idx >= 0 && !withErrorState[idx].read) {
          withErrorState[idx] = { ...withErrorState[idx], read: true };
          void markRecommendationRead(focusId).catch(() => undefined);
        }
      }
      // 曝光埋点（adopt-v1 曝光②）：会话内同卡一次 fire-and-forget
      for (const item of withErrorState) {
        trackReadingOnce(`rec-view:${item.id}`, {
          contentType: 'RECOMMENDATION_VIEW',
          contentRef: String(item.id),
        });
      }
      return withErrorState;
    },
    [focusId],
  );

  /** 骨架通道（首屏/筛选变更 → page=1 新结果集查询，M9 §5.1）。 */
  const loadFirst = useCallback(
    async (nextFilters: FilterState) => {
      abortRef.current?.abort();
      const ctrl = new AbortController();
      abortRef.current = ctrl;
      setLoading(true);
      setError(null);
      setPageLoading(false);
      setPageError(null);
      setPage(1);
      try {
        const view = await fetchView(nextFilters, 1, pageSizeRef.current, ctrl.signal);
        if (ctrl.signal.aborted) return;
        setItems(applyPageArrival(view.items, true));
        setTotal(view.total);
        setPage(view.landed);
      } catch (err) {
        if (ctrl.signal.aborted) return;
        setError(messageOf(err, '推荐卡片加载失败'));
      } finally {
        if (!ctrl.signal.aborted) setLoading(false);
      }
    },
    [fetchView, applyPageArrival],
  );

  // filters 变化（含挂载首查）：回第 1 页骨架重查（前请求 abort 守卫竞态）
  useEffect(() => {
    void loadFirst(filters);
    return () => abortRef.current?.abort();
  }, [filters, loadFirst]);

  /** 在途保留通道（同筛选条件下的翻页/条数切换，M9 §5.1）：列表数据保留，仅列表区在途态。 */
  const fetchPage = useCallback(
    async (target: number, size: number) => {
      abortRef.current?.abort();
      const ctrl = new AbortController();
      abortRef.current = ctrl;
      setPageLoading(true);
      setPageError(null);
      try {
        const view = await fetchView(filters, target, size, ctrl.signal);
        if (ctrl.signal.aborted) return;
        setItems(applyPageArrival(view.items, false));
        setTotal(view.total);
        setPage(view.landed);
      } catch (err) {
        if (ctrl.signal.aborted) return;
        setPageError({ page: target, message: messageOf(err, '加载失败') });
      } finally {
        if (!ctrl.signal.aborted) setPageLoading(false);
      }
    },
    [filters, fetchView, applyPageArrival],
  );

  const handlePageChange = (target: number) => {
    if (target === page) return;
    void fetchPage(target, pageSizeRef.current);
  };

  const handlePageSizeChange = (next: number) => {
    if (next === pageSizeRef.current) return;
    pageSizeRef.current = next;
    setPageSize(next);
    void fetchPage(1, next);
  };

  const handleRetryPage = () => {
    if (pageError) void fetchPage(pageError.page, pageSizeRef.current);
  };

  /** 四动作反馈：即时态更新（失败就地提示、按钮可重试）。 */
  const handleFeedback = (
    item: RecommendationCardItem,
    action: Exclude<RecommendationFeedbackAction, null>,
    subjectCode?: string,
  ) => {
    void (async () => {
      try {
        await postRecommendationFeedback(item.id, action, subjectCode);
        setItems((prev) =>
          prev.map((card) =>
            card.id === item.id
              ? {
                  ...card,
                  feedbackAction: action,
                  muted: action === 'DISLIKE' ? true : action === 'UNDO_MUTE' ? false : card.muted,
                  subjects:
                    action === 'ADD_WATCHLIST'
                      ? card.subjects.map((subject) =>
                          subject.code === subjectCode ? { ...subject, inWatchlist: true } : subject,
                        )
                      : card.subjects,
                  feedbackError: null,
                }
              : card,
          ),
        );
      } catch (err) {
        setItems((prev) =>
          prev.map((card) =>
            card.id === item.id
              ? { ...card, feedbackError: messageOf(err, '反馈失败，请重试') }
              : card,
          ),
        );
      }
    })();
  };

  /** 已读（展开即采纳）：任一跳转前 fire-and-forget。 */
  const handleRead = (item: RecommendationCardItem) => {
    if (item.read) return;
    void markRecommendationRead(item.id).catch(() => undefined);
    setItems((prev) => prev.map((card) => (card.id === item.id ? { ...card, read: true } : card)));
  };

  return (
    <main className="mx-auto w-full max-w-4xl p-4 sm:p-6" data-testid="recommendations-page">
      <header className="mb-4">
        <h1 className="text-xl font-medium">推荐中心</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          事件驱动的动态推荐：逻辑链取自结构化事实（AI 分析仅供参考），可反馈有用/不感兴趣调整后续推送。
          <a
            href="#/market-top"
            data-testid="rec-link-market-top"
            className="ml-1 text-primary underline underline-offset-2"
          >
            全市场视角 → 全市场推荐
          </a>
        </p>
      </header>

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterRow
          filters={filters}
          onChange={(key, value) => setFilters((prev) => ({ ...prev, [key]: value }))}
        />
        <span className="ml-auto text-xs text-muted-foreground" data-testid="rec-total">
          共 {total} 条推荐
        </span>
      </div>

      {loading ? (
        <div className="flex flex-col gap-3" data-testid="rec-loading">
          <Skeleton className="h-40 w-full" />
          <Skeleton className="h-40 w-full" />
          <Skeleton className="h-40 w-full" />
        </div>
      ) : error ? (
        <div className="flex flex-col items-start gap-2" data-testid="rec-error">
          <p className="text-sm text-destructive" role="alert">
            {error}
          </p>
          <Button
            variant="outline"
            size="sm"
            onClick={() => void loadFirst(filters)}
            data-testid="rec-retry"
          >
            重试
          </Button>
        </div>
      ) : items.length === 0 ? (
        <p className="py-10 text-center text-sm text-muted-foreground" data-testid="rec-empty">
          暂无动态推荐：暂无重要性达标且与自选/订阅相关的事件，可稍后刷新或调整关注配置。
        </p>
      ) : (
        <>
          <div className="flex flex-col gap-3" data-testid="rec-list" aria-busy={pageLoading || undefined}>
            {items.map((item) => (
              <RecommendationCardView
                key={item.id}
                item={item}
                focused={item.id === focusId}
                onFeedback={handleFeedback}
                onRead={handleRead}
              />
            ))}
          </div>
          {pageLoading ? (
            <p
              className="mt-3 text-sm text-muted-foreground"
              aria-live="polite"
              data-testid="rec-page-loading"
            >
              加载中…
            </p>
          ) : null}
          {pageError ? (
            <div className="mt-3 flex flex-wrap items-center gap-2" data-testid="rec-pagination-error">
              <p className="text-sm text-destructive" role="alert">
                加载第 {pageError.page} 页失败：{pageError.message}
              </p>
              <Button
                variant="outline"
                size="sm"
                onClick={handleRetryPage}
                data-testid="rec-pagination-retry"
              >
                重试
              </Button>
            </div>
          ) : null}
          <Pagination
            page={page}
            pageSize={pageSize}
            total={total}
            disabled={pageLoading}
            onPageChange={handlePageChange}
            onPageSizeChange={handlePageSizeChange}
            label="推荐中心分页"
            testIdPrefix="rec-pagination"
          />
        </>
      )}
    </main>
  );
}

export default Recommendations;
