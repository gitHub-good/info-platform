// 资讯库类型（M19 T161，对齐后端 NewsItemView/NewsItemsPagedView——T160 契约：
// GET /api/v1/news-items 页码模式增量 q/l0/l1 过滤 + analysis join 字段）。
import { SW_INDUSTRIES } from './eventStream';

/** L0 状态线格式（news_analysis.l0_result；无 analysis 行后端兜底 PASS）。 */
export type NewsL0Result = 'PASS' | 'NOISE' | 'NEAR_DUP';

/** l0 过滤参数（API 取值；ALL = 不过滤三态混合）。 */
export type NewsL0Filter = NewsL0Result | 'ALL';

/** 回联标的（news_analysis.matched_subjects 元素，V3.1——后端 MatchedSubjectView 镜像；industry 无值为 null）。 */
export interface MatchedSubject {
  code: string;
  name: string;
  industry: string | null;
}

/** 资讯库条目视图（GET /api/v1/news-items 每条；publishedAt/fetchedAt 为 ISO-8601 整秒文本）。 */
export interface NewsLibraryItem {
  id: number;
  sourceId: number;
  sourceCode: string | null;
  sourceName: string | null;
  title: string;
  summary: string | null;
  url: string | null;
  author: string | null;
  publishedAt: string;
  fetchedAt: string;
  /** L0 状态（滞留条目无 analysis 行 → 后端兜底 PASS）。 */
  l0Result: NewsL0Result;
  /** 诊断（NOISE 命中规则名 / NEAR_DUP 海明距离+编辑距离）。 */
  l0Detail: string | null;
  /** 主分类（35 枚举；null = 未分类灰态）。 */
  l1Main: string | null;
  /** L1 置信度 0~1。 */
  l1Confidence: number | null;
  /** 低置信兜底旗标（阈值运行时可配，前端不可复算——直读库内值）。 */
  lowConfidence: boolean;
  /** 近重复主条 news_id。 */
  nearDupMasterId: number | null;
  /** 主条原文 url（主条被清理为 null → 「主条」链接降级隐藏）。 */
  nearDupMasterUrl: string | null;
  /** 回联标的清单（V3.1；无关联标的 = 空数组，后端保证非 null）。 */
  matchedSubjects: MatchedSubject[];
}

/** 页码模式视图（M9 PageQuery 模式：items + total + page/size 回显）。 */
export interface NewsLibraryPagedView {
  items: NewsLibraryItem[];
  total: number;
  page: number;
  size: number;
}

/** L1 主分类跨行业容器 4 枚举（后端 IndustryCategory.CONTAINERS 镜像）。 */
const L1_CONTAINERS: readonly string[] = ['宏观', '监管·政策', '国际', '市场·其他'];

/** L1 主分类 35 枚举（31 申万一级 + 4 容器，后端 IndustryCategory 全量镜像——L1 下拉前端硬编码，REQ 拍板一）。 */
export const L1_MAIN_CATEGORIES: readonly string[] = [...SW_INDUSTRIES, ...L1_CONTAINERS];
