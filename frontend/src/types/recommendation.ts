// 每日推荐类型（体检 P1-4，对齐后端 DailyRecommendationView / TopRecommendation——T23/T29 已交付）。
//
// GET /api/v1/recommendations/daily → { status, topRecommend[], disclaimer }
// - status：1 AI 生成 Top5 / 2 规则兜底（相关性综合分排序，非 AI 输出）/ 3 自选池空（Top5 为空）
//   （4 PENDING 仅 feed 只读路径产生，本端点轮询至终态不会返回）
// - 契约无独立「信息面活跃度」字段：活跃度依据包含在 reason 文本中（展示取理由首行）。

/** 每日推荐条目（rank 1 起越小越优先；subjectCode 如 SH600519）。 */
export interface TopRecommendation {
  subjectCode: string;
  subjectName: string;
  reason: string;
  rank: number;
}

/** 每日推荐响应视图（对齐后端 DailyRecommendationView）。 */
export interface DailyRecommendationView {
  /** 1 AI 生成 / 2 规则兜底 / 3 自选池空。 */
  status: number;
  topRecommend: TopRecommendation[];
  /** 免责声明（恒附「AI 生成，非投资建议」）。 */
  disclaimer: string;
}

/** 每日推荐状态码（对齐后端 DailyRecommendationResult 常量）。 */
export const DAILY_RECOMMENDATION_STATUS = {
  DONE: 1,
  FALLBACK: 2,
  EMPTY: 3,
} as const;
