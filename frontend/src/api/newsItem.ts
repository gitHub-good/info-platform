// 资讯库数据适配层（M19 T161，对齐后端 NewsItemsController——T160 契约：
// GET /api/v1/news-items 页码模式 q/l0/l1 过滤 + analysis join 字段）。
// 受 JWT 保护，经 http.ts request 自动注入 Bearer、解析 {code,msg,data,traceId}；
// 错误码：2001 参数校验（q 长度 2~64 / l0 枚举 / l1 35 枚举 / size 上限，400）；5xxx 服务异常。

import { request } from './http';
import type { NewsL0Filter, NewsLibraryPagedView } from '@/types/newsItem';

/** 每页条数默认值（REQ 拍板一：默认 20 条/页；选项 10/20/50 由 Pagination 提供）。 */
export const DEFAULT_NEWS_LIBRARY_PAGE_SIZE = 20;

/** L0 状态筛选缺省（REQ 拍板一：默认仅 PASS——对齐管道消费口径）。 */
export const DEFAULT_NEWS_LIBRARY_L0: NewsL0Filter = 'PASS';

/** 资讯库页码模式查询参数（对象收拢，多参不散摆）。 */
export interface NewsLibraryQuery {
  /** 源过滤（info-sources 活跃源 id）；null 不过滤。 */
  sourceId?: number | null;
  /** 关键词（标题/摘要 LIKE）；null/空串不过滤。前端已按 ≥2 字符拦截。 */
  q?: string | null;
  /** L0 状态筛选（PASS/NOISE/NEAR_DUP/ALL）；缺省 PASS。 */
  l0?: NewsL0Filter;
  /** 主分类过滤（35 枚举）；null/空串不过滤。 */
  l1?: string | null;
  /** 发布时间窗（yyyy-MM-dd，上海日界含端点；BUG-M23-01）。 */
  publishedFrom?: string | null;
  publishedTo?: string | null;
  /** 入库时间窗（yyyy-MM-dd，上海日界含端点；V2.4 T210——大盘「今日入库」弹框对账口径）。 */
  fetchedFrom?: string | null;
  fetchedTo?: string | null;
  page: number;
  size: number;
}

/**
 * 资讯库列表（页码分页 + 源/状态/分类/关键词四维过滤，全 AND 语义）。
 * @param query 查询参数（page 1 起；size 仅 10/20/50，后端上限 50）
 * @param signal 可选中止信号（筛选变更/翻页互斥时取消在途请求）
 * @throws ApiError 2001(400) 参数校验 / 5xxx 服务异常
 */
export async function listNewsLibraryPaged(
  query: NewsLibraryQuery,
  signal?: AbortSignal,
): Promise<NewsLibraryPagedView> {
  const params = new URLSearchParams();
  if (query.sourceId != null) {
    params.set('sourceId', String(query.sourceId));
  }
  if (query.q && query.q.trim()) {
    params.set('q', query.q.trim());
  }
  if (query.l0) {
    params.set('l0', query.l0);
  }
  if (query.l1 && query.l1.trim()) {
    params.set('l1', query.l1.trim());
  }
  if (query.publishedFrom) {
    params.set('publishedFrom', query.publishedFrom);
  }
  if (query.publishedTo) {
    params.set('publishedTo', query.publishedTo);
  }
  if (query.fetchedFrom) {
    params.set('fetchedFrom', query.fetchedFrom);
  }
  if (query.fetchedTo) {
    params.set('fetchedTo', query.fetchedTo);
  }
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  return request<NewsLibraryPagedView>(`/news-items?${params.toString()}`, { signal });
}
