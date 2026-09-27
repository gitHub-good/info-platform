// 政策时事流类型（V2.3-M23 T201 契约换代：policy-scope-v1 news 背书——id = news_item.id）。
// 旧数据面字段随轨 B 退役移除：aiTendency（由 L2 政策发布事件 direction 承接）/ relatedIndustries /
// relatedSubjects（watchlist 口径）——对齐技术方案 §4.1/§4.2 与 ADR-0062。
//
// GET /api/v1/policies?days=&industry=&keyword=&sourceCode=&page=&size= → PolicyPagedView
// GET /api/v1/policies/{id}                                 → PolicyDetailView（含回联标的 + 关联事件）

import { SW_INDUSTRIES } from './eventStream';

/**
 * 行业筛选可选集（L1 口径，方案 §4.1）：申万 31 一级行业（后端 main 或 sub 命中）
 * + 「监管·政策」容器（仅 main）——后端 IndustryCategory 全量 35 枚举中可作筛选值的 32 个镜像。
 */
export const POLICY_INDUSTRY_OPTIONS: readonly string[] = [...SW_INDUSTRIES, '监管·政策'];

/** 回联标的（news_analysis.matched_subjects 元素，库内值直读：{code,name,industry}）。 */
export interface MatchedSubjectView {
  code: string;
  name: string | null;
  industry: string | null;
}

/**
 * 政策列表项（GET /policies 每条，id = news_item.id）。
 * publishedAt ISO-8601 UTC（gov.cn 纯日期归一上海当日 00:00）；mainCategory/subIndustry 为 L1 归类产物（可空）。
 */
export interface PolicyView {
  id: number;
  title: string;
  summary: string | null;
  url: string | null;
  sourceCode: string;
  sourceName: string;
  publishedAt: string | null;
  mainCategory: string | null;
  subIndustry: string | null;
  matchedSubjects: MatchedSubjectView[];
}

/** 政策列表页码分页视图（M9）。total 为筛选后精确总数；basis = 口径版本串（policy-scope-v1，追加式非破坏）。 */
export interface PolicyPagedView {
  policies: PolicyView[];
  total: number;
  page: number;
  size: number;
  basis: string;
}

/**
 * 关联 L2 政策发布事件（event_item WHERE news_id=:id，UNIQUE 至多一条）。
 * direction 即 ai_tendency 退役后的倾向承接面（BULLISH 利好 / BEARISH 利空 / NEUTRAL 中性，
 * trace-v1 事件流可下钻）。
 */
export interface RelatedEventView {
  id: number;
  eventType: string;
  summary: string | null;
  direction: string;
  importance: string;
  eventDate: string | null;
}

/** 政策详情视图（GET /policies/{id}，id = news_item.id）。无关联事件时 relatedEvents 为空数组。 */
export interface PolicyDetailView {
  id: number;
  title: string;
  summary: string | null;
  url: string | null;
  sourceCode: string;
  sourceName: string;
  publishedAt: string | null;
  mainCategory: string | null;
  subIndustry: string | null;
  matchedSubjects: MatchedSubjectView[];
  relatedEvents: RelatedEventView[];
}
