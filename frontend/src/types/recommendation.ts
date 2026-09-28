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

// —— 推荐中心（M16 T134/T135，对齐后端 RecommendationCardListView / FeedbackResult 契约，方案 §4.8） ——

/** 关联层级（P1 标的直接 / P2 行业 / P3 订阅；多级命中取最高）。 */
export const REC_LEVEL_LABELS: Record<string, string> = {
  P1: '标的直接',
  P2: '行业',
  P3: '订阅',
};

/** 反馈动作（对齐后端 FeedbackAction 枚举名）。 */
export type RecommendationFeedbackAction =
  | 'USEFUL'
  | 'DISLIKE'
  | 'ADD_WATCHLIST'
  | 'UNDO_MUTE';

/** 卡片标的区条目（≤5；inWatchlist=false 展示「加自选」）。 */
export interface RecommendationSubject {
  code: string | null;
  name: string | null;
  industry: string | null;
  inWatchlist: boolean;
}

/** 关键数字 chips（结构化事实直出，禁编造）。 */
export interface RecommendationFigure {
  label: string | null;
  value: string | null;
  unit: string | null;
}

/** 推荐卡片（字段面 = 方案 §4.8 冻结契约；pushStatus 含 SILENT 语义态）。 */
export interface RecommendationCardItem {
  id: number;
  eventId: number;
  eventType: string;
  importance: string;
  direction: string;
  level: string;
  industries: string[];
  subjects: RecommendationSubject[];
  logicChain: string;
  summary: string | null;
  figures: RecommendationFigure[];
  quote: string | null;
  newsId: number;
  newsTitle: string | null;
  newsUrl: string | null;
  eventTime: string | null;
  pushStatus: string;
  pushedAt: string | null;
  createdAt: string | null;
  read: boolean;
  /** 该卡组合当前降频中（撤销入口展示判定）。 */
  muted: boolean;
  /** 操作条已点动作回显（最近一条反馈；null = 未反馈）。 */
  feedbackAction: RecommendationFeedbackAction | null;
}

/** 卡片流视图（total 无筛选 = 全量卡，§4.11 对账基准）。 */
export interface RecommendationListView {
  total: number;
  items: RecommendationCardItem[];
  nextBeforeId: number | null;
}

/** 卡片流页码视图（M25 T224 消费 T220 契约：{total, items, page, size}，无 nextBeforeId——两模式契约各自闭合；items 与游标模式同构）。 */
export interface RecommendationCardPageView {
  total: number;
  items: RecommendationCardItem[];
  page: number;
  size: number;
}

/** 反馈响应（{muteUntil?, escalated?}：DISLIKE 返回降频到期与是否升级）。 */
export interface RecommendationFeedbackResult {
  muteUntil: string | null;
  escalated: boolean | null;
}
