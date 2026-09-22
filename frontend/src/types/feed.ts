// 个人信息流类型（T43，对齐后端 FeedItem/FeedListView/SubscriptionView——技术方案 §4.1.6）。

/** 条目类型：announce 公告 / news 新闻 / policy 政策（订阅命中）+ recommendation 每日推荐。 */
export type FeedItemType = 'announce' | 'news' | 'policy' | 'recommendation';

/** 信息流条目（FeedItem；id 为合成游标序号，仅供 React key 与游标分页用）。 */
export interface FeedItemView {
  id: number;
  type: FeedItemType;
  title: string;
  summary: string | null;
  /** 发布时间 ISO 串（推荐项取装配时刻）。 */
  publishedAt: string | null;
  /** 来源标签（如 gov.cn / 每日推荐）。 */
  source: string | null;
  /** 原文链接（推荐项为 null）。 */
  url: string | null;
  subjectCode: string | null;
  subjectName: string | null;
  /** 命中原因（订阅类型+key）；推荐项为「每日推荐」。 */
  matchReason: string | null;
  /** 命中订阅关键词（主题/政策主题为订阅词、标的为标的名、事件类型/推荐为空数组）。 */
  keywords: string[];
}

/** 信息流一页（游标分页；nextCursor 为 null 表示无下一页）。 */
export interface FeedPage {
  items: FeedItemView[];
  nextCursor: number | null;
}

/** 订阅条目（SubscriptionView；仅取 status 判断「有无活跃订阅」区分两种空态）。 */
export interface SubscriptionSummary {
  id: number;
  /** 1 主题 / 2 标的 / 3 事件类型 / 4 政策主题（后端 SubscriptionType code）。 */
  subType: number;
  subKey: string;
  channel: number;
  /** 1 订阅中 / 0 已退订（后端 SubscriptionStatus code）。 */
  status: number;
}
