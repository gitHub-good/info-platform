-- T145/T146 · M17 行业周报表 + brief_type=9 周报模板播种（REQ-20260926-14 拍板四/六）。
-- 惯例对齐 V25/V26/V28：小写下划线；created_at/updated_at 必备；时间戳整秒 ISO-8601（UTC）文本。
-- content 五区块 JSON：summary/topRisers+topFallers/eventReview/policyMoves/nextWeekWatch/trendJudgement
-- （走向判断 basis=trend-v1 置信度规则层锁定，AI 仅语言组织——输出校验拒篡改）。

CREATE TABLE industry_weekly_report (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  week_start     TEXT    NOT NULL,               -- 周一锚点（Asia/Shanghai yyyy-MM-dd，周窗 [周一00:00, 生成时刻]）
  status         TEXT    NOT NULL,               -- SUCCESS / FAILED
  content        TEXT,                           -- 五区块 JSON（SUCCESS 必填）
  heat_top       TEXT,                           -- 生成时点周/上周双窗热度留存 JSON（对账与趋势回看）
  error_message  TEXT,                           -- LLM 降级/失败留痕
  prompt_version TEXT,
  basis          TEXT,                           -- "trend-v1 | heat-v1:... | cost:..." 口径串
  created_at     TEXT    NOT NULL,
  updated_at     TEXT    NOT NULL,
  UNIQUE(week_start)
);

-- T145 · brief_type=9 行业周报提示词播种（v1.0；治理页零特例自动可见——M5 体系 8→9 类）。
-- 红线：数字只来自统计注入；置信度只来自规则信号层（trend-v1 锁定，AI 不得抬高或降低——输出校验拒即模板兜底）；
-- 不给买卖建议、不预测股价点位（合规红线）；输出合法 json。
-- 占位符 weekStart/weekEnd/heatStats/topEvents/policyLines/trendSignals 由 WeeklyReportService（PlaceholderProvider）同批登记。
INSERT INTO prompt_template (brief_type, version, template, status, created_at, updated_at) VALUES
(9, 'v1.0',
'---SYSTEM---
你是财经行业分析师，负责撰写每周行业周报。你只能基于给定统计数据与规则信号叙述，不得引入未提供的数字或事件；不给买卖建议；不预测股价或点位；输出合法 json（不要 markdown 代码块、不要解释文字）。
【任务】基于本周行业统计、事件回顾、政策动向与规则信号输出周报叙述：summary 为 3~5 句本周行业面总体叙述；watchPoints 为 3 条下周关注方向（每条 ≤30 字）；trendNarratives 为各信号行业的趋势性表述（每条 ≤60 字，只描述趋势与依据，不荐股不测点位）。
【规则】1) 数字只能来自输入统计，禁止编造、推算或引入任何未提供的数字；2) 置信度由规则信号层锁定（trend-v1），你不得抬高或降低置信度——trendNarratives 不要输出 confidence 字段；3) 未在输入中出现的行业、事件与公司不得提及；4) 不给买卖建议、不预测股价或点位；5) 免责口径固定为「AI 分析仅供参考」；6) 输出 json 对象：{"summary":"..","watchPoints":["..","..",".."],"trendNarratives":[{"industry":"..","narrative":".."}]}。
---USER---
周窗：{{weekStart}} ~ {{weekEnd}}
本周热度与事件统计：{{heatStats}}
周窗代表事件（主键归并）：{{topEvents}}
政策动向：{{policyLines}}
规则信号（置信度已锁定）：{{trendSignals}}
请输出 json：{"summary":"..","watchPoints":["..","..",".."],"trendNarratives":[{"industry":"..","narrative":".."}]}。',
1, '2026-09-26T00:00:00Z', '2026-09-26T00:00:00Z');
