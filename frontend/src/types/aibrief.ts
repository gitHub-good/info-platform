// AI 简报接口契约（对齐技术方案 §4.1.4 + 后端 T21 AIBriefView/BriefContent/BriefFact）。
// POST /api/v1/ai-briefs（Idempotency-Key, body{subjectId,briefType}）→ 202 { data:{ taskId } }
// GET  /api/v1/ai-briefs/{taskId} → 200 { data:{ status, content, sourceLinks, disclaimer } } | 404(30032)
//
// 关键点：
// - status：0 处理中 / 1 完成 / 2 失败 / 3 待核实（数值校验不符降级，content 仍可展示）。
// - content 是结构化对象（非 JSON 字符串），仅 status=1/3 非空；0/2 为 null。
// - sourceLinks 是 URL 字符串数组（顶层），非事实对象；带 metric/value/source/sourceUrl 的
//   事实回链条目在 content.facts[]（与 PRD「每条 metric/value/source/sourceUrl」对应）。

/** 简报类型：1 个股 / 2 事件归因 / 3 政策解读 / 4 每日推荐。 */
export type BriefTypeCode = 1 | 2 | 3 | 4;

/** 简报状态：0 处理中 / 1 完成 / 2 失败 / 3 待核实。 */
export type BriefStatusCode = 0 | 1 | 2 | 3;

/** 终态（不再变更）：1 完成 / 2 失败 / 3 待核实。0 为处理中，前端据此停止轮询。 */
export function isTerminalStatus(status: BriefStatusCode): boolean {
  return status !== 0;
}

/** 关键事件条目（content.keyEvents[]）。 */
export interface BriefKeyEvent {
  /** 事件描述 */
  event: string;
  /** 影响方向：利好 / 利空 / 中性 */
  impact?: string;
  /** 判断理由 */
  reason?: string;
  /** 原文链接（事实回链） */
  sourceUrl?: string;
}

/** 事实条目（content.facts[]）—— 幻觉校验 + 事实回链的载体。 */
export interface BriefFact {
  /** 人类可读的事实陈述，如「归母净利润同比增长15%」 */
  claim?: string;
  /** 指标键，与数据源字段键对齐（如 roe/gross_margin/net_profit） */
  metric?: string;
  /** 数值（可空，定性事实不带数值则不参与幻觉校验） */
  value?: number | null;
  /** 数据源标识，如 FINANCE/VALUATION */
  source?: string;
  /** 原文链接（事实回链，回链率 100%） */
  sourceUrl?: string;
}

/** 结构化简报内容（GET 响应 content，仅 status=1/3 非空）。 */
export interface BriefContent {
  /** 核心摘要 */
  summary?: string;
  /** 关键事件 */
  keyEvents?: BriefKeyEvent[];
  /** 整体倾向：利好 / 利空 / 中性 */
  bias?: string;
  /** 倾向理由 */
  biasReason?: string;
  /** 关注建议（非投资建议） */
  watchSuggestion?: string;
  /** 事实数组（幻觉校验 + 回链） */
  facts?: BriefFact[];
  /** 免责声明（content 内冗余一份，应用层兜底为「AI 生成，非投资建议」） */
  disclaimer?: string;
}

/** GET /ai-briefs/{taskId} 响应 data。 */
export interface AiBriefView {
  status: BriefStatusCode;
  /** 结构化简报（status=0/2 时为 null） */
  content: BriefContent | null;
  /** 事实回链 URL 数组（status=0/2 时为 null） */
  sourceLinks: string[] | null;
  /** 免责声明（恒附「AI 生成，非投资建议」） */
  disclaimer: string;
}

/** POST /ai-briefs 响应 data。 */
export interface CreateBriefResult {
  taskId: number;
}

/** POST /ai-briefs 请求体。subjectId 可空（每日推荐型无单一标的）。 */
export interface CreateBriefRequest {
  subjectId?: number | null;
  briefType: BriefTypeCode;
}
