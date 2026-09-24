// 标的详情数据适配层（P0-1 真实化）。
// - 统一走 http.ts request()：自动注入 Bearer、解析 { code, msg, data }、401 跳登录。
// - 前端不再自带 mock 数据；「mock 能力」由后端 datasource mode=MOCK 提供（配置页可切），
//   输出结构与真实结构一致，本层无需分支。

import { request } from '@/api/http';
import type { Quote, SectionCode, SubjectDetailData } from '@/types/subject-detail';

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
 * 标的模糊搜索（体检 P1-2 搜索选择器数据源）。
 * GET /api/v1/subjects/search?q=xxx&limit=20：按代码/名称 contains（大小写不敏感）搜启用标的；
 * q 空白 → ApiError(2001, 400)；无结果返回空数组（非错误）。
 */
export function searchSubjects(
  q: string,
  options: { limit?: number; signal?: AbortSignal } = {},
): Promise<SubjectSummary[]> {
  const limit = options.limit ?? 20;
  return request<SubjectSummary[]>(
    `/subjects/search?q=${encodeURIComponent(q)}&limit=${limit}`,
    { signal: options.signal },
  );
}

/** 批量标的行情行（GET /subjects/quotes 响应元素）：标的摘要 + 行情分区（失败/无数据为 null）。 */
export interface SubjectQuoteRow extends SubjectSummary {
  quote: Quote | null;
}

/**
 * 批量取标的摘要+行情（体检 P1-2 自选清单表格列）。
 * GET /api/v1/subjects/quotes?ids=1,2,3：不存在的主键后端跳过；任一标的行情失败该行 quote=null 不阻断。
 * ids 为空不入请求（调用方自行兜底空对象）。
 */
export function fetchSubjectQuotes(
  ids: number[],
  signal?: AbortSignal,
): Promise<SubjectQuoteRow[]> {
  return request<SubjectQuoteRow[]>(`/subjects/quotes?ids=${ids.join(',')}`, { signal });
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
