// 提示词模板管理数据适配层（M5 T47/T48，后端 PromptTemplateController）。
// 受 JWT 保护，经 http.ts request 自动注入 Bearer、解析 {code,msg,data,traceId}。
// 保存的分级错误均以 ApiError 抛出：400/30067 硬校验（msg "; " 联播）、409/30068 待确认移除
// （err.data 携带 removed/unknown 清单）、409/30070 版本冲突、409/30069 激活不可删。

import { request } from './http';
import type {
  PromptActivateResult,
  PromptCreateRequest,
  PromptCreateResult,
  PromptDeleteResult,
  PromptDetailView,
  PromptListView,
  PromptRegistryView,
  PromptScenarioView,
} from '@/types/promptTemplate';

/** 4 场景分组版本总览（轻列表；activeCount 异常由页面警示）。 */
export function getPromptTemplates(signal?: AbortSignal): Promise<PromptListView> {
  return request<PromptListView>('/prompt-templates', { signal });
}

/** 版本详情（编辑底稿与 diff 基准取全文；404 → 30066）。 */
export function getPromptTemplateDetail(
  id: number,
  signal?: AbortSignal,
): Promise<PromptDetailView> {
  return request<PromptDetailView>(`/prompt-templates/${id}`, { signal });
}

/** 新建版本（保存即激活，201）。 */
export function createPromptTemplate(body: PromptCreateRequest): Promise<PromptCreateResult> {
  return request<PromptCreateResult>('/prompt-templates', { method: 'POST', body });
}

/** 激活切换（回滚任意保留版本；目标已是激活幂等 200）。 */
export function activatePromptTemplate(id: number): Promise<PromptActivateResult> {
  return request<PromptActivateResult>(`/prompt-templates/${id}/activate`, { method: 'POST' });
}

/** 物理删除（仅 RETIRED；激活 → 409/30069）。 */
export function deletePromptTemplate(id: number): Promise<PromptDeleteResult> {
  return request<PromptDeleteResult>(`/prompt-templates/${id}`, { method: 'DELETE' });
}

/** 单场景占位符注册表（编辑器对照栏与保存校验同源数据源）。 */
export async function getPromptPlaceholders(
  briefType: number,
  signal?: AbortSignal,
): Promise<PromptScenarioView> {
  const view = await request<PromptRegistryView>(`/prompt-placeholders?briefType=${briefType}`, {
    signal,
  });
  // 合法 briefType 恒返回 1 个场景；空数组为契约外防御，按空清单降级
  return view.scenarios[0] ?? { briefType, name: '', dormant: false, placeholders: [] };
}
