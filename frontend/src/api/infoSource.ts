// 资讯源管理数据适配层（M13 T105，对齐后端 InfoSourceController——技术方案 §4.5 契约）。
// 受 JWT 保护，经 http.ts request 自动注入 Bearer、解析 {code,msg,data,traceId}；
// 30074（在飞）由调用方收敛为「抓取中…」态不弹错（UI 设计 D6）。

import { request } from './http';
import type {
  CreateInfoSourceRequest,
  InfoSourceCardView,
  InfoSourceConnectivityResult,
  InfoSourcePollAccepted,
  InfoSourcesView,
  UpdateInfoSourceRequest,
} from '@/types/infoSource';

/** 分组列表（active 按 category 分组 + archived 归档组；卡片含今日计数与运行态摘要）。 */
export function getInfoSources(signal?: AbortSignal): Promise<InfoSourcesView> {
  return request<InfoSourcesView>('/info-sources', { signal });
}

/** 新增通用源（保存即启用：next_due_at = now，首抓 ≤1 个调度周期）。 */
export function createInfoSource(body: CreateInfoSourceRequest): Promise<InfoSourceCardView> {
  return request<InfoSourceCardView>('/info-sources', { method: 'POST', body });
}

/** 编辑源（部分字段合并，下一轮抓取热生效）。 */
export function patchInfoSource(
  id: number,
  body: UpdateInfoSourceRequest,
): Promise<InfoSourceCardView> {
  return request<InfoSourceCardView>(`/info-sources/${id}`, { method: 'PATCH', body });
}

/** 停用并归档（软删：调度摘除 + 条目保留；DELETE 语义）。 */
export function archiveInfoSource(id: number): Promise<InfoSourceCardView> {
  return request<InfoSourceCardView>(`/info-sources/${id}`, { method: 'DELETE' });
}

/** 恢复归档源（回停用态，启用时机由用户确认）。 */
export function restoreInfoSource(id: number): Promise<InfoSourceCardView> {
  return request<InfoSourceCardView>(`/info-sources/${id}/restore`, { method: 'POST' });
}

/** 连通性干跑（不落库：可达性 + robots 判读 + 解析样本 + 延迟；失败原因在 body）。 */
export function testInfoSourceConnectivity(id: number): Promise<InfoSourceConnectivityResult> {
  return request<InfoSourceConnectivityResult>(`/info-sources/${id}/connectivity-test`, {
    method: 'POST',
  });
}

/** 手动抓取（202 受理，同通道同去重；在飞 409/30074 由调用方收敛）。 */
export function pollInfoSourceNow(id: number): Promise<InfoSourcePollAccepted> {
  return request<InfoSourcePollAccepted>(`/info-sources/${id}/poll`, { method: 'POST' });
}
