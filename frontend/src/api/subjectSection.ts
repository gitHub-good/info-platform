// 标的详情分区子端点取数层（M12 T94，技术方案 §4.1 / ADR-0037 决策 1）。
// - 一请求=一分区（分区独立翻页三约束①）；翻页绕 SourceCache 直调源（决策 2）；
//   sourceStatus 三态降级语义与聚合同源（约束③，200 + 非 ok 不是 HTTP 错误）。
// - 统一走 http.ts request()：Bearer 注入 + { code, msg, data } 解析 + 401 跳登录。

import { request } from '@/api/http';
import type {
  AnnouncementPageView,
  EventPageView,
  NewsPageView,
} from '@/types/subject-detail';

/**
 * 公告分区分页（§4.1.1）：page 必填 1~5（超限 400「更多历史公告请走源站」）；
 * size 可选 1~50，缺省=运行时 announcePageSize（不发 size 参数）。
 */
export function fetchAnnouncementPage(
  subjectId: number,
  page: number,
  options: { size?: number; signal?: AbortSignal } = {},
): Promise<AnnouncementPageView> {
  const params = new URLSearchParams({ page: String(page) });
  if (options.size != null) params.set('size', String(options.size));
  return request<AnnouncementPageView>(
    `/subjects/${subjectId}/announcements?${params.toString()}`,
    { signal: options.signal },
  );
}

/**
 * 事件分区分页（§4.1.2）：page 必填 ≥1（本地 7 天窗数据翻完即止，无 5 页上限）；
 * size 可选（缺省 10，原 MAX_ITEMS 语义升级为页大小常量）。
 */
export function fetchEventPage(
  subjectId: number,
  page: number,
  options: { size?: number; signal?: AbortSignal } = {},
): Promise<EventPageView> {
  const params = new URLSearchParams({ page: String(page) });
  if (options.size != null) params.set('size', String(options.size));
  return request<EventPageView>(`/subjects/${subjectId}/events?${params.toString()}`, {
    signal: options.signal,
  });
}

/**
 * 新闻分区「加载更多」（§4.1.3）：page=源页码（必填）；
 * 端点不接受 size——出现即 400（源页大小是运维配置 newsPageSize，不属调用方自由度）。
 */
export function fetchNewsPage(
  subjectId: number,
  page: number,
  signal?: AbortSignal,
): Promise<NewsPageView> {
  return request<NewsPageView>(`/subjects/${subjectId}/news?page=${page}`, { signal });
}
