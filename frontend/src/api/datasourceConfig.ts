// 数据源配置数据适配层（T40 + 后端 DataSourceConfigController）。
// 受 JWT 保护，经 http.ts request 自动注入 Bearer、解析 {code,msg,data,traceId}。
// 写接口带 expectedUpdatedAt 防并发误覆盖（不符 → 30065/409）。

import { request } from './http';
import type {
  AggregationGlobalUpdate,
  AggregationGlobalView,
  DataSourceCardView,
  DataSourceConfigUpdate,
  DataSourceConfigView,
  DataSourceConnectivityResult,
} from '@/types/datasourceConfig';

/** 拉取全量视图（7 源卡片含健康徽章数据 + 聚合总超时条）。 */
export function getDatasourceConfigs(signal?: AbortSignal): Promise<DataSourceConfigView> {
  return request<DataSourceConfigView>('/datasource-configs', { signal });
}

/** 更新单源配置（PATCH 语义部分合并，数据源参数全部 LIVE 保存即生效）。 */
export function patchDatasourceSource(
  sourceCode: string,
  update: DataSourceConfigUpdate,
): Promise<DataSourceCardView> {
  return request<DataSourceCardView>(`/datasource-configs/${sourceCode}`, {
    method: 'PATCH',
    body: update,
  });
}

/** 更新聚合编排总超时（页面「聚合总超时条」，LIVE 每请求生效）。 */
export function patchAggregationGlobal(
  update: AggregationGlobalUpdate,
): Promise<AggregationGlobalView> {
  return request<AggregationGlobalView>('/datasource-configs/aggregation/global', {
    method: 'PATCH',
    body: update,
  });
}

/** 分源连通性测试（单源试拉一次；mock 模式本地校验并附 note，测试已执行即 200）。 */
export function testDatasourceConnectivity(
  sourceCode: string,
): Promise<DataSourceConnectivityResult> {
  return request<DataSourceConnectivityResult>(`/datasource-configs/${sourceCode}/connectivity-test`, {
    method: 'POST',
  });
}
