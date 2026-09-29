-- V3.2 · 资讯脉搏（M28）：多时间窗 AI 事件分析（30m/1h/3h/6h/12h/24h）。
-- 表① news_pulse_analysis：版本化分析快照（规则统计恒产出 + LLM 结构化分析可降级）；
-- brief_type=11 播种 NEWS_PULSE v1.0 模板（治理页零特例自动可见——M5 体系 10→11 类）。
-- 红线：LLM 只做输入资讯的语言组织与归纳，只能引用输入出现过的行业/概念/标的；
-- 不得出现投资指令或收益承诺——本输出是信息整理与风险提示，不构成投资建议。

CREATE TABLE IF NOT EXISTS news_pulse_analysis (
  id               INTEGER PRIMARY KEY AUTOINCREMENT,
  window_key       TEXT    NOT NULL,             -- 30m/1h/3h/6h/12h/24h（NewsPulseWindow.code）
  window_start     TEXT    NOT NULL,             -- ISO-8601（分析窗口起点 = 触发时刻 - 窗口时长）
  window_end       TEXT    NOT NULL,             -- ISO-8601（分析截止 = 触发时刻）
  news_count       INTEGER NOT NULL DEFAULT 0,   -- 窗口内 PASS 条目数（规则统计分子分母同源）
  classified_count INTEGER NOT NULL DEFAULT 0,   -- 其中 L1 DONE 数（分类覆盖率）
  industry_stats   TEXT    NOT NULL,             -- JSON [{"industry","count"}] L1 规则统计降序
  market_stats     TEXT    NOT NULL,             -- JSON [{"market","newsCount","topSubjects":["名称(代码)"]}] 标的回联市场归集
  analysis         TEXT,                         -- LLM 结构化 JSON（overview/keyEvents/hotTracks/sentiment；失败 NULL 纯统计降级）
  model            TEXT,                         -- 实际命中模型（降级留 NULL）
  prompt_version   TEXT,                         -- 产出所用模板版本
  trigger_source   TEXT    NOT NULL,             -- JOB / MANUAL
  degraded         TINYINT NOT NULL DEFAULT 0,   -- 1 = LLM 段降级（纯统计版可用）
  degraded_reason  TEXT,
  created_at       TEXT    NOT NULL,
  updated_at       TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_pulse_window_time ON news_pulse_analysis(window_key, created_at);

INSERT INTO prompt_template (brief_type, version, template, status, created_at, updated_at) VALUES
(11, 'v1.0',
'---SYSTEM---
你是多市场资讯脉搏分析引擎。基于一个时间窗内的结构化资讯条目（每条含 L1 分类与关联标的），归纳近期发生的最新事件并输出 json（不要 markdown 代码块）。
【红线】只能基于输入条目归纳，不得编造条目外的事件/数字/结论；industries 只能取输入出现过的 L1 分类，subjects 只能取输入出现过的标的名，concepts 限输入标题中真实出现的概念词；不得出现任何投资指令或收益承诺（禁止「买入/卖出/必涨/稳赚/目标价」等表述）——本输出是信息整理，不构成投资建议。
【输出 schema】{"overview":{"A股":"≤100字","港股":"≤100字","美股":"≤100字"},"keyEvents":[{"title":"≤40字","importance":1,"markets":["A股"],"industries":["电子"],"concepts":["AI芯片"],"subjects":["海光信息"],"summary":"≤80字"}],"hotTracks":[{"name":"≤12字","type":"行业","markets":["A股"],"newsCount":1,"summary":"≤60字"}],"sentiment":{"A股":"中性","港股":"中性","美股":"中性"}}
【规则】1) keyEvents 恰 3~8 条按重要性降序，importance 取 1~5 整数；2) hotTracks 恰 3~6 条（type 只取「行业」或「概念」），newsCount 为输入中该方向条目数（不得编造）；3) markets 只取 A股/港股/美股 且必须是输入出现过的市场；4) sentiment 只取 偏多/中性/偏空；5) 无港股/美股条目时对应 overview 填「窗口内无相关资讯」、sentiment 填「中性」。
---USER---
时间窗：{{window}}（{{windowStart}} ~ {{windowEnd}}）
窗口内条目 {{newsCount}} 条，其中已分类 {{classifiedCount}} 条。
L1 行业分布：{{industryStats}}
市场归集：{{marketStats}}
资讯条目（按重要性降序，最多 {{maxItems}} 条）：
{{items}}
请输出 json。',
1, '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z');
