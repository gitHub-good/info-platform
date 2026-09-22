// LLM 模型配置数据适配层（T39 + 后端 LlmConfigController）。
// 受 JWT 保护，经 http.ts request 自动注入 Bearer、解析 {code,msg,data,traceId}。
// 安全：API key 只写不读（putLlmApiKey 明文仅在请求体内瞬时存在，不写 console/不拼 URL）。

import { request } from './http';
import type {
  LlmConfigView,
  LlmConnectivityResult,
  LlmGlobalConfigView,
  LlmGlobalUpdate,
  LlmProviderConfigView,
  LlmProviderUpdate,
} from '@/types/llmConfig';

/** 拉取全量配置视图（含脱敏 key 态与逐字段 effectiveMode）。 */
export function getLlmConfig(signal?: AbortSignal): Promise<LlmConfigView> {
  return request<LlmConfigView>('/llm-config', { signal });
}

/** 更新全局参数（部分字段合并；返回更新后的视图含新 updatedAt）。 */
export function patchLlmGlobal(update: LlmGlobalUpdate): Promise<LlmGlobalConfigView> {
  return request<LlmGlobalConfigView>('/llm-config/global', { method: 'PATCH', body: update });
}

/** 更新单 provider（isDefault=true 服务端互斥置反其他 provider）。 */
export function putLlmProvider(
  name: string,
  update: LlmProviderUpdate,
): Promise<LlmProviderConfigView> {
  return request<LlmProviderConfigView>(`/llm-config/providers/${name}`, {
    method: 'PUT',
    body: update,
  });
}

/** 写入 API key（AES-256-GCM 加密落库，只写不读，成功回脱敏态）。 */
export function putLlmApiKey(
  name: string,
  apiKey: string,
  expectedUpdatedAt?: string,
): Promise<LlmProviderConfigView> {
  return request<LlmProviderConfigView>(`/llm-config/providers/${name}/api-key`, {
    method: 'PUT',
    body: { apiKey, expectedUpdatedAt },
  });
}

/** 连通性测试（同步短超时探活，测试已执行即 200）。 */
export function testLlmConnectivity(name: string): Promise<LlmConnectivityResult> {
  return request<LlmConnectivityResult>(`/llm-config/providers/${name}/connectivity-test`, {
    method: 'POST',
  });
}
