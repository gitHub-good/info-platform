import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '@/api/http';
import { getEventImpactChains, getEventsPaged } from '@/api/eventStream';
import { Badge } from '@/components/ui/badge';
import { PageHeader } from '@/components/ui/PageHeader';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { MarketTabs } from '@/components/common/MarketTabs';
import { EmptyState } from '@/components/ui/EmptyState';
import { Pagination } from '@/components/ui/pagination';
import { Skeleton } from '@/components/ui/skeleton';
import { useMarketParam } from '@/hooks/useMarketParam';
import type { MarketKey } from '@/lib/market';
import { marketOf } from '@/lib/market';
import { directionToneClass, formatDateTime } from '@/lib/format';
import {
  DIRECTION_LABELS,
  EVENT_TYPE_LABELS,
  IMPORTANCE_LABELS,
  labelOf,
} from '@/types/industryHeat';
import {
  SW_INDUSTRIES,
  type EventCard,
  type EventIndustryFilterGroup,
  type ImpactChainView,
} from '@/types/eventStream';

// 事件流页（M15 T127，#/events 全站第 17 页——方案 §4.8 + REQ 故事 3）。
// L2 结构化事件全字段卡片流：类型/方向（沿 A 股惯例利好红利空绿）/重要度（高>中>低）徽章、
// 影响行业 chips、关键数字 chips（原文可回溯）、subjects 可点跳标的详情、quote 原文引用 + 原文外链；
// 四维筛选（类型 9 枚举/行业随市场分组/重要度/方向）变更回第 1 页骨架重查。
// M25 T224 分页化：beforeId 游标「加载更多」退役，接入 M9 Pagination（page/size 页码模式，
// 消费 T220 契约）——翻页在途保留数据、失败可重试、末页收缩空页回退（M9 §5 语义）。
// M29 T257：三市场切换（?market= 持久化；market 过滤 = 事件关联标的含该市场标的，A股缺省不过滤零回归；
// 行业过滤器随响应 industryFilterGroups 分组切换，后端在途缺省回 SW 31 既有口径——容错不报错）。
// 落点聚焦（M20 T173）从简裁量留档：页码模式下聚焦「当前页命中行」高亮滚入，不做定向跨页回溯。
// 三态齐备：加载骨架 / 空态引导 / 错误重试；受保护接口 401 由 http 层统一跳登录。

/** 缺省每页条数（后端 size 缺省 20，上限 50）。 */
const DEFAULT_PAGE_SIZE = 20;

function messageOf(err: unknown, fallback: string): string {
  return err instanceof ApiError ? err.msg : fallback;
}

/** 事件方向徽章（利好红 / 利空绿 / 中性灰，A 股惯例——方向轨单点 directionToneClass，T229）。 */
function DirectionBadge({ direction, testId }: { direction: string; testId: string }) {
  const label = labelOf(DIRECTION_LABELS, direction);
  const tone =
    direction === 'BULLISH'
      ? directionToneClass('up')
      : direction === 'BEARISH'
        ? directionToneClass('down')
        : directionToneClass('flat');
  return (
    <Badge className={tone} data-testid={testId}>
      {label}
    </Badge>
  );
}

/** 重要度徽章（HIGH > MEDIUM > LOW 视觉分层：高强调、中常规、低弱化）。 */
function ImportanceBadge({ importance, testId }: { importance: string; testId: string }) {
  const label = labelOf(IMPORTANCE_LABELS, importance);
  const tone =
    importance === 'HIGH'
      ? 'bg-amber-500/25 font-medium text-amber-300'
      : importance === 'MEDIUM'
        ? 'bg-amber-500/15 text-amber-400'
        : 'bg-muted text-muted-foreground';
  return (
    <Badge className={tone} data-testid={testId}>
      重要度 {label}
    </Badge>
  );
}

/** 事件影响链区块（M17 T144，事件详情扩展承载）：首展按需拉取（HIGH 缓存直返/MEDIUM 服务端按需生成/LOW 空态）； 命中缓存由父级（卡片）持有，收起再展开不重复请求；行业/方向/逻辑链/依据展开 + 模板态与免责标注。 */
function ImpactChainSection({
  eventId,
  initialView,
  onView,
}: {
  eventId: number;
  initialView: ImpactChainView | null;
  onView: (view: ImpactChainView) => void;
}) {
  const [reloadToken, setReloadToken] = useState(0);
  const [loading, setLoading] = useState(initialView == null);
  const [error, setError] = useState<string | null>(null);
  const [expandedBasis, setExpandedBasis] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const hasCache = initialView != null;

  useEffect(() => {
    if (hasCache) return; // 卡片级缓存命中：不重复请求
    abortRef.current?.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    getEventImpactChains(eventId, ctrl.signal)
      .then((data) => {
        if (ctrl.signal.aborted) return;
        onView(data);
        setError(null);
      })
      .catch((err: unknown) => {
        if (ctrl.signal.aborted) return;
        setError(err instanceof ApiError ? err.msg : '影响链加载失败');
      })
      .finally(() => {
        if (!ctrl.signal.aborted) setLoading(false);
      });
    return () => abortRef.current?.abort();
  }, [eventId, reloadToken, hasCache, onView]);

  const view = initialView;

  return (
    <div
      className="flex flex-col gap-2 rounded-lg border bg-muted/20 p-3"
      data-testid={`impact-chain-section-${eventId}`}
    >
      {loading ? (
        <div className="flex flex-col gap-2" data-testid={`impact-chain-loading-${eventId}`}>
          <Skeleton className="h-8 w-full" />
          <Skeleton className="h-8 w-full" />
        </div>
      ) : error ? (
        <div className="flex flex-col items-start gap-1" data-testid={`impact-chain-error-${eventId}`}>
          <p className="text-xs text-destructive" role="alert">
            {error}
          </p>
          <Button
            variant="outline"
            size="sm"
            onClick={() => {
              setError(null);
              setLoading(true);
              setReloadToken((token) => token + 1);
            }}
            data-testid={`impact-chain-retry-${eventId}`}
          >
            重试
          </Button>
        </div>
      ) : view?.eligibility === 'LOW_SKIPPED' || (view?.chains.length ?? 0) === 0 ? (
        <p
          className="py-2 text-center text-xs text-muted-foreground"
          data-testid={`impact-chain-empty-${eventId}`}
        >
          低重要度事件不生成行业影响链（高重要度自动生成、中重要度首次展开生成）。
        </p>
      ) : (
        <>
          {view?.chains.map((chain) => (
            <div
              key={chain.id}
              className="flex flex-col gap-1"
              data-testid={`impact-chain-row-${chain.industry}`}
            >
              <div className="flex flex-wrap items-center gap-2 text-sm">
                <span className="font-medium">{chain.industry}</span>
                <DirectionBadge
                  direction={chain.direction}
                  testId={`impact-chain-direction-${chain.industry}`}
                />
                <span className="min-w-0 flex-1 text-xs text-muted-foreground">
                  {chain.logicChain}
                </span>
                <button
                  type="button"
                  className="text-xs text-primary underline underline-offset-2"
                  onClick={() =>
                    setExpandedBasis(expandedBasis === chain.industry ? null : chain.industry)
                  }
                  data-testid={`impact-chain-basis-${chain.industry}`}
                >
                  依据
                </button>
              </div>
              {expandedBasis === chain.industry ? (
                <p
                  className="border-l-2 border-border pl-2 text-xs text-muted-foreground"
                  data-testid={`impact-chain-basis-detail-${chain.industry}`}
                >
                  信号来源：资讯 #{String(chain.basis?.newsId ?? '--')}
                  {chain.basis?.quote ? ` ｜ 原文引用：「${chain.basis.quote}」` : ''}（模板：
                  {chain.templateKey}）
                </p>
              ) : null}
            </div>
          ))}
          <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
            <Badge className="bg-muted" data-testid={`impact-chain-genmethod-${eventId}`}>
              模板规则生成
            </Badge>
            <span data-testid={`impact-chain-disclaimer-${eventId}`}>{view?.disclaimer}</span>
          </div>
        </>
      )}
    </div>
  );
}

/** 事件卡片（全字段面：徽章行 / 摘要 / 行业与关键数字 chips / 标的 / 引用与外链 / 影响链扩展区块）。 */
function EventCardView({ event }: { event: EventCard }) {
  const [chainOpen, setChainOpen] = useState(false);
  const [chainView, setChainView] = useState<ImpactChainView | null>(null);
  return (
    <Card data-testid={`event-card-${event.id}`}>
      <CardContent className="flex flex-col gap-2 p-4">
        <div className="flex flex-wrap items-center gap-2">
          <Badge className="bg-sky-500/15 text-sky-400" data-testid={`event-type-${event.id}`}>
            {labelOf(EVENT_TYPE_LABELS, event.eventType)}
          </Badge>
          <DirectionBadge
            direction={event.direction}
            testId={`event-direction-${event.id}`}
          />
          <ImportanceBadge
            importance={event.importance}
            testId={`event-importance-${event.id}`}
          />
          <span
            className="ml-auto text-xs text-muted-foreground"
            data-testid={`event-time-${event.id}`}
          >
            {event.eventTime ? formatDateTime(event.eventTime) : '--'}
          </span>
        </div>

        <p className="text-sm font-medium">{event.summary}</p>

        {event.industries.length > 0 ? (
          <div className="flex flex-wrap gap-1" data-testid={`event-industries-${event.id}`}>
            {event.industries.map((industry) => (
              <Badge key={industry} variant="secondary">
                {industry}
              </Badge>
            ))}
          </div>
        ) : null}

        {event.figures.length > 0 ? (
          <div className="flex flex-wrap gap-1" data-testid={`event-figures-${event.id}`}>
            {event.figures.map((figure, idx) => (
              <Badge key={idx} className="bg-muted font-mono text-muted-foreground">
                {figure.label}: {figure.value}
                {figure.unit}
              </Badge>
            ))}
          </div>
        ) : null}

        {event.subjects.length > 0 ? (
          <div className="flex flex-wrap gap-1" data-testid={`event-subjects-${event.id}`}>
            {event.subjects.map((subject, idx) =>
              subject.code ? (
                <a
                  key={idx}
                  href={`#/subjects/${subject.code}`}
                  title={subject.industry ? `${subject.name} · ${subject.industry}` : subject.name ?? ''}
                  data-testid={`event-subject-${subject.code}`}
                  className="rounded bg-primary/10 px-1.5 py-0.5 text-xs text-primary underline underline-offset-2 transition-colors hover:bg-primary/20"
                >
                  {subject.name}
                </a>
              ) : (
                <Badge key={idx} variant="outline">
                  {subject.name}
                </Badge>
              ),
            )}
          </div>
        ) : null}

        {event.quote ? (
          <p
            className="border-l-2 border-border pl-2 text-xs text-muted-foreground"
            data-testid={`event-quote-${event.id}`}
          >
            原文引用：「{event.quote}」
          </p>
        ) : null}

        <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
          <span className="min-w-0 truncate" title={event.newsTitle ?? undefined}>
            {event.newsTitle ?? `资讯 #${event.newsId}`}
          </span>
          {event.newsUrl ? (
            <a
              href={event.newsUrl}
              target="_blank"
              rel="noreferrer"
              data-testid={`event-link-${event.id}`}
              className="shrink-0 underline underline-offset-2 hover:text-foreground"
            >
              查看原文
            </a>
          ) : null}
          <button
            type="button"
            className="ml-auto shrink-0 text-primary underline underline-offset-2"
            aria-expanded={chainOpen}
            onClick={() => setChainOpen((prev) => !prev)}
            data-testid={`impact-chain-toggle-${event.id}`}
          >
            行业影响链
          </button>
        </div>

        {chainOpen ? (
          <ImpactChainSection
            eventId={event.id}
            initialView={chainView}
            onView={setChainView}
          />
        ) : null}
      </CardContent>
    </Card>
  );
}

interface FilterState {
  type: string;
  industry: string;
  importance: string;
  direction: string;
  /** 市场过滤（M29：入 filters 走既有「变更回第 1 页重查」通道；A_SHARE = 不过滤零回归）。 */
  market: MarketKey;
}

const INITIAL_FILTERS: FilterState = {
  type: '',
  industry: '',
  importance: '',
  direction: '',
  market: 'A_SHARE',
};

/** 下拉筛选行（类型 9 枚举 / 行业随市场分组 / 重要度 / 方向；变更即回第 1 页重拉）。 */
function FilterRow({
  filters,
  industryOptions,
  onChange,
}: {
  filters: FilterState;
  /** 行业下拉选项（当前市场的枚举集——响应 industryFilterGroups 分组；缺省回 SW 31 既有口径）。 */
  industryOptions: readonly string[];
  onChange: (key: keyof FilterState, value: string) => void;
}) {
  const selectClass =
    'h-8 rounded-md border bg-background px-2 text-xs text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50';
  return (
    <div className="flex flex-wrap items-center gap-2" data-testid="events-filters">
      <select
        aria-label="事件类型"
        data-testid="events-filter-type"
        value={filters.type}
        onChange={(e) => onChange('type', e.target.value)}
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
        aria-label="影响行业"
        data-testid="events-filter-industry"
        value={filters.industry}
        onChange={(e) => onChange('industry', e.target.value)}
        className={selectClass}
      >
        <option value="">全部行业</option>
        {industryOptions.map((industry) => (
          <option key={industry} value={industry}>
            {industry}
          </option>
        ))}
      </select>
      <select
        aria-label="重要度"
        data-testid="events-filter-importance"
        value={filters.importance}
        onChange={(e) => onChange('importance', e.target.value)}
        className={selectClass}
      >
        <option value="">全部重要度</option>
        {Object.entries(IMPORTANCE_LABELS).map(([value, label]) => (
          <option key={value} value={value}>
            {label}
          </option>
        ))}
      </select>
      <select
        aria-label="方向"
        data-testid="events-filter-direction"
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

/** 事件流页（第 17 页，「分析」组；M25 T224 分页化 + M29 T257 三市场切换）。 */
export function Events() {
  // 三市场切换（M29 T257）：URL ?market= 持久化（挂载读初值——深链直达）；切换并进 filters 走既有重查通道
  const [market, setMarket] = useMarketParam();
  const [filters, setFilters] = useState<FilterState>(() => ({ ...INITIAL_FILTERS, market }));
  const [items, setItems] = useState<EventCard[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  // 行业过滤器分组（M29 §5.4 契约增量：响应携带则按市场分组；后端在途缺省 null → 回 SW 31 口径）
  const [industryGroups, setIndustryGroups] = useState<EventIndustryFilterGroup[] | null>(null);
  // pageSize 的稳定读取点：翻页回调不随渲染闭包漂移
  const pageSizeRef = useRef(DEFAULT_PAGE_SIZE);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [pageLoading, setPageLoading] = useState(false);
  const [pageError, setPageError] = useState<{ page: number; message: string } | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  // 落点聚焦（M20 T173 依据事件下钻）：#/events?focus=<eventId> 定位高亮该事件卡——
  // T224 从简裁量：聚焦当前页命中行即滚入视口高亮（不定向跨页回溯）
  const [focusId] = useState<number | null>(() => {
    const raw = new URLSearchParams(window.location.hash.split('?')[1] ?? '').get('focus');
    const parsed = raw == null ? null : Number(raw);
    return parsed != null && Number.isInteger(parsed) && parsed > 0 ? parsed : null;
  });

  /** 行业下拉选项：当前市场的枚举集（分组缺省回 SW 31——A 股既有口径零回归）。 */
  const industryOptions: readonly string[] =
    industryGroups?.find((group) => marketOf(group.market) === filters.market)?.industries ??
    SW_INDUSTRIES;

  /** 空页防御回退（M9 §5.3）：响应 items 空且 total>0 且 page>1 → 页界漂移，静默重发末页。 */
  const fetchView = useCallback(
    async (
      query: {
        type: string;
        industry: string;
        importance: string;
        direction: string;
        market: MarketKey;
      },
      target: number,
      size: number,
      signal: AbortSignal,
    ): Promise<{ landed: number; total: number; items: EventCard[] }> => {
      // market=A_SHARE 不下发（§5.4 缺省不过滤——A 股视角零回归）；非 A 股下发，后端未识别即忽略回全量（容错）
      const call = (pageNo: number) =>
        getEventsPaged(
          {
            ...query,
            market: query.market !== 'A_SHARE' ? query.market : undefined,
            page: pageNo,
            size,
          },
          signal,
        );
      let view = await call(target);
      setIndustryGroups(view.industryFilterGroups ?? null);
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
        setItems(view.items);
        setTotal(view.total);
        setPage(view.landed);
      } catch (err) {
        if (ctrl.signal.aborted) return;
        setError(messageOf(err, '事件流加载失败'));
      } finally {
        if (!ctrl.signal.aborted) setLoading(false);
      }
    },
    [fetchView],
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
        setItems(view.items);
        setTotal(view.total);
        setPage(view.landed);
      } catch (err) {
        if (ctrl.signal.aborted) return;
        setPageError({ page: target, message: messageOf(err, '加载失败') });
      } finally {
        if (!ctrl.signal.aborted) setPageLoading(false);
      }
    },
    [filters, fetchView],
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

  // 聚焦卡命中当前页 → 滚入视口（jsdom 无 scrollIntoView 实现时静默跳过，高亮仍生效）
  useEffect(() => {
    if (focusId == null) return;
    const el = document.querySelector(`[data-testid="event-focus-${focusId}"]`);
    if (el && typeof el.scrollIntoView === 'function') {
      el.scrollIntoView({ block: 'center' });
    }
  }, [focusId, items]);

  /** 市场切换（M29）：并进 filters 驱动既有「回第 1 页重查」通道；行业筛选随枚举集切换清空。 */
  const handleMarketChange = (next: MarketKey) => {
    setMarket(next);
    setFilters((prev) => (prev.market === next ? prev : { ...prev, market: next, industry: '' }));
  };

  return (
    <main className="mx-auto w-full max-w-4xl p-4 sm:p-6" data-testid="events-page">
      <PageHeader
        title="事件流"
        subtitle="AI 管道提取的结构化事件：按市场/类型/行业/重要度/方向筛选，关键数字与引用取自原文可回溯。"
      />

      {/* 三市场切换（M29：统一 MarketTabs，Tab 不隐藏——拍板三；A股 = 不过滤零回归） */}
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <MarketTabs value={filters.market} onChange={handleMarketChange} />
      </div>

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterRow
          filters={filters}
          industryOptions={industryOptions}
          onChange={(key, value) => setFilters((prev) => ({ ...prev, [key]: value }))}
        />
        <span
          className="ml-auto text-xs text-muted-foreground"
          data-testid="events-total"
        >
          共 {total} 条事件
        </span>
      </div>

      {loading ? (
        <div className="flex flex-col gap-3" data-testid="events-loading">
          <Skeleton className="h-28 w-full" />
          <Skeleton className="h-28 w-full" />
          <Skeleton className="h-28 w-full" />
        </div>
      ) : error ? (
        <div className="flex flex-col items-start gap-2" data-testid="events-error">
          <p className="text-sm text-destructive" role="alert">
            {error}
          </p>
          <Button
            variant="outline"
            size="sm"
            onClick={() => void loadFirst(filters)}
            data-testid="events-retry"
          >
            重试
          </Button>
        </div>
      ) : items.length === 0 ? (
        <EmptyState
          title="暂无事件"
          description={
            filters.market === 'A_SHARE'
              ? 'AI 管道按配额提取高价值结构化事件（L2 产出），可稍后刷新或放宽筛选。'
              : '该市场事件随港美股资讯源接入逐步积累（覆盖不足如实呈现，不隐藏不填充）；可稍后刷新、放宽筛选或切换市场。'
          }
          testId="events-empty"
        />
      ) : (
        <>
          <div className="flex flex-col gap-3" data-testid="events-list" aria-busy={pageLoading || undefined}>
            {items.map((event) =>
              event.id === focusId ? (
                <div
                  key={event.id}
                  className="rounded-lg ring-2 ring-primary/60"
                  data-testid={`event-focus-${event.id}`}
                >
                  <EventCardView event={event} />
                </div>
              ) : (
                <EventCardView key={event.id} event={event} />
              ),
            )}
          </div>
          {pageLoading ? (
            <p
              className="mt-3 text-sm text-muted-foreground"
              aria-live="polite"
              data-testid="events-page-loading"
            >
              加载中…
            </p>
          ) : null}
          {pageError ? (
            <div className="mt-3 flex flex-wrap items-center gap-2" data-testid="events-pagination-error">
              <p className="text-sm text-destructive" role="alert">
                加载第 {pageError.page} 页失败：{pageError.message}
              </p>
              <Button
                variant="outline"
                size="sm"
                onClick={handleRetryPage}
                data-testid="events-pagination-retry"
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
            label="事件流分页"
            testIdPrefix="events-pagination"
          />
        </>
      )}
    </main>
  );
}

export default Events;
