import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { ExternalLink, Search } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { PageHeader } from '@/components/ui/PageHeader';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { EmptyState } from '@/components/ui/EmptyState';
import { SubjectPicker } from '@/components/subject/SubjectPicker';
import { statusToneClass } from '@/lib/format';
import { Skeleton } from '@/components/ui/skeleton';
import { Pagination } from '@/components/ui/pagination';
import { ApiError } from '@/api/http';
import { getInfoSources } from '@/api/infoSource';
import type { SubjectSummary } from '@/api/subject';
import { currentRoute, queryOf } from '@/lib/navigation';
import {
  DEFAULT_NEWS_LIBRARY_L0,
  DEFAULT_NEWS_LIBRARY_PAGE_SIZE,
  listNewsLibraryPaged,
  type NewsLibraryQuery,
} from '@/api/newsItem';
import { formatDateTime } from '@/lib/format';
import {
  L1_MAIN_CATEGORIES,
  type MatchedSubject,
  type NewsL0Filter,
  type NewsL0Result,
  type NewsLibraryItem,
} from '@/types/newsItem';
import type { InfoSourceCardView } from '@/types/infoSource';

/** 关键词最小长度（后端契约 ≥2 ≤64，前端先行拦截；M9 同口径）。 */
const MIN_KEYWORD_LENGTH = 2;

/** L0 状态徽章（REQ 拍板一：PASS 通过·绿 / NOISE 噪音·灰 / NEAR_DUP 近重复·琥珀——沿用既有徽章语义体系）。 */
const L0_BADGES: Record<NewsL0Result, { label: string; className: string }> = {
  PASS: { label: '通过', className: statusToneClass('success') },
  NOISE: { label: '噪音', className: statusToneClass('neutral') },
  NEAR_DUP: { label: '近重复', className: statusToneClass('warning') },
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
  /** 标的 chip 点击（V3.1 双向联动）：以该标的为筛选条件重查。 */
  onSubjectPick: (subject: MatchedSubject) => void;
}

/** hover 详情卡（V3.1）：纯 CSS group-hover 浮层（零新依赖）——已有字段全量呈现，不截断任何信息。 */
function NewsLibraryHoverCard({ item }: { item: NewsLibraryItem }) {
  const l0 = L0_BADGES[item.l0Result];
  return (
    <aside
      className="absolute inset-x-0 top-full z-20 hidden rounded-xl border border-border bg-popover p-4 text-sm shadow-lg group-hover:block"
      data-testid={`news-library-hover-${item.id}`}
      aria-label="条目详情"
    >
      <p className="font-medium break-words" data-testid={`news-library-hover-title-${item.id}`}>
        {item.title}
      </p>
      <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
        <span title={item.sourceCode ?? undefined}>
          源 {item.sourceName ?? `源 ${item.sourceId}`}
        </span>
        {item.author ? <span>作者 {item.author}</span> : null}
        <span>发布 {formatDateTime(item.publishedAt)}</span>
        <span>抓取 {formatDateTime(item.fetchedAt)}</span>
        <Badge className={l0.className} title={item.l0Detail ?? undefined}>
          {l0.label}
        </Badge>
        <Badge
          variant={item.l1Main ? 'secondary' : 'outline'}
          title={item.l1Confidence != null ? `置信度 ${item.l1Confidence}` : undefined}
        >
          {item.l1Main ?? '未分类'}
        </Badge>
        {item.lowConfidence ? (
          <Badge className="bg-amber-500/15 text-amber-400">低置信</Badge>
        ) : null}
      </div>
      {item.matchedSubjects.length > 0 ? (
        <div className="mt-2 flex flex-wrap items-center gap-1.5">
          <span className="text-xs text-muted-foreground">关联标的</span>
          {item.matchedSubjects.map((subject) => (
            <span
              key={subject.code}
              className="inline-flex items-center gap-1 rounded-full border border-border bg-muted/50 px-2 py-0.5 text-xs"
              data-testid={`news-library-hover-subject-${item.id}-${subject.code}`}
            >
              <span className="font-medium">{subject.code}</span>
              <span>{subject.name}</span>
              {subject.industry ? (
                <span className="text-muted-foreground">· {subject.industry}</span>
              ) : null}
              <a
                href={`#/subjects/${subject.code}`}
                className="text-primary underline-offset-4 hover:underline"
                data-testid={`news-library-hover-subject-link-${item.id}-${subject.code}`}
              >
                详情
              </a>
            </span>
          ))}
        </div>
      ) : null}
      {item.summary ? (
        <p
          className="mt-2 whitespace-pre-wrap break-words text-muted-foreground"
          data-testid={`news-library-hover-summary-${item.id}`}
        >
          {item.summary}
        </p>
      ) : null}
      <div className="mt-2 flex flex-wrap items-center gap-3 text-xs">
        {item.url ? (
          <a
            href={item.url}
            target="_blank"
            rel="noreferrer"
            className="inline-flex items-center gap-0.5 text-primary underline-offset-4 hover:underline"
            data-testid={`news-library-hover-origin-${item.id}`}
          >
            原文
            <ExternalLink className="size-3" aria-hidden="true" />
          </a>
        ) : null}
        {item.nearDupMasterUrl ? (
          <a
            href={item.nearDupMasterUrl}
            target="_blank"
            rel="noreferrer"
            className="inline-flex items-center gap-0.5 text-primary underline-offset-4 hover:underline"
            data-testid={`news-library-hover-master-${item.id}`}
          >
            主条
            <ExternalLink className="size-3" aria-hidden="true" />
          </a>
        ) : null}
      </div>
    </aside>
  );
}

/** 资讯库列表行：标题外链 / 源徽章 / 双时间 / L0·L1 徽章 / 标的 chips / 摘要（行内截断，悬浮卡全量）。 */
function NewsLibraryRow({ item, onSubjectPick }: NewsLibraryRowProps) {
  const l0 = L0_BADGES[item.l0Result];
  return (
    <article
      className="group relative rounded-xl border border-border bg-card p-4 shadow-sm"
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
        {/* 关联标的 chips（V3.1，L1 徽章旁）：名称可点 → 以该标的筛选（双向联动）；详情入口在悬浮卡 */}
        {item.matchedSubjects.map((subject) => (
          <button
            key={subject.code}
            type="button"
            title={subject.code}
            onClick={() => onSubjectPick(subject)}
            className="inline-flex items-center rounded-full border border-primary/30 bg-primary/10 px-2 py-0.5 font-medium text-primary transition-colors hover:bg-primary/20"
            data-testid={`news-library-subject-chip-${item.id}-${subject.code}`}
          >
            {subject.name}
          </button>
        ))}
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
      <NewsLibraryHoverCard item={item} />
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
    return <EmptyState title="资讯库暂无条目" testId="news-library-empty" />;
  }
  return (
    <EmptyState
      title="未找到匹配条目"
      description="可调整源、状态、分类或关键词后重试"
      action={
        <Button variant="outline" size="sm" onClick={onClear} data-testid="news-library-clear-filters">
          清除筛选
        </Button>
      }
      testId="news-library-empty"
    />
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
  subjectCode: string | null = null,
): NewsLibraryQuery {
  return {
    sourceId: sourceSel ? Number(sourceSel) : null,
    q: keyword || null,
    l0: l0Sel,
    l1: l1Sel || null,
    publishedFrom: publishedFrom || null,
    publishedTo: publishedTo || null,
    subjectCode,
    page,
    size,
  };
}

/** 标的 chip → SubjectPicker 选中态适配（V3.1 双向联动）：chip 仅携带 code/name/industry， 合成中性占位（id/market/type 本页不消费——仅 picker 展示 code/name/industry）。 */
function subjectOfChip(subject: MatchedSubject): SubjectSummary {
  return {
    id: 0,
    subjectCode: subject.code,
    name: subject.name,
    market: '',
    type: 0,
    industry: subject.industry,
  };
}

/** L0 筛选合法值集（URL 预填校验：非法值忽略回默认态）。 */
const L0_FILTER_VALUES: NewsL0Filter[] = ['PASS', 'ALL', 'NEAR_DUP', 'NOISE'];

/** URL 预填初始筛选（V2.4 T213：挂载消费一次 l1/sourceId/l0——政策页重定向与大盘深查入口的落点）。 */
interface UrlPrefill {
  l1: string;
  sourceId: string;
  l0: NewsL0Filter;
}

/**
 * 解析路由查询参数为初始筛选态：l1 须在 35 枚举内、sourceId 须为数字、l0 须为合法枚举——
 * 非法参数忽略回默认态（REQ 故事 2 场景 3：预填仅初始化一次不锁态，用户随后改筛自由）。
 */
function prefillOf(route: string): UrlPrefill {
  const query = queryOf(route);
  const l1 = query.get('l1') ?? '';
  const sourceId = query.get('sourceId') ?? '';
  const l0 = query.get('l0') ?? '';
  return {
    l1: L1_MAIN_CATEGORIES.includes(l1) ? l1 : '',
    sourceId: /^\d+$/.test(sourceId) ? sourceId : '',
    l0: L0_FILTER_VALUES.includes(l0 as NewsL0Filter) ? (l0 as NewsL0Filter) : DEFAULT_NEWS_LIBRARY_L0,
  };
}

/** 标的选中态 → 查询参数（V3.1：null = 不过滤）。 */
function subjectCodeOf(sel: SubjectSummary | null): string | null {
  return sel?.subjectCode ?? null;
}

/** 骨架重查条件（V3.1 收拢为对象——七维筛选位置传参易错；from/to 缺省 '' 沿原默认参数语义）。 */
interface LibraryReload {
  source: string;
  l0: NewsL0Filter;
  l1: string;
  keyword: string;
  subjectCode?: string | null;
  publishedFrom?: string;
  publishedTo?: string;
}

/**
 * 资讯库页（M19 T161 第 19 页，REQ-20260926-16 拍板一）：news_item 原始库全量列表。
 * - 列表：GET /news-items?page&size&l0（页码分页，默认 20/页、仅 PASS——管道消费口径）。
 * - 筛选行：关键词搜索（显式触发）+ L0 状态段（默认有效）+ L1 分类下拉（35 枚举硬编码）+
 *   源下拉（info-sources 活跃源复用；接口失败降级仅「全部源」不阻塞列表）+
 *   标的搜索（V3.1：SubjectPicker 联想选中 → subjectCode 过滤，chips 点击双向联动）。
 * - 行悬浮详情卡（V3.1 纯 CSS group-hover）：已有字段全量呈现不截断（完整标题/摘要/源/作者/
 *   双时间/L0·L1/关联标的/主条/原文），标的详情入口 #/subjects/:code。
 * - 联动（M9 §5 语义）：筛选变更 → page=1 骨架重查；翻页/条数切换保留数据；空页漂移静默回退末页；
 *   AbortController 单点防串台。
 * 三态：加载骨架 / 空态（区分库为空与筛选过窄）/ 错误重试；401 由 http 层统一跳 /login。
 */
export function NewsLibrary({ route: routeProp }: { route?: string }) {
  // URL 预填（V2.4 T213）：挂载时消费一次路由查询参数（l1/sourceId/l0）为初始筛选态
  // （#/policies 重定向、大盘弹框深查等入口的落点；预填不锁态，用户随后改筛自由）。
  // route prop 优先（App 归一层后的 effectiveRoute——redirect 首帧即含目标参数）；
  // 直挂场景（单测/嵌入）回落 window hash。
  const [prefill] = useState(() => prefillOf(routeProp ?? currentRoute()));
  const [items, setItems] = useState<NewsLibraryItem[]>([]);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_NEWS_LIBRARY_PAGE_SIZE);
  // pageSize 的稳定读取点：loadFirst 保持引用稳定（避免挂载 effect 重跑）
  const pageSizeRef = useRef(DEFAULT_NEWS_LIBRARY_PAGE_SIZE);
  const [total, setTotal] = useState(0);

  const [sourceSel, setSourceSel] = useState(prefill.sourceId);
  const [l0, setL0] = useState<NewsL0Filter>(prefill.l0);
  const [l1, setL1] = useState(prefill.l1);
  const [publishedFrom, setPublishedFrom] = useState('');
  const [publishedTo, setPublishedTo] = useState('');
  const [keywordInput, setKeywordInput] = useState('');
  const [keyword, setKeyword] = useState('');
  // 标的筛选（V3.1）：SubjectPicker 受控选中态（chips 点击经 subjectOfChip 适配同一状态——双向联动）
  const [subjectSel, setSubjectSel] = useState<SubjectSummary | null>(null);
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
  const loadFirst = useCallback(async (next: LibraryReload) => {
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
        buildQuery(
          next.source,
          next.l0,
          next.l1,
          next.keyword,
          1,
          pageSizeRef.current,
          next.publishedFrom ?? '',
          next.publishedTo ?? '',
          next.subjectCode ?? null,
        ),
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
  }, [fetchView]);

  // 首次加载首页 + 源下拉选项（两请求独立：源清单失败不阻塞列表）；
  // 首查按 URL 预填筛选态出数（T213：政策页重定向 L1=监管·政策 即达同口径数据）
  useEffect(() => {
    void loadFirst({ source: prefill.sourceId, l0: prefill.l0, l1: prefill.l1, keyword: '' });
    return () => listAbort.current?.abort();
  }, [loadFirst, prefill]);

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
          buildQuery(
            sourceSel,
            l0,
            l1,
            keyword,
            target,
            size,
            publishedFrom,
            publishedTo,
            subjectCodeOf(subjectSel),
          ),
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
    [sourceSel, l0, l1, keyword, subjectSel, fetchView],
  );

  const handleSourceChange = (next: string) => {
    if (next === sourceSel) return;
    setSourceSel(next);
    void loadFirst({
      source: next,
      l0,
      l1,
      keyword,
      subjectCode: subjectCodeOf(subjectSel),
      publishedFrom,
      publishedTo,
    });
  };

  const handleL0Change = (next: NewsL0Filter) => {
    if (next === l0) return;
    setL0(next);
    void loadFirst({ source: sourceSel, l0: next, l1, keyword, subjectCode: subjectCodeOf(subjectSel) });
  };

  const handleL1Change = (next: string) => {
    if (next === l1) return;
    setL1(next);
    void loadFirst({ source: sourceSel, l0, l1: next, keyword, subjectCode: subjectCodeOf(subjectSel) });
  };

  const handleSearchSubmit = (next: string) => {
    setKeyword(next);
    void loadFirst({
      source: sourceSel,
      l0,
      l1,
      keyword: next,
      subjectCode: subjectCodeOf(subjectSel),
      publishedFrom,
      publishedTo,
    });
  };

  const handleDateChange = (from: string, to: string) => {
    setPublishedFrom(from);
    setPublishedTo(to);
    void loadFirst({
      source: sourceSel,
      l0,
      l1,
      keyword,
      subjectCode: subjectCodeOf(subjectSel),
      publishedFrom: from,
      publishedTo: to,
    });
  };

  /** 标的筛选（V3.1）：picker 选中/清除 → 该标的关联资讯聚合重查（其余筛选保持）。 */
  const handleSubjectChange = (next: SubjectSummary | null) => {
    setSubjectSel(next);
    void loadFirst({
      source: sourceSel,
      l0,
      l1,
      keyword,
      subjectCode: subjectCodeOf(next),
      publishedFrom,
      publishedTo,
    });
  };

  /** 标的 chip 点击（V3.1 双向联动）：chip 携带的三字段适配为 picker 选中态后走同一筛选通道。 */
  const handleSubjectChipPick = (subject: MatchedSubject) => {
    handleSubjectChange(subjectOfChip(subject));
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
    setSubjectSel(null);
    void loadFirst({ source: '', l0: DEFAULT_NEWS_LIBRARY_L0, l1: '', keyword: '' });
  };

  const hasFilter =
    sourceSel !== '' || l1 !== '' || keyword !== '' || subjectSel != null || l0 !== DEFAULT_NEWS_LIBRARY_L0;

  return (
    <main className="mx-auto w-full max-w-4xl p-4 sm:p-6" data-testid="news-library-page">
      <PageHeader
        title="资讯库"
        subtitle="资讯源原始条目全量 · 默认仅有效（PASS）条目 · 噪音/近重复可筛"
      />
      {/* 标的聚合（V3.1）：选中标的后按当前口径呈现该标的关联资讯总数 */}
      {subjectSel ? (
        <p
          className="mt-2 flex flex-wrap items-center gap-2 text-sm text-muted-foreground"
          data-testid="news-library-subject-aggregate"
        >
          <Badge variant="secondary">
            标的：{subjectSel.subjectCode} {subjectSel.name}
          </Badge>
          <span>共 {total} 条</span>
          <span className="text-xs">（该口径下关联资讯）</span>
        </p>
      ) : null}

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
        {/* 标的筛选（V3.1）：复用 SubjectPicker 联想（代码/名称）；选中即按该标的过滤资讯 */}
        <div className="flex w-full items-center gap-2 sm:w-72" data-testid="news-library-subject-filter">
          <span className="shrink-0 text-sm text-muted-foreground">标的</span>
          <SubjectPicker
            value={subjectSel}
            onChange={handleSubjectChange}
            disabled={listLoading}
            placeholder="输代码或名称筛标的"
            testId="news-library-subject-picker"
          />
        </div>
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
              onClick={() =>
                void loadFirst({
                  source: sourceSel,
                  l0,
                  l1,
                  keyword,
                  subjectCode: subjectCodeOf(subjectSel),
                })
              }
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
                <NewsLibraryRow key={item.id} item={item} onSubjectPick={handleSubjectChipPick} />
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
