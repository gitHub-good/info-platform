// 概览仪表盘数据适配层（T42 + 后端 OverviewController）。
// GET /api/v1/overview → OverviewView（五卡片一次聚合，避免首屏 5 并发）
// 受 JWT 保护，经 http.ts request 自动注入 Bearer、解析 {code,msg,data,traceId}。

import { request } from './http';
import type { OverviewView } from '@/types/overview';

/**
 * 拉取概览五卡片聚合数据。
 * @param signal 可选中止信号
 * @throws ApiError 5xxx 服务异常（整页失败走页面错误态；单卡失败由卡级 error 字段区分）
 */
export async function getOverview(signal?: AbortSignal): Promise<OverviewView> {
  return request<OverviewView>('/overview', { signal });
}
