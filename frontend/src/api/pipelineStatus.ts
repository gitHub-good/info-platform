// 管道状态数据适配层（M18 T156 概览工作台「管道成本水位」摘要用）。
// GET /api/v1/pipeline/status（Bearer）——护栏/成本三处同源端点（降级横幅/成本报表/验收断言）复用，零新数据链路。

import { request } from './http';
import type { PipelineStatusView } from '@/types/pipelineStatus';

/** 管道状态（护栏等级 + 当日成本/预算）。 */
export function getPipelineStatus(signal?: AbortSignal): Promise<PipelineStatusView> {
  return request<PipelineStatusView>('/pipeline/status', { signal });
}
