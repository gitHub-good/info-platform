// 事件流类型（M15 T127，对齐后端 EventStreamView——方案 §4.8 契约）。
// 枚举线格式 = 后端 Java 枚举 name()：EventType/Direction/Importance；
// 徽章中文展示复用 types/industryHeat 的 EVENT_TYPE_LABELS/DIRECTION_LABELS/IMPORTANCE_LABELS（同源镜像）。

/** 事件流查询参数（空值 = 不过滤；beforeId 游标 + limit 缺省 20 ≤50）。 */
export interface EventStreamQuery {
  type?: string;
  industry?: string;
  importance?: string;
  direction?: string;
  beforeId?: number;
  limit?: number;
}

/** 关键数字（来自原文，label + value + unit，禁编造）。 */
export interface EventFigure {
  label: string | null;
  value: string | null;
  unit: string | null;
}

/** 关联标的（code 非空 = 已回联标的池，可跳 #/subjects/:code；null = 未回联仅留名）。 */
export interface EventSubject {
  code: string | null;
  name: string | null;
  industry: string | null;
}

/** 事件卡片（字段面 = 方案 §4.8 冻结契约：id/eventType/summary/industries/direction/importance/figures/
 *  subjects/quote/newsId/newsTitle/newsUrl/eventTime）。 */
export interface EventCard {
  id: number;
  eventType: string;
  summary: string;
  industries: string[];
  direction: string;
  importance: string;
  figures: EventFigure[];
  subjects: EventSubject[];
  quote: string | null;
  newsId: number;
  newsTitle: string | null;
  newsUrl: string | null;
  eventTime: string | null;
}

/** 事件流视图（total 与分页同口径：无筛选 = event_item 全量，§4.10 对账）。 */
export interface EventStreamView {
  total: number;
  items: EventCard[];
  nextBeforeId: number | null;
}

/** 申万一级行业 31 枚举（后端 IndustryCategory.SW_INDUSTRIES 镜像，行业筛选下拉；容器 4 枚举不入选）。 */
export const SW_INDUSTRIES: readonly string[] = [
  '农林牧渔',
  '基础化工',
  '钢铁',
  '有色金属',
  '电子',
  '家用电器',
  '食品饮料',
  '纺织服饰',
  '轻工制造',
  '医药生物',
  '公用事业',
  '交通运输',
  '房地产',
  '商贸零售',
  '社会服务',
  '银行',
  '非银金融',
  '综合',
  '建筑材料',
  '建筑装饰',
  '电力设备',
  '机械设备',
  '国防军工',
  '计算机',
  '传媒',
  '通信',
  '煤炭',
  '石油石化',
  '环保',
  '美容护理',
  '汽车',
];
