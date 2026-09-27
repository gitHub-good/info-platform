import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { Search } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { Pagination } from '@/components/ui/pagination';
import { IndustryFilter } from '@/components/policy/IndustryFilter';
import { PolicyDetail } from '@/components/policy/PolicyDetail';
import { PolicyList } from '@/components/policy/PolicyList';
import { SourceFilter, type PolicySourceOption } from '@/components/policy/SourceFilter';
import { ApiError } from '@/api/http';
import { getInfoSources } from '@/api/infoSource';
import { trackReadingOnce } from '@/api/readingEvent';
import { DEFAULT_POLICY_DAYS, getPolicy, listPoliciesPaged } from '@/api/policy';
import { POLICY_INDUSTRY_OPTIONS, type PolicyDetailView, type PolicyPagedView, type PolicyView } from '@/types/policy';

/** 每页条数默认值（选项 10/20/50 由 Pagination 提供）。 */
const DEFAULT_PAGE_SIZE = 20;
/** 关键词最小长度（后端契约 ≥2 ≤64，前端先行拦截；PRD 非功能）。 */
const MIN_KEYWORD_LENGTH = 2;
/** 源下拉纳入的政策 scope 源类别（政策类 ∪ 宏观类——显式选源旁路 scope 口径，ADR-0062 随批 4）。 */
const SOURCE_FILTER_CATEGORIES = ['政策', '宏观'];

/** 非 ApiError 兜底文案。 */
function messageOf(err: unknown, fallback: string): string {
  return err instanceof ApiError ? err.msg : fallback;
}

/** 详情错误映射：30040 政策条目不存在 → 友好提示。 */
function detailErrorMessage(err: unknown): string {
  if (err instanceof ApiError && err.code === 30040) return '政策条目不存在或已下线';
  return messageOf(err, '详情加载失败');
}

/** 滚回列表顶部（翻页/条数/筛选成功后，§7.1-6）；环境不支持 scrollIntoView 时跳过，不干扰数据回填。 */
function scrollToListTop(el: HTMLElement | null): void {
  if (el && typeof el.scrollIntoView === 'function') {
    el.scrollIntoView({ block: 'start' });
  }
}

/** 筛选条件全集（V2.3 T205 加 sourceCode；对象收拢，loadFirst/fetchPage 单参传递）。 */
interface PolicyFilters {
  days: number;
  industry: string;
  sourceCode: string;
  keyword: string;
}

const DEFAULT_FILTERS: PolicyFilters = {
  days: DEFAULT_POLICY_DAYS,
  industry: '',
  sourceCode: '',
  keyword: '',
};

interface PolicyQuery extends PolicyFilters {
  page: number;
  size: number;
}

/**
 * 单次页码查询 + 空页防御回退（UI 设计 §5.3）：
 * 响应 items 空且 total>0 且 page>1 → 页界漂移，以 ceil(total/size) 静默重发一次。
 */
async function fetchPolicies(
  q: PolicyQuery,
  signal: AbortSignal,
): Promise<{ data: PolicyPagedView; page: number }> {
  const call = (page: number) =>
    listPoliciesPaged(
      {
        days: q.days,
        industry: q.industry,
        sourceCode: q.sourceCode,
        keyword: q.keyword,
        page,
        size: q.size,
      },
      signal,
    );
  let data = await call(q.page);
  if (data.policies.length === 0 && data.total > 0 && q.page > 1) {
    const last = Math.ceil(data.total / q.size);
    if (last >= 1 && last < q.page) {
      data = await call(last);
      return { data, page: last };
    }
  }
  return { data, page: q.page };
}

/** 时间窗分段选项（近 7 / 近 30 天，UI 设计 §3.1）。 */
const DAYS_OPTIONS = [
  { days: 7, label: '近 7 天' },
  { days: 30, label: '近 30 天' },
] as const;

interface DaysSwitcherProps {
  value: number;
  onChange: (days: number) => void;
  disabled?: boolean;
}

/** 时间窗分段按钮组（复用 LlmCostReport.WindowSwitcher 先例：default 高亮 / outline 未选）。 */
function DaysSwitcher({ value, onChange, disabled }: DaysSwitcherProps) {
  return (
    <div className="flex items-center gap-1" data-testid="policy-days-switcher">
      {DAYS_OPTIONS.map(({ days, label }) => (
        <Button
          key={days}
          variant={days === value ? 'default' : 'outline'}
          size="sm"
          disabled={disabled}
          onClick={() => onChange(days)}
          data-testid={`policy-days-${days}`}
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

/**
 * 关键词搜索（显式触发，UI 设计 D7）：按钮/Enter 提交；trim 后 1 字符禁用按钮
 * （原生隐式提交随默认按钮禁用一并拦截）；空输入提交 = 清除关键词回全量；
 * 输入值与提交值分离（输入过程零请求）。
 */
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
        data-testid="policy-keyword-input"
      />
      <Button
        type="submit"
        size="lg"
        disabled={disabled || tooShort}
        title={tooShort ? '至少输入 2 个字符' : undefined}
        data-testid="policy-keyword-search"
      >
        <Search aria-hidden="true" />
        搜索
      </Button>
    </form>
  );
}

interface PolicyEmptyProps {
  days: number;
  keyword: string;
  hasFilter: boolean;
  onClear: () => void;
}

/** 空态（UI 设计 §3.3）：有筛选区分性文案 + 清除筛选 CTA；无筛选维持 muted 引导。 */
function PolicyEmpty({ days, keyword, hasFilter, onClear }: PolicyEmptyProps) {
  if (!hasFilter) {
    return (
      <div
        className="py-10 text-center text-sm text-muted-foreground"
        data-testid="policy-list-empty"
      >
        最近 {days} 天暂无政策条目
      </div>
    );
  }
  return (
    <div
      className="flex flex-col items-center gap-2 py-10 text-center"
      data-testid="policy-list-empty"
    >
      <p className="text-sm text-foreground">
        {keyword ? `未找到包含「${keyword}」的政策` : '未找到匹配的政策条目'}
      </p>
      <p className="text-xs text-muted-foreground">可调整关键词、行业、源或时间窗后重试</p>
      <Button
        variant="outline"
        size="sm"
        onClick={onClear}
        data-testid="policy-clear-filters"
      >
        清除筛选
      </Button>
    </div>
  );
}

/**
 * 政策时事页（V2.3-M23 T205 数据面换代：policy-scope-v1 news 背书）。
 * - 列表：GET /policies?days&industry&keyword&sourceCode&page&size（页码分页，默认 20/页）。
 * - 筛选行：关键词搜索（显式触发）+ 时间窗分段（7/30 天）+ 行业下拉（静态 31+容器 L1 口径）
 *   + 源下拉（/info-sources 政策/宏观源，显式选源旁路 scope，失败静默降级仅「全部源」）。
 * - 联动（§5）：listLoading（骨架）与 pageLoading（在途禁用）分离；翻页/条数切换保留数据，
 *   失败保留当前页 + 错误行 + 重试（重发同目标页）；空页漂移静默回退末页；AbortController 单点防串台。
 * - 详情：点条目 → GET /policies/{id} → 回联标的 chips（跳标的详情）+ 关联 L2 政策发布事件
 *   （direction 徽章承接 ai_tendency + 事件流下钻）；倾向徽章随旧数据面退役。
 * 三态：加载骨架 / 空数据引导（区分筛选来源）/ 错误重试；401 由 http 层统一跳 /login。
 */
export function Policy() {
  const [items, setItems] = useState<PolicyView[]>([]);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  // pageSize 的稳定读取点：loadFirst 保持引用稳定（避免挂载 effect 重跑）
  const pageSizeRef = useRef(DEFAULT_PAGE_SIZE);
  const [total, setTotal] = useState(0);

  const [filters, setFilters] = useState<PolicyFilters>(DEFAULT_FILTERS);
  const [keywordInput, setKeywordInput] = useState('');
  // 源下拉选项：info-sources 政策/宏观类源（辅助筛选，失败静默降级）
  const [sources, setSources] = useState<PolicySourceOption[]>([]);

  const [listLoading, setListLoading] = useState(true);
  const [listError, setListError] = useState<string | null>(null);
  const [pageLoading, setPageLoading] = useState(false);
  // 翻页失败：目标页收进重试闭包，显示态不前跳（§5.2）
  const [pageError, setPageError] = useState<{ page: number; message: string } | null>(
    null,
  );

  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [detail, setDetail] = useState<PolicyDetailView | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);

  // 单一中止点：任何新请求发出前 abort 旧的，响应回填前检查 aborted（§5.4）
  const listAbort = useRef<AbortController | null>(null);
  const listSectionRef = useRef<HTMLElement | null>(null);

  /** 骨架通道（首屏/筛选/搜索/时间窗变更 → 新结果集查询，§5.1）。 */
  const loadFirst = useCallback(async (next: PolicyFilters) => {
    listAbort.current?.abort();
    const ctrl = new AbortController();
    listAbort.current = ctrl;
    setListLoading(true);
    setListError(null);
    setPageLoading(false);
    setPageError(null);
    setPage(1);
    try {
      const { data, page: landed } = await fetchPolicies(
        { ...next, page: 1, size: pageSizeRef.current },
        ctrl.signal,
      );
      if (ctrl.signal.aborted) return;
      setItems(data.policies);
      setTotal(data.total);
      setPage(landed);
      scrollToListTop(listSectionRef.current);
    } catch (err) {
      if (ctrl.signal.aborted) return;
      setListError(messageOf(err, '政策列表加载失败'));
    } finally {
      if (!ctrl.signal.aborted) setListLoading(false);
    }
  }, []);

  // 首次加载首页（挂载即首查，初始筛选 = 默认时间窗 + 全量）+ 源下拉选项（两请求独立）
  useEffect(() => {
    void loadFirst(DEFAULT_FILTERS);
    return () => listAbort.current?.abort();
  }, [loadFirst]);

  useEffect(() => {
    const ctrl = new AbortController();
    getInfoSources(ctrl.signal)
      .then((view) => {
        setSources(
          view.groups
            .flatMap((group) => group.sources)
            .filter((source) => SOURCE_FILTER_CATEGORIES.includes(source.category))
            .map((source) => ({ sourceCode: source.sourceCode, name: source.name })),
        );
      })
      .catch(() => {
        // 源下拉为辅助筛选：失败静默降级为仅「全部源」，列表主路径不受阻
      });
    return () => ctrl.abort();
  }, []);

  /** 在途保留通道（同筛选条件下的翻页/条数切换，§5.1）。 */
  const fetchPage = useCallback(
    async (target: number, size: number) => {
      listAbort.current?.abort();
      const ctrl = new AbortController();
      listAbort.current = ctrl;
      setPageLoading(true);
      setPageError(null);
      try {
        const { data, page: landed } = await fetchPolicies(
          { ...filters, page: target, size },
          ctrl.signal,
        );
        if (ctrl.signal.aborted) return;
        setItems(data.policies);
        setTotal(data.total);
        setPage(landed);
        scrollToListTop(listSectionRef.current);
      } catch (err) {
        if (ctrl.signal.aborted) return;
        setPageError({ page: target, message: messageOf(err, '加载失败') });
      } finally {
        if (!ctrl.signal.aborted) setPageLoading(false);
      }
    },
    [filters],
  );

  const handleDaysChange = (next: number) => {
    if (next === filters.days) return;
    setFilters((prev) => ({ ...prev, days: next }));
    void loadFirst({ ...filters, days: next });
  };

  const handleIndustryChange = (next: string) => {
    setFilters((prev) => ({ ...prev, industry: next }));
    // 现状行为保留：切换行业清空已选详情
    clearDetail();
    void loadFirst({ ...filters, industry: next });
  };

  const handleSourceChange = (next: string) => {
    setFilters((prev) => ({ ...prev, sourceCode: next }));
    clearDetail();
    void loadFirst({ ...filters, sourceCode: next });
  };

  const handleSearchSubmit = (next: string) => {
    setFilters((prev) => ({ ...prev, keyword: next }));
    void loadFirst({ ...filters, keyword: next });
  };

  const handlePageChange = (target: number) => {
    if (target === page) return;
    void fetchPage(target, pageSizeRef.current);
  };

  // 条数切换：重置 page=1 再查（PRD 场景 1.3）；同结果集重排 → 保留数据通道（§5.1 注）
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
    setFilters(DEFAULT_FILTERS);
    clearDetail();
    void loadFirst(DEFAULT_FILTERS);
  };

  /** 筛选变更/清除时同步清空已选详情（旧状态详情与新列表口径不符）。 */
  function clearDetail() {
    setSelectedId(null);
    setDetail(null);
    setDetailError(null);
  }

  const handleSelect = useCallback(async (id: number) => {
    setSelectedId(id);
    setDetailLoading(true);
    setDetailError(null);
    setDetail(null);
    try {
      const view = await getPolicy(id);
      setDetail(view);
      // 阅读埋点（T29）：详情加载成功后上报一次（会话级去重、静默失败）
      trackReadingOnce(`policy:${id}`, {
        contentType: 'POLICY',
        contentRef: String(id),
      });
    } catch (err) {
      setDetailError(detailErrorMessage(err));
    } finally {
      setDetailLoading(false);
    }
  }, []);

  const handleRetryList = () => void loadFirst(filters);
  const handleRetryDetail = () => {
    if (selectedId != null) void handleSelect(selectedId);
  };

  const hasFilter =
    filters.industry !== '' ||
    filters.sourceCode !== '' ||
    filters.keyword !== '' ||
    filters.days !== DEFAULT_POLICY_DAYS;

  return (
    <main className="mx-auto w-full max-w-6xl p-4 sm:p-6" data-testid="policy-page">
      <header className="mb-4">
        <h1 className="text-xl font-medium">政策时事</h1>
      </header>

      <div className="mb-4 flex flex-wrap items-center gap-3">
        <KeywordSearch
          value={keywordInput}
          onChange={setKeywordInput}
          onSubmit={handleSearchSubmit}
          disabled={listLoading}
        />
        <DaysSwitcher value={filters.days} onChange={handleDaysChange} disabled={listLoading} />
        <IndustryFilter
          industries={POLICY_INDUSTRY_OPTIONS}
          value={filters.industry}
          onChange={handleIndustryChange}
          disabled={listLoading}
        />
        <SourceFilter
          sources={sources}
          value={filters.sourceCode}
          onChange={handleSourceChange}
          disabled={listLoading}
        />
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <section
          ref={listSectionRef}
          data-testid="policy-list-section"
          aria-busy={pageLoading || undefined}
        >
          {listLoading ? (
            <div className="flex flex-col gap-2" data-testid="policy-list-loading">
              {Array.from({ length: 4 }, (_, i) => (
                <Skeleton key={i} className="h-24 w-full" />
              ))}
            </div>
          ) : listError ? (
            <div
              className="flex flex-col items-start gap-2"
              data-testid="policy-list-error"
            >
              <p className="text-sm text-destructive" role="alert">
                {listError}
              </p>
              <Button
                variant="outline"
                size="sm"
                onClick={handleRetryList}
                data-testid="policy-list-retry"
              >
                重试
              </Button>
            </div>
          ) : items.length === 0 ? (
            <PolicyEmpty
              days={filters.days}
              keyword={filters.keyword}
              hasFilter={hasFilter}
              onClear={handleClearFilters}
            />
          ) : (
            <>
              <PolicyList
                items={items}
                selectedId={selectedId}
                onSelect={handleSelect}
              />
              {pageLoading ? (
                <p
                  className="mt-2 text-sm text-muted-foreground"
                  aria-live="polite"
                  data-testid="policy-page-loading"
                >
                  加载中…
                </p>
              ) : null}
              {pageError ? (
                <div
                  className="mt-2 flex flex-wrap items-center gap-2"
                  data-testid="policy-pagination-error"
                >
                  <p className="text-sm text-destructive" role="alert">
                    加载第 {pageError.page} 页失败：{pageError.message}
                  </p>
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={handleRetryPage}
                    data-testid="policy-pagination-retry"
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
                label="政策列表分页"
              />
            </>
          )}
        </section>

        <section data-testid="policy-detail-section" className="self-start">
          <PolicyDetail
            detail={detail}
            loading={detailLoading}
            error={detailError}
            onRetry={handleRetryDetail}
          />
        </section>
      </div>
    </main>
  );
}

export default Policy;
