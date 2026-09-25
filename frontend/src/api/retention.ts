// 留痕窗口数据适配层（T73 + 后端 RetentionController 契约 §4.3）。
// 受 JWT 保护，经 http.ts request 自动注入 Bearer、解析 {code,msg,data,traceId}。
// PATCH 四字段全量替换，带 expectedUpdatedAt 防并发误覆盖（不符 → 30065/409）；非法值 2001 字段级、原值保留。

import { request } from './http';
import type { RetentionWindowsUpdate, RetentionWindowsView } from '@/types/retention';

/** 拉取当前窗口视图（任务中心 RETENTION_CLEANUP 编辑 Dialog 预填与下限提示数据源）。 */
export function getRetentionWindows(signal?: AbortSignal): Promise<RetentionWindowsView> {
  return request<RetentionWindowsView>('/retention/windows', { signal });
}

/** 全量替换窗口（保存即热生效——下一轮清理按新窗口）。 */
export function patchRetentionWindows(
  update: RetentionWindowsUpdate,
): Promise<RetentionWindowsView> {
  return request<RetentionWindowsView>('/retention/windows', {
    method: 'PATCH',
    body: update,
  });
}
