// 标的详情聚合接口契约 —— 对应技术方案 §4.1.1
// GET /api/v1/subjects/{subjectId}/detail?sections=...
// 统一响应体 { code, msg, data, traceId }，data 结构见 SubjectDetailData

/** 单分区数据源状态：正常 / 缺失 / 获取失败 / 响应超时 */
export type SourceStatus = 'ok' | 'missing' | 'failed' | 'timeout';

/** 分区代码 = sourceStatus 的 key（与数据源 source_code 一致；event 为 ADR-0013 新增） */
export type SectionCode =
  | 'quote'
  | 'finance'
  | 'valuation'
  | 'announce'
  | 'news'
  | 'policy'
  | 'event';

/** 每分区的数据源状态标注 */
export type SourceStatusMap = Record<SectionCode, SourceStatus>;

/** 标的类型：1股票 2指数 3板块 4基金(预留) 5债券(预留) */
export type SubjectType = 1 | 2 | 3 | 4 | 5;

export type SubjectMarket = 'A_SHARE' | 'HK' | 'INDEX' | 'SECTOR';

export interface Subject {
  /** 内部统一代码，如 SH600519 */
  subjectCode: string;
  name: string;
  market: SubjectMarket;
  type: SubjectType;
  /** 行业 / 概念 */
  industry?: string;
}

/** 行情分区 */
export interface Quote {
  price: number;
  /** 日涨跌幅（%） */
  changePct: number;
  open?: number;
  high?: number;
  low?: number;
  preClose?: number;
  /** 成交量（股） */
  volume?: number;
  /** 成交额（元） */
  amount?: number;
  /** 换手率（%） */
  turnoverRate?: number;
  /** 振幅（%） */
  amplitude?: number;
  source?: string;
  updatedAt?: string;
}

/** 财务分区 */
export interface Finance {
  /** 营业收入（元） */
  revenue?: number;
  /** 净利润（元） */
  netProfit?: number;
  /** 毛利率（%）——mock 源键 */
  grossMargin?: number;
  /** 毛利率（%）——真实源键（eastmoney XSMLL 映射），展示时与 grossMargin 互为兜底 */
  grossProfitMargin?: number;
  /** 净资产收益率 ROE（%） */
  roe?: number;
  /** 报告期，如 2026 年中报（mock 源键） */
  reportPeriod?: string;
  /** 报告日期（ISO，真实源键 REPORT_DATE 映射），展示时与 reportPeriod 互为兜底 */
  reportDate?: string;
  source?: string;
  updatedAt?: string;
}

/** 估值分区 */
export interface Valuation {
  /** 市盈率（mock 源键） */
  pe?: number;
  /** 市盈率 TTM（真实源键，eastmoney f162 映射），展示时与 pe 互为兜底 */
  peTtm?: number;
  /** 市净率 */
  pb?: number;
  source?: string;
  updatedAt?: string;
}

/** 公告条目 */
export interface Announcement {
  id?: string;
  title: string;
  publishedAt: string;
  /** 分类：定期报告 / 权益变动 / 股东大会 等 */
  category?: string;
  url?: string;
  source?: string;
}

/** 新闻条目 */
export interface NewsItem {
  id?: string;
  title: string;
  publishedAt: string;
  summary?: string;
  url?: string;
  source?: string;
}

/** 政策条目 */
export interface PolicyItem {
  id?: string;
  title: string;
  publishedAt: string;
  /** 发文单位 */
  publisher?: string;
  url?: string;
  source?: string;
}

/** 异动类型（anomaly_event.anomaly_type，ADR-0013） */
export type AnomalyType = 'PRICE_CHANGE' | 'VOLUME' | 'EVENT';

/** 事件分区条目（本地 anomaly_event 近期异动/事件记录） */
export interface EventItem {
  /** 异动类型枚举名，展示标签由前端映射 */
  anomalyType: AnomalyType | string;
  /** 触发时涨跌幅（%），非涨跌幅类型时缺省 */
  changePct?: number;
  /** 触发时现价，可缺省 */
  currentPrice?: number;
  /** 触发时刻（ISO-8601，UTC） */
  triggerTime: string;
  /** 人读详情 */
  detail?: string;
}

/** §4.1.1 响应 data：subject 基本信息 + 各分区 + sourceStatus */
export interface SubjectDetailData {
  subject: Subject;
  quote?: Quote | null;
  finance?: Finance | null;
  valuation?: Valuation | null;
  announcements?: Announcement[] | null;
  news?: NewsItem[] | null;
  policies?: PolicyItem[] | null;
  /** 事件分区（ADR-0013）：本地 anomaly_event 近期异动/事件 */
  events?: EventItem[] | null;
  sourceStatus: SourceStatusMap;
}

/** 后端统一响应体 { code, msg, data, traceId } */
export interface ApiResponse<T> {
  code: number;
  msg: string;
  traceId?: string;
  data: T;
}
