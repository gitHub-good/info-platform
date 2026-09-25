// 标的详情分区分页/加载更多状态机（M12 T94，UI 设计 §5.1/§5.2 + 技术方案 §4.3）。
// - 两类 loading 分离：页面级首查骨架（useSubjectDetail）不动，本 hook 只管分区在途——
//   在途=保留内容 + 控件禁用；失败=错误态 + 重试（重发同一目标页/同一探页，不清数据基线）。
// - 每分区独立 AbortController：新请求 abort 旧请求，生命周期不触碰其他分区（§5.2）。
// - 切标的重置由 App.tsx key={subjectId} 重挂载承担（UI 设计 D8）：
//   本 hook 初始值只在挂载时取一次，无逐项枚举重置的遗漏面。

import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '@/api/http';
import {
  fetchAnnouncementPage,
  fetchEventPage,
  fetchNewsPage,
} from '@/api/subjectSection';
import type {
  Announcement,
  AnnouncementPageView,
  EventItem,
  NewsItem,
  SourceStatus,
} from '@/types/subject-detail';

/** 公告分区页大小（V19 后运行时缺省 10；分区恒定不提供切换，PRD Won't 8）。 */
export const ANNOUNCE_PAGE_SIZE = 10;
/** 公告分区页码上限（D5：后端 400 权威，前端 maxPages 显示层封顶双保险）。 */
export const ANNOUNCE_MAX_PAGES = 5;
/** 事件分区页大小（原 MAX_ITEMS 语义升级为页大小常量，方案 §4.1.2）。 */
export const EVENT_PAGE_SIZE = 10;
/** 新闻探页：单次点击最多探 2 个源页（首个空页自动续探，§4.3.2）。 */
const NEWS_MAX_PAGES_PER_CLICK = 2;
/** 新闻探页：累计探页达 10 停止（首屏源页 1 计入累计）。 */
const NEWS_MAX_EXPLORED_PAGES = 10;
/** 新闻探页：连续 2 个源页零新增停止。 */
const NEWS_CONSECUTIVE_EMPTY_LIMIT = 2;

/** 公告分区分页元数据（首屏来自聚合 sectionPagination，翻页后以子端点响应覆盖）。 */
export interface AnnouncePageMeta {
  /** 源公告总数；null=不可知（巨潮降级 total 键不出现）。 */
  total: number | null;
  paginationSupported: boolean;
  moreUrl: string | null;
}

/** 翻页失败态：目标页收进重试闭包，显示态不前跳（§5.2）。 */
export interface SectionPageError {
  page: number;
  message: string;
}

/** 分区翻页对外状态面（公告/事件共用核心状态机）。 */
export interface SectionPageTurn<TItem, TMeta> {
  items: TItem[];
  meta: TMeta;
  page: number;
  loading: boolean;
  error: SectionPageError | null;
  goToPage: (target: number) => void;
  retry: () => void;
}

/** 子端点响应 → 落地结果（fetch 适配器归一化输出）。 */
interface SectionPageLanding<TItem, TMeta> {
  items: TItem[];
  /** 落点页（越界回退后可能与请求页不同）。 */
  page: number;
  meta: TMeta;
  /** false=保留当前列表仅更新元数据（巨潮降级空页：保留内容不视为错误）。 */
  replaceItems: boolean;
}

/** 200 + sourceStatus 非 ok 的分区降级（不是 HTTP 错误，走同一错误行通道，§4.1.5）。 */
class SectionStatusError extends Error {}

const STATUS_MESSAGE: Record<SourceStatus, string> = {
  ok: '',
  missing: '本分区暂未返回数据',
  failed: '数据源获取失败，请稍后重试',
  timeout: '数据源响应超时，请稍后重试',
};

function assertSectionOk(status: SourceStatus): void {
  if (status !== 'ok') throw new SectionStatusError(STATUS_MESSAGE[status]);
}

/** 错误 → 用户可读文案（不直出技术串）。 */
function pageErrorMessage(err: unknown, fallback: string): string {
  if (err instanceof ApiError) return err.msg;
  if (err instanceof SectionStatusError) return err.message;
  return fallback;
}

function announceMetaOf(resp: AnnouncementPageView): AnnouncePageMeta {
  return {
    total: resp.total ?? null,
    paginationSupported: resp.paginationSupported,
    moreUrl: resp.moreUrl ?? null,
  };
}

/**
 * 公告子端点响应 → 落地结果（§4.3.1 降级链 + §4.1.1 越界语义）：
 * - sourceStatus 非 ok → 抛错（保留当前内容 + 错误行 + 重试）；
 * - 巨潮降级（paginationSupported=false）：page>1 恒空列表 → 保留当前内容仅切降级文案；
 * - 越界空页（page ≤5 但超出源总页数）→ 静默回第 1 页。
 */
export async function landAnnouncementPage(
  subjectId: number,
  page: number,
  signal: AbortSignal,
): Promise<SectionPageLanding<Announcement, AnnouncePageMeta>> {
  let resp = await fetchAnnouncementPage(subjectId, page, { signal });
  assertSectionOk(resp.sourceStatus);
  if (
    resp.paginationSupported &&
    resp.items.length === 0 &&
    page > 1 &&
    (resp.total ?? 0) > 0
  ) {
    resp = await fetchAnnouncementPage(subjectId, 1, { signal });
    assertSectionOk(resp.sourceStatus);
    return { items: resp.items, page: 1, meta: announceMetaOf(resp), replaceItems: true };
  }
  return {
    items: resp.items,
    page,
    meta: announceMetaOf(resp),
    replaceItems: resp.items.length > 0 || resp.paginationSupported,
  };
}

/** 事件子端点响应 → 落地结果（§4.1.2：total=7 天窗精确 count，翻页后覆盖首屏值）。 */
export async function landEventPage(
  subjectId: number,
  page: number,
  signal: AbortSignal,
): Promise<SectionPageLanding<EventItem, { total: number }>> {
  const resp = await fetchEventPage(subjectId, page, { signal });
  assertSectionOk(resp.sourceStatus);
  return { items: resp.items, page, meta: { total: resp.total }, replaceItems: true };
}

/** 分区翻页核心状态机（公告/事件共用；enabled=false 时翻页入口静默不发请求）。 */
function useSectionPageCore<TItem, TMeta>(
  enabled: boolean,
  fetchLanding: (page: number, signal: AbortSignal) => Promise<SectionPageLanding<TItem, TMeta>>,
  initialItems: TItem[],
  initialMeta: TMeta,
  onLanded?: () => void,
): SectionPageTurn<TItem, TMeta> {
  const [items, setItems] = useState<TItem[]>(initialItems);
  const [meta, setMeta] = useState<TMeta>(initialMeta);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<SectionPageError | null>(null);
  /** 单一中止点：新请求发出前 abort 旧的，回填前检查 aborted（§5.4 平移）。 */
  const abortRef = useRef<AbortController | null>(null);
  const pageRef = useRef(1);

  useEffect(() => () => abortRef.current?.abort(), []);

  const run = useCallback(
    async (target: number) => {
      abortRef.current?.abort();
      const ctrl = new AbortController();
      abortRef.current = ctrl;
      setLoading(true);
      setError(null);
      try {
        const landing = await fetchLanding(target, ctrl.signal);
        if (ctrl.signal.aborted) return;
        if (landing.replaceItems) setItems(landing.items);
        setMeta(landing.meta);
        setPage(landing.page);
        pageRef.current = landing.page;
        onLanded?.();
      } catch (err) {
        if (ctrl.signal.aborted) return;
        setError({ page: target, message: pageErrorMessage(err, '网络异常或服务暂不可用') });
      } finally {
        if (!ctrl.signal.aborted) setLoading(false);
      }
    },
    [fetchLanding, onLanded],
  );

  const goToPage = useCallback(
    (target: number) => {
      if (!enabled || target === pageRef.current) return;
      void run(target);
    },
    [enabled, run],
  );

  const retry = useCallback(() => {
    if (error) void run(error.page);
  }, [error, run]);

  return { items, meta, page, loading, error, goToPage, retry };
}

/** 公告分区分页（AnnounceSection 用）：降级保留/越界回退语义内置在 landAnnouncementPage。 */
export function useAnnounceSectionPage(
  subjectId: number | null,
  initial: { items: Announcement[]; meta: AnnouncePageMeta },
  onLanded?: () => void,
): SectionPageTurn<Announcement, AnnouncePageMeta> {
  const fetchLanding = useCallback(
    async (
      page: number,
      signal: AbortSignal,
    ): Promise<SectionPageLanding<Announcement, AnnouncePageMeta>> => {
      if (subjectId == null) throw new Error('标的主键未解析，无法翻页');
      return landAnnouncementPage(subjectId, page, signal);
    },
    [subjectId],
  );
  return useSectionPageCore(
    subjectId != null,
    fetchLanding,
    initial.items,
    initial.meta,
    onLanded,
  );
}

/** 事件分区分页（EventSection 用）：标准页码条 + total 以子端点响应覆盖。 */
export function useEventSectionPage(
  subjectId: number | null,
  initial: { items: EventItem[]; total: number },
  onLanded?: () => void,
): SectionPageTurn<EventItem, { total: number }> {
  const fetchLanding = useCallback(
    async (
      page: number,
      signal: AbortSignal,
    ): Promise<SectionPageLanding<EventItem, { total: number }>> => {
      if (subjectId == null) throw new Error('标的主键未解析，无法翻页');
      return landEventPage(subjectId, page, signal);
    },
    [subjectId],
  );
  return useSectionPageCore(
    subjectId != null,
    fetchLanding,
    initial.items,
    { total: initial.total },
    onLanded,
  );
}

/** 新闻「加载更多」对外状态面。 */
export interface NewsLoadMore {
  items: NewsItem[];
  loading: boolean;
  /** 停止终态（同标的会话内不再外呼；切标的 key 重挂载后重新探测）。 */
  stopped: boolean;
  error: string | null;
  /** 追加批次首条下标（滚动定位请求；acknowledgeScroll 后清空）。 */
  scrollRequest: number | null;
  loadMore: () => void;
  retry: () => void;
  acknowledgeScroll: () => void;
}

/** 新闻条目稳定标识（externalId/docid 级——去重唯一依据，title 不参与去重，§7.1-6）。 */
export function stableNewsId(item: NewsItem): string | null {
  return item.externalId ?? item.id ?? null;
}

/**
 * 新闻「加载更多」探页状态机（§4.3.2，D4 前端判定「新增」）：
 * 后端契约无状态（单请求=单源页过滤命中 + hasMore），客户端累积 externalId 集是唯一真相。
 * 停止三条件：连续 2 源页零新增 / hasMore=false（源页耗尽）/ 累计探页达 10（首屏计入 1）。
 */
export function useNewsSectionPage(
  subjectId: number | null,
  initialItems: NewsItem[],
): NewsLoadMore {
  const [items, setItems] = useState<NewsItem[]>(initialItems);
  const [loading, setLoading] = useState(false);
  const [stopped, setStopped] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [scrollRequest, setScrollRequest] = useState<number | null>(null);
  /** 已见条目稳定标识集（「新增」判定唯一真相）。 */
  const seenIds = useRef<Set<string>>(
    new Set(initialItems.map(stableNewsId).filter((id): id is string => id != null)),
  );
  /** 下一目标源页（首屏已消费源页 1）与累计探页数（首屏计入 1）。 */
  const nextPage = useRef(2);
  const explored = useRef(1);
  /** 列表长度镜像（scrollRequest 下标计算用，绕开 setState 闭包时序）。 */
  const appendedCount = useRef(initialItems.length);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => () => abortRef.current?.abort(), []);

  const explore = useCallback(async () => {
    if (subjectId == null || stopped) return;
    abortRef.current?.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    setLoading(true);
    setError(null);
    let consecutiveEmpty = 0;
    try {
      // 单次点击最多探 2 个源页：首个源页零新增 → 自动续探下一页
      for (let round = 0; round < NEWS_MAX_PAGES_PER_CLICK; round++) {
        const target = nextPage.current;
        const resp = await fetchNewsPage(subjectId, target, ctrl.signal);
        if (resp.sourceStatus !== 'ok') {
          throw new SectionStatusError(STATUS_MESSAGE[resp.sourceStatus]);
        }
        explored.current += 1;
        nextPage.current = target + 1;
        const ids = resp.items.map(stableNewsId);
        const fresh = resp.items.filter(
          (_item, index) => ids[index] == null || !seenIds.current.has(ids[index] as string),
        );
        for (const id of ids) {
          if (id != null) seenIds.current.add(id);
        }
        if (fresh.length > 0) {
          const start = appendedCount.current;
          appendedCount.current = start + fresh.length;
          setItems((current) => [...current, ...fresh]);
          setScrollRequest(start);
        }
        const reachedCap = explored.current >= NEWS_MAX_EXPLORED_PAGES;
        if (!resp.hasMore || reachedCap) {
          setStopped(true);
          return;
        }
        if (fresh.length > 0) return;
        consecutiveEmpty += 1;
        if (consecutiveEmpty >= NEWS_CONSECUTIVE_EMPTY_LIMIT) {
          setStopped(true);
          return;
        }
        // 空页且未触发停止 → 续探下一源页
      }
    } catch (err) {
      if (ctrl.signal.aborted) return;
      setError(pageErrorMessage(err, '网络异常或服务暂不可用'));
    } finally {
      if (!ctrl.signal.aborted) setLoading(false);
    }
  }, [subjectId, stopped]);

  const loadMore = useCallback(() => {
    if (loading) return;
    void explore();
  }, [explore, loading]);

  const retry = useCallback(() => {
    void explore();
  }, [explore]);

  const acknowledgeScroll = useCallback(() => setScrollRequest(null), []);

  return { items, loading, stopped, error, scrollRequest, loadMore, retry, acknowledgeScroll };
}
