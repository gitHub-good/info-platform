// 资讯脉搏类型（V3.2 M28，对齐后端 NewsPulseRepository.PulseRow / NewsPulseController）。
// GET /api/v1/news-pulse → {windowKey, label, latest}[]；GET /{window} → latest；
// POST /{window}/refresh → 新快照（5min 最小间隔守卫）。

import type { NewsPulseWindowKey } from '@/api/newsPulse';

/** L1 行业分布统计（industry_stats JSON 元素）。 */
export interface PulseIndustryCount {
  industry: string;
  count: number;
}

/** 市场归集统计（market_stats JSON 元素）。 */
export interface PulseMarketStat {
  market: string;
  newsCount: number;
  topSubjects: string[];
}

/** LLM 结构化分析（analysis JSON；LLM 降级时为 null）。 */
export interface PulseAnalysis {
  overview: { A股: string; 港股: string; 美股: string };
  keyEvents: Array<{
    title: string;
    importance: number;
    markets: string[];
    industries: string[];
    concepts: string[];
    subjects: string[];
    summary: string;
  }>;
  hotTracks: Array<{
    name: string;
    type: string;
    markets: string[];
    newsCount: number;
    summary: string;
  }>;
  sentiment: { A股: string; 港股: string; 美股: string };
}

/** 分析快照（表 news_pulse_analysis 全列投影；ISO-8601 时间为文本）。 */
export interface PulseRow {
  id: number;
  windowKey: string;
  windowStart: string;
  windowEnd: string;
  newsCount: number;
  classifiedCount: number;
  industryStats: string;
  marketStats: string;
  analysis: string | null;
  model: string | null;
  promptVersion: string | null;
  triggerSource: string;
  degraded: boolean;
  degradedReason: string | null;
  createdAt: string;
}

/** 六窗总览元素。 */
export interface PulseWindowView {
  windowKey: NewsPulseWindowKey;
  label: string;
  latest: PulseRow | null;
}
