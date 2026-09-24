// 政策时事流类型（对齐技术方案 §4.1.5 + M9 页码分页契约：
// PolicyView / PolicyPagedView / PolicyDetailView / RelatedSubjectView）。
//
// GET /api/v1/policies?days=&industry=&keyword=&page=&size= → PolicyPagedView
// GET /api/v1/policies/{id}                                 → PolicyDetailView（含关联自选标的 + aiTendency）

/** 政策 AI 倾向码（对齐后端 ai_tendency.code：T24 全 0 未判，T28 AI 填 1/2/3）。 */
export type AiTendencyCode = 0 | 1 | 2 | 3;

/** 政策列表项（GET /policies 每条）。publishedAt 为 ISO-8601 日期文本（yyyy-MM-dd）。 */
export interface PolicyView {
  id: number;
  title: string;
  source: string;
  publishedAt: string | null;
  summary: string;
  relatedIndustries: string[];
}

/** 政策列表页码分页视图（M9）。total 为筛选后精确总数（「共 N 条」与空页回退依据）。 */
export interface PolicyPagedView {
  policies: PolicyView[];
  total: number;
  page: number;
  size: number;
}

/** 政策详情关联自选标的（按 relatedIndustries 匹配当前用户 watchlist）。 */
export interface RelatedSubjectView {
  subjectCode: string;
  subjectName: string;
  industry: string;
}

/** 政策详情视图（GET /policies/{id}）。aiTendency 0未判/1利好/2利空/3中性。 */
export interface PolicyDetailView {
  id: number;
  title: string;
  source: string;
  publishedAt: string | null;
  summary: string;
  relatedIndustries: string[];
  sourceUrl: string | null;
  aiTendency: AiTendencyCode;
  relatedSubjects: RelatedSubjectView[];
}
