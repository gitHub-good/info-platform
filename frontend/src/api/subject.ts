// 标的详情数据适配层（P0-1 真实化）。
// - 统一走 http.ts request()：自动注入 Bearer、解析 { code, msg, data }、401 跳登录。
// - 前端不再自带 mock 数据；「mock 能力」由后端 datasource mode=MOCK 提供（配置页可切），
//   输出结构与真实结构一致，本层无需分支。

import { request } from '@/api/http';
import type { SectionCode, SubjectDetailData } from '@/types/subject-detail';

/** 标的摘要（GET /subjects/by-code/{code} 响应 data）：数字主键 + 头部展示字段。 */
export interface SubjectSummary {
  /** 数字主键（聚合详情接口寻址用） */
  id: number;
  /** 内部统一代码，如 SH600519 */
  subjectCode: string;
  name: string;
  market: string;
  type: number;
  industry?: string | null;
}

/**
 * 内部统一代码 → 数字主键解析（P0-1）。
 * GET /api/v1/subjects/by-code/{code}；标的不存在 → ApiError(30001, 404)。
 */
export function fetchSubjectByCode(code: string, signal?: AbortSignal): Promise<SubjectSummary> {
  return request<SubjectSummary>(`/subjects/by-code/${encodeURIComponent(code)}`, { signal });
}

/**
 * 拉取标的详情聚合数据。
 * GET /api/v1/subjects/{subjectId}/detail?sections=quote,finance,...
 * 单源缺失不阻断，后端在 sourceStatus 中标注每分区状态。
 *
 * @param subjectId 数字主键（先经 fetchSubjectByCode 解析）
 * @param sections 分区清单；缺省请求全部分区（后端默认）
 */
export function fetchSubjectDetail(
  subjectId: number,
  sections?: SectionCode[],
  signal?: AbortSignal,
): Promise<SubjectDetailData> {
  const query = sections?.length ? `?sections=${sections.join(',')}` : '';
  return request<SubjectDetailData>(`/subjects/${subjectId}/detail${query}`, { signal });
}
