import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { ExternalLink, Search } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { Pagination } from '@/components/ui/pagination';
import { ApiError } from '@/api/http';
import { getInfoSources } from '@/api/infoSource';
import {
  DEFAULT_NEWS_LIBRARY_L0,
  DEFAULT_NEWS_LIBRARY_PAGE_SIZE,
  listNewsLibraryPaged,
  type NewsLibraryQuery,
} from '@/api/newsItem';
import { formatDateTime } from '@/lib/format';
import { L1_MAIN_CATEGORIES, type NewsL0Filter, type NewsL0Result, type NewsLibraryItem } from '@/types/newsItem';
import type { InfoSourceCardView } from '@/types/infoSource';

/** 关键词最小长度（后端契约 ≥2 ≤64，前端先行拦截；M9 同口径）。 */
const MIN_KEYWORD_LENGTH = 2;

/** L0 状态徽章（REQ 拍板一：PASS 通过·绿 / NOISE 噪音·灰 / NEAR_DUP 近重复·琥珀——沿用既有徽章语义体系）。 */
const L0_BADGES: Record<NewsL0Result, { label: string; className: string }> = {
  PASS: { label: '通过', className: 'bg-emerald-500/15 text-emerald-400' },
  NOISE: { label: '噪音', className: 'bg-muted text-muted-foreground' },
  NEAR_DUP: { label: '近重复', className: 'bg-amber-500/15 text-amber-400' },
};

/** L0 状态筛选段（默认「有效 PASS」；其余：全部 / 近重复 / 噪音——REQ 拍板一）。 */
const L0_OPTIONS: Array<{ value: NewsL0Filter; label: string; testId: string }> = [
  { value: 'PASS', label: '有效', testId: 'news-library-l0-pass' },
  { value: 'ALL', label: '全部', testId: 'news-library-l0-all' },
  { value: 'NEAR_DUP', label: '近重复', testId: 'news-library-l0-near-dup' },
  { value: 'NOISE', label: '噪音', testId: 'news-library-l0-noise' },
];

/** 非 ApiError 兜底文案。 */
function messageOf(err: unknown, fallback: string): string {
  return err instanceof ApiError ? err.msg : fallback;
}

/** 滚回列表顶部（翻页/条数/筛选成功后，M9 §7.1-6）；环境不支持 scrollIntoView 时跳过。 */
function scrollToListTop(el: HTMLElement | null): void {
  if (el && typeof el.scrollIntoView === 'function') {
    el.scrollIntoView({ block: 'start' });
  }
}

interface L0SwitcherProps {
  value: NewsL0Filter;
  onChange: (next: NewsL0Filter) => void;
  disabled?: boolean;
}

/** L0 状态单选段（复用 Policy DaysSwitcher 先例：default 高亮 / outline 未选）。 */
function L0Switcher({ value, onChange, disabled }: L0SwitcherProps) {
  return (
    <div className="flex items-center gap-1" data-testid="news-library-l0-switcher">
      {L0_OPTIONS.map(({ value: optionValue, label, testId }) => (
        <Button
          key={optionValue}
          variant={optionValue === value ? 'default' : 'outline'}
          size="sm"
          disabled={disabled}
          onClick={() => onChange(optionValue)}
          data-testid={testId}
        >
          {label}
        </Button>
      ))}
    </div>
  );
}

interface KeywordSearchProps {
  value: string;
  onChange: (value: string) => void;
  onSubmit: (keyword: string) => void;
  disabled?: boolean;
}

/** 关键词搜索（显式触发，M9 D7 先例）：按钮/Enter 提交；输入过程零请求；空提交 = 清除回全量。 */
function KeywordSearch({ value, onChange, onSubmit, disabled }: KeywordSearchProps) {
  const trimmed = value.trim();
  const tooShort = trimmed.length > 0 && trimmed.length < MIN_KEYWORD_LENGTH;
  return (
    <form
      className="flex w-full items-center gap-2 sm:w-auto"
      onSubmit={(e: FormEvent<HTMLFormElement>) => {
        e.preventDefault();
        onSubmit(trimmed);
      }}
    >
      <Input
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder="搜索标题 / 摘要关键词（留空显示全部）"
        aria-label="搜索标题 / 摘要关键词"
        disabled={disabled}
        className="w-full sm:w-64"
        data-testid="news-library-keyword-input"
      />
      <Button
        type="submit"
        size="lg"
        disabled={disabled || tooShort}
        title={tooShort ? '至少输入 2 个字符' : undefined}
        data-testid="news-library-keyword-search"
      >
        <Search aria-hidden="true" />
        搜索
      </Button>
    </form>
  );
}

interface NewsLibraryRowProps {
  item: NewsLibraryItem;
}

/** 资讯库列表行：标题外链 / 源徽章 / 双时间 / L0·L1 徽章 / 摘要（REQ 拍板一列表字段）。 */
function NewsLibraryRow({ item }: NewsLibraryRowProps) {
  const l0 = L0_BADGES[item.l0Result];
  return (
    <article
      className="rounded-xl border border-border bg-card p-4 shadow-sm"
      data-testid={`news-library-item-${item.id}`}
    >
      {item.url ? (
        <a
          href={item.url}
          target="_blank"
          rel="noreferrer"
          className="inline-flex items-start gap-1 font-medium underline-offset-4 hover:underline"
          data-testid={`news-library-title-${item.id}`}
        >
          {item.title}
          <ExternalLink className="mt-0.5 size-3 shrink-0 text-muted-foreground" aria-hidden="true" />
        </a>
      ) : (
        <span className="font-medium" data-testid={`news-library-title-${item.id}`}>
          {item.title}
        </span>
      )}
      <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1.5 text-xs text-muted-foreground">
        <Badge variant="outline" data-testid={`news-library-source-${item.id}`}>
          {item.sourceName ?? `源 ${item.sourceId}`}
        </Badge>
        <span data-testid={`news-library-published-${item.id}`}>
          发布 {formatDateTime(item.publishedAt)}
        </span>
        <span data-testid={`news-library-fetched-${item.id}`}>
          抓取 {formatDateTime(item.fetchedAt)}
        </span>
        {/* L0 徽章：l0_detail（NOISE 规则名 / NEAR_DUP 距离）hover 可见——抽检观测面 */}
        <Badge
          className={l0.className}
          title={item.l0Detail ?? undefined}
          data-testid={`news-library-l0-${item.id}`}
        >
          {l0.label}
        </Badge>
        {item.l1Main ? (
          <Badge variant="secondary" data-testid={`news-library-l1-${item.id}`}>
            {item.l1Main}
          </Badge>
        ) : (
          <Badge
            className="bg-muted text-muted-foreground"
            data-testid={`news-library-l1-${item.id}`}
          >
            未分类
          </Badge>
        )}
        {item.lowConfidence ? (
          <Badge
            className="bg-amber-500/15 text-amber-400"
            title={item.l1Confidence != null ? `置信度 ${item.l1Confidence}` : undefined}
            data-testid={`news-library-lowconf-${item.id}`}
          >
            低置信
          </Badge>
        ) : null}
        {item.nearDupMasterUrl ? (
          <a
            href={item.nearDupMasterUrl}
            target="_blank"
            rel="noreferrer"
            className="inline-flex items-center gap-0.5 text-primary underline-offset-4 hover:underline"
            data-testid={`news-library-master-${item.id}`}
          >
            主条
            <ExternalLink className="size-3" aria-hidden="true" />
          </a>
        ) : null}
      </div>
      {item.summary ? (
        <p
          className="mt-2 line-clamp-2 text-sm text-muted-foreground"
          title={item.summary}
          data-testid={`news-library-summary-${item.id}`}
        >
          {item.summary}
        </p>
      ) : null}
    </article>
  );
}

interface NewsLibraryEmptyProps {
  hasFilter: boolean;
  onClear: () => void;
}

/** 空态（REQ 拍板一）：无附加筛选 →「库为空」；有筛选 →「筛选条件过窄」+ 清除筛选 CTA。 */
function NewsLibraryEmpty({ hasFilter, onClear }: NewsLibraryEmptyProps) {
  if (!hasFilter) {
    return (
      <div
        className="py-10 text-center text-sm text-muted-foreground"
        data-testid="news-library-empty"
      >
        资讯库暂无条目
      </div>
    );
  }
  return (
    <div
      className="flex flex-col items-center gap-2 py-10 text-center"
      data-testid="news-library-empty"
    >
      <p className="text-sm text-foreground">未找到匹配条目</p>
      <p className="text-xs text-muted-foreground">可调整源、状态、分类或关键词后重试</p>
      <Button variant="outline" size="sm" onClick={onClear} data-testid="news-library-clear-filters">
        清除筛选
      </Button>
    </div>
  );
}

/** 组装查询参数（'' 选择统一归一为 null 不过滤）。 */
function buildQuery(
  sourceSel: string,
  l0Sel: NewsL0Filter,
  l1Sel: string,
  keyword: string,
  page: number,
  size: number,
  publishedFrom = '',
  publishedTo = '',
): NewsLibraryQuery {
  return {
    sourceId: sourceSel ? Number(sourceSel) : null,
    q: keyword || null,
    l0: l0Sel,
    l1: l1Sel || null,
    publishedFrom: publishedFrom || null,
    publishedTo: publishedTo || null,
    page,
    size,
  };
}

/**
 * 资讯库页（M19 T161 第 19 页，REQ-20260926-16 拍板一）：news_item 原始库全量列表。
 * - 列表：GET /news-items?page&size&l0（页码分页，默认 20/页、仅 PASS——管道消费口径）。
 * - 筛选行：关键词搜索（显式触发）+ L0 状态段（默认有效）+ L1 分类下拉（35 枚举硬编码）+
 *   源下拉（info-sources 活跃源复用；接口失败降级仅「全部源」不阻塞列表）。
 * - 联动（M9 §5 语义）：筛选变更 → page=1 骨架重查；翻页/条数切换保留数据；空页漂移静默回退末页；
 *   AbortController 单点防串台。
 * 三态：加载骨架 / 空态（区分库为空与筛选过窄）/ 错误重试；401 由 http 层统一跳 /login。
 */
export function NewsLibrary() {
  const [items, setItems] = useState<NewsLibraryItem[]>([]);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_NEWS_LIBRARY_PAGE_SIZE);
  // pageSize 的稳定读取点：loadFirst 保持引用稳定（避免挂载 effect 重跑）
  const pageSizeRef = useRef(DEFAULT_NEWS_LIBRARY_PAGE_SIZE);
  const [total, setTotal] = useState(0);

  const [sourceSel, setSourceSel] = useState('');
  const [l0, setL0] = useState<NewsL0Filter>(DEFAULT_NEWS_LIBRARY_L0);
  const [l1, setL1] = useState('');
  const [publishedFrom, setPublishedFrom] = useState('');
  const [publishedTo, setPublishedTo] = useState('');
  const [keywordInput, setKeywordInput] = useState('');
  const [keyword, setKeyword] = useState('');
  // 源下拉选项：info-sources 活跃源清单（辅助筛选，失败静默降级）
  const [sources, setSources] = useState<InfoSourceCardView[]>([]);

  const [listLoading, setListLoading] = useState(true);
  const [listError, setListError] = useState<string | null>(null);
  const [pageLoading, setPageLoading] = useState(false);
  const [pageError, setPageError] = useState<{ page: number; message: string } | null>(null);

  // 单一中止点：任何新请求发出前 abort 旧的，响应回填前检查 aborted（M9 §5.4）
  const listAbort = useRef<AbortController | null>(null);
  const listSectionRef = useRef<HTMLElement | null>(null);

  /** 空页防御回退（M9 §5.3）：响应 items 空且 total>0 且 page>1 → 页界漂移，静默重发末页。 */
  const fetchView = useCallback(
    async (query: NewsLibraryQuery, signal: AbortSignal): Promise<{ landed: number; total: number; items: NewsLibraryItem[] }> => {
      const call = (target: number) =>
        listNewsLibraryPaged({ ...query, page: target }, signal);
      let view = await call(query.page);
      if (view.items.length === 0 && view.total > 0 && query.page > 1) {
        const last = Math.ceil(view.total / query.size);
        if (last >= 1 && last < query.page) {
          view = await call(last);
          return { landed: last, total: view.total, items: view.items };
        }
      }
      return { landed: query.page, total: view.total, items: view.items };
    },
    [],
  );

  /** 骨架通道（首屏/筛选/搜索变更 → 新结果集查询，M9 §5.1）。 */
  const loadFirst = useCallback(
    async (
      nextSource: string,
      nextL0: NewsL0Filter,
      nextL1: string,
      nextKeyword: string,
      nextFrom = "",
      nextTo = "",
    ) => {
      listAbort.current?.abort();
      const ctrl = new AbortController();
      listAbort.current = ctrl;
      setListLoading(true);
      setListError(null);
      setPageLoading(false);
      setPageError(null);
      setPage(1);
      try {
        const view = await fetchView(
          buildQuery(nextSource, nextL0, nextL1, nextKeyword, 1, pageSizeRef.current, nextFrom, nextTo),
          ctrl.signal,
        );
        if (ctrl.signal.aborted) return;
        setItems(view.items);
        setTotal(view.total);
        setPage(view.landed);
        scrollToListTop(listSectionRef.current);
      } catch (err) {
        if (ctrl.signal.aborted) return;
        setListError(messageOf(err, '资讯库加载失败'));
      } finally {
        if (!ctrl.signal.aborted) setListLoading(false);
      }
    },
    [fetchView],
  );

  // 首次加载首页 + 源下拉选项（两请求独立：源清单失败不阻塞列表）
  useEffect(() => {
    void loadFirst('', DEFAULT_NEWS_LIBRARY_L0, '', '');
    return () => listAbort.current?.abort();
  }, [loadFirst]);

  useEffect(() => {
    const ctrl = new AbortController();
    getInfoSources(ctrl.signal)
      .then((view) => {
        setSources(view.groups.flatMap((group) => group.sources));
      })
      .catch(() => {
        // 源下拉为辅助筛选：失败静默降级为仅「全部源」，列表主路径不受阻
      });
    return () => ctrl.abort();
  }, []);

  /** 在途保留通道（同筛选条件下的翻页/条数切换，M9 §5.1）。 */
  const fetchPage = useCallback(
    async (target: number, size: number) => {
      listAbort.current?.abort();
      const ctrl = new AbortController();
      listAbort.current = ctrl;
      setPageLoading(true);
      setPageError(null);
      try {
        const view = await fetchView(
          buildQuery(sourceSel, l0, l1, keyword, target, size, publishedFrom, publishedTo),
          ctrl.signal,
        );
        if (ctrl.signal.aborted) return;
        setItems(view.items);
        setTotal(view.total);
        setPage(view.landed);
        scrollToListTop(listSectionRef.current);
      } catch (err) {
        if (ctrl.signal.aborted) return;
        setPageError({ page: target, message: messageOf(err, '加载失败') });
      } finally {
        if (!ctrl.signal.aborted) setPageLoading(false);
      }
    },
    [sourceSel, l0, l1, keyword, fetchView],
  );

  const handleSourceChange = (next: string) => {
    if (next === sourceSel) return;
    setSourceSel(next);
    void loadFirst(next, l0, l1, keyword, publishedFrom, publishedTo);
  };

  const handleL0Change = (next: NewsL0Filter) => {
    if (next === l0) return;
    setL0(next);
    void loadFirst(sourceSel, next, l1, keyword);
  };

  const handleL1Change = (next: string) => {
    if (next === l1) return;
    setL1(next);
    void loadFirst(sourceSel, l0, next, keyword);
  };

  const handleSearchSubmit = (next: string) => {
    setKeyword(next);
    void loadFirst(sourceSel, l0, l1, next, publishedFrom, publishedTo);
  };

  const handleDateChange = (from: string, to: string) => {
    setPublishedFrom(from);
    setPublishedTo(to);
    void loadFirst(sourceSel, l0, l1, keyword, from, to);
  };

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

  const handleClearFilters = () => {
    setKeywordInput('');
    setKeyword('');
    setL1('');
    setSourceSel('');
    setL0(DEFAULT_NEWS_LIBRARY_L0);
    void loadFirst('', DEFAULT_NEWS_LIBRARY_L0, '', '');
  };

  const hasFilter =
    sourceSel !== '' || l1 !== '' || keyword !== '' || l0 !== DEFAULT_NEWS_LIBRARY_L0;

  return (
    <main className="mx-auto w-full max-w-4xl p-4 sm:p-6" data-testid="news-library-page">
      <header className="mb-4">
        <h1 className="text-xl font-medium">资讯库</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          资讯源原始条目全量 · 默认仅有效（PASS）条目 · 噪音/近重复可筛
        </p>
      </header>

      <div className="mb-4 flex flex-wrap items-center gap-3">
        <label className="flex items-center gap-1 text-sm text-muted-foreground">
          发布
          <input
            type="date"
            value={publishedFrom}
            onChange={(e) => handleDateChange(e.target.value, publishedTo)}
            disabled={listLoading}
            data-testid="news-library-from-date"
            className="h-8 rounded-lg border border-input bg-input/30 px-2 text-sm text-foreground shadow-sm transition-colors focus-visible:border-ring focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50"
          />
          <span>~</span>
          <input
            type="date"
            value={publishedTo}
            onChange={(e) => handleDateChange(publishedFrom, e.target.value)}
            disabled={listLoading}
            data-testid="news-library-to-date"
            className="h-8 rounded-lg border border-input bg-input/30 px-2 text-sm text-foreground shadow-sm transition-colors focus-visible:border-ring focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50"
          />
        </label>
        <KeywordSearch
          value={keywordInput}
          onChange={setKeywordInput}
          onSubmit={handleSearchSubmit}
          disabled={listLoading}
        />
        <L0Switcher value={l0} onChange={handleL0Change} disabled={listLoading} />
        <label className="flex items-center gap-2 text-sm text-muted-foreground">
          分类
          <select
            value={l1}
            onChange={(e) => handleL1Change(e.target.value)}
            disabled={listLoading}
            data-testid="news-library-l1-filter"
            className="h-8 max-w-36 rounded-lg border border-input bg-input/30 px-2 text-sm text-foreground shadow-sm transition-colors focus-visible:border-ring focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50"
          >
            <option value="">全部分类</option>
            {L1_MAIN_CATEGORIES.map((name) => (
              <option key={name} value={name}>
                {name}
              </option>
            ))}
          </select>
        </label>
        <label className="flex items-center gap-2 text-sm text-muted-foreground">
          源
          <select
            value={sourceSel}
            onChange={(e) => handleSourceChange(e.target.value)}
            disabled={listLoading}
            data-testid="news-library-source-filter"
            className="h-8 max-w-44 rounded-lg border border-input bg-input/30 px-2 text-sm text-foreground shadow-sm transition-colors focus-visible:border-ring focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50"
          >
            <option value="">全部源</option>
            {sources.map((source) => (
              <option key={source.id} value={String(source.id)}>
                {source.name}
              </option>
            ))}
          </select>
        </label>
      </div>

      <section
        ref={listSectionRef}
        data-testid="news-library-list-section"
        aria-busy={pageLoading || undefined}
        className="flex flex-col gap-3"
      >
        {listLoading ? (
          <div className="flex flex-col gap-3" data-testid="news-library-loading">
            {Array.from({ length: 4 }, (_, i) => (
              <Skeleton key={i} className="h-28 w-full" />
            ))}
          </div>
        ) : listError ? (
          <div className="flex flex-col items-start gap-2" data-testid="news-library-error">
            <p className="text-sm text-destructive" role="alert">
              {listError}
            </p>
            <Button
              variant="outline"
              size="sm"
              onClick={() => void loadFirst(sourceSel, l0, l1, keyword)}
              data-testid="news-library-retry"
            >
              重试
            </Button>
          </div>
        ) : items.length === 0 ? (
          <NewsLibraryEmpty hasFilter={hasFilter} onClear={handleClearFilters} />
        ) : (
          <>
            <div className="flex flex-col gap-3" data-testid="news-library-list">
              {items.map((item) => (
                <NewsLibraryRow key={item.id} item={item} />
              ))}
            </div>
            {pageLoading ? (
              <p
                className="text-sm text-muted-foreground"
                aria-live="polite"
                data-testid="news-library-page-loading"
              >
                加载中…
              </p>
            ) : null}
            {pageError ? (
              <div
                className="flex flex-wrap items-center gap-2"
                data-testid="news-library-pagination-error"
              >
                <p className="text-sm text-destructive" role="alert">
                  加载第 {pageError.page} 页失败：{pageError.message}
                </p>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={handleRetryPage}
                  data-testid="news-library-pagination-retry"
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
              label="资讯库分页"
              testIdPrefix="news-library-pagination"
            />
          </>
        )}
      </section>
    </main>
  );
}

export default NewsLibrary;
