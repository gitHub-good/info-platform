// 全市场榜单数据适配层（M21 T181 + 后端 MarketTopController 契约 §4.7.3）。
// 受 JWT 保护，经 http.ts request 自动注入 Bearer、解析 {code,msg,data,traceId}。
// PATCH 5 字段全量替换，带 expectedUpdatedAt 防并发误覆盖（不符 → 30065/409）；非法值 30091 字段级、原值保留。
// 榜单读取（GET /market-top）与方法论端点随 T183/T185 增补。

import { request } from './http';
import type { MarketTopConfigUpdate, MarketTopConfigView } from '@/types/marketTop';

/** 拉取当前漏斗配置视图（任务中心 MARKET_TOP_JOB 编辑 Dialog 预填数据源）。 */
export function getMarketTopConfig(signal?: AbortSignal): Promise<MarketTopConfigView> {
  return request<MarketTopConfigView>('/market-top/config', { signal });
}

/** 全量替换漏斗配置（保存即热生效——下一轮 18:00 榜单按新参数计算）。 */
export function patchMarketTopConfig(update: MarketTopConfigUpdate): Promise<MarketTopConfigView> {
  return request<MarketTopConfigView>('/market-top/config', {
    method: 'PATCH',
    body: update,
  });
}
