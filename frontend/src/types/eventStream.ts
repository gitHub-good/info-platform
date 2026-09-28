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

// —— 页码模式（M25 T224 消费 T220 契约：GET /api/v1/events?page=&size=） ——

/** 页码模式查询（page 出现即页码模式 offset 语义；与 beforeId 互斥 400——两模式不同用）。 */
export interface EventStreamPageQuery {
  type?: string;
  industry?: string;
  importance?: string;
  direction?: string;
  /** 页码（1 起；上限 100 万防 offset 溢出）。 */
  page: number;
  /** 页大小（缺省 20 上限 50，越界后端拒绝不截断）。 */
  size: number;
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

/** 事件流页码视图（M25 T220：{total, items, page, size}，无 nextBeforeId——两模式契约各自闭合；items 与游标模式同构）。 */
export interface EventStreamPageView {
  total: number;
  items: EventCard[];
  page: number;
  size: number;
}

// —— 行业影响链（M17 T144，对齐后端 ImpactChainView——GET /api/v1/events/{id}/impact-chains 契约） ——

/** 影响链依据回溯（信号来源条目 id 集 / 事件结构化字段 / 原文引用）。 */
export interface ImpactChainBasis {
  newsId?: number;
  signalNewsIds?: number[];
  quote?: string | null;
  [key: string]: unknown;
}

/** 单链行（行业 / 方向 / 逻辑链 / 依据 / 模板键 / 缓存态 / 生成方式）。 */
export interface ImpactChainItem {
  id: number;
  industry: string;
  direction: string;
  logicChain: string;
  basis: ImpactChainBasis | null;
  templateKey: string;
  cacheState: 'AUTO' | 'ON_DEMAND';
  genMethod: 'TEMPLATE';
}

/** 影响链视图（eligibility：AUTO 落库自动 / ON_DEMAND 按需生成 / CACHED 已缓存 / LOW_SKIPPED 低重要度空态）。 */
export interface ImpactChainView {
  eventId: number;
  importance: string;
  eligibility: 'AUTO' | 'ON_DEMAND' | 'CACHED' | 'LOW_SKIPPED';
  chains: ImpactChainItem[];
  disclaimer: string;
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
