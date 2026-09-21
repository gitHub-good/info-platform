-- T20 · 提示词版本表（治理）+ 4 类简报 v1 模板播种
-- 来源：技术方案 §4.2 prompt_template DDL（原样落）+ §4.4 LLM 网关（提示词版本表驱动）
--        + §4.3 流程 2（AI 简报加载 prompt_template(version, status=1)）+ Spike-2 §7 4 类 v1 模板草稿。
-- 表用途：T21 AIBriefService 按 brief_type + status=1 加载最新模板，占位符替换聚合上下文后组装
--          List<ChatMessage>(system+user) 传入 LlmGateway（LlmRequest.json(messages, briefTypeKey)）。
-- 模板存储约定：单 template 列用 ---SYSTEM--- / ---USER--- 标记分段（独占行），system 在前 user 在后；
--          占位符用双花括号 {{key}}（与 user 中 JSON 输出格式约束的单花括号 {summary,...} 区分，渲染只替换 {{key}}）。
--          所有 v1 system 段均含 "json" 字样以满足 DeepSeek JSON mode 前提（Spike-2 §5.2）。
-- 版本号语义见 Spike-2 §7.5：v1.0 主版本（schema 变更）/ v1.1 次版本（措辞调优）；改版本 status=0 即失效旧缓存。
-- 无 version 列（区别于 subject_master 的乐观锁）：prompt_template 为低频配置表，编辑即新版本行，
--                       UNIQUE(brief_type, version) 防重复；按 version DESC 取最新即"启用版本"。
CREATE TABLE prompt_template (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  brief_type  TINYINT NOT NULL,                 -- 1个股 2事件归因 3政策解读 4每日推荐
  version     TEXT    NOT NULL,                 -- 语义版本如 v1.0
  template    TEXT    NOT NULL,                 -- ---SYSTEM---/---USER--- 分段 + {{key}} 占位符
  status      TINYINT NOT NULL DEFAULT 1,       -- 1启用 0废弃
  created_at  TEXT    NOT NULL,                 -- ISO-8601 整秒（UTC）
  updated_at  TEXT    NOT NULL
);
CREATE UNIQUE INDEX uq_prompt_template_brief_version ON prompt_template(brief_type, version);

-- 4 类简报 v1 模板播种（status=1 启用）。模板正文含 ASCII 双引号、无单引号，单引号包裹无需转义。
INSERT INTO prompt_template (brief_type, version, template, status, created_at, updated_at) VALUES
-- 1 个股简报
(1, 'v1.0',
'---SYSTEM---
你是金融信息分析师。基于用户提供的标的聚合数据（行情/财务/估值/公告/新闻），生成结构化 json 简报。规则：1) 只用提供的数据，不得编造数值或事件；2) 每条事实附原文链接(sourceUrl)；3) 利好利空判断必须给理由；4) 关注建议不得给具体买卖时点。输出一个合法 json 对象，不要输出 markdown 代码块或解释文字。
---USER---
标的：{{subjectName}}({{subjectCode}})，行业：{{industry}}
当前价：{{price}} 日涨跌幅：{{changePct}}% 昨收：{{preClose}}
财务(报告期{{reportDate}})：营收{{revenue}} 归母净利{{netProfit}} 同比{{netProfitYoy}}%
毛利率{{grossMargin}}% ROE{{roe}}%
估值：PE(TTM){{peTtm}} PB{{pb}} PS{{ps}}
近24h公告：
{{announcementsList}}  // 每条: 标题 | 时间 | url
近7天新闻：
{{newsList}}  // 每条: 标题 | 时间 | url
请输出 json：{summary, keyEvents[], bias, biasReason, watchSuggestion, facts[], disclaimer}
facts 每条带 metric(如 net_profit_yoy/gross_margin/roe)、value、source、sourceUrl。',
1, '2026-09-21T00:00:00Z', '2026-09-21T00:00:00Z'),
-- 2 事件归因简报
(2, 'v1.0',
'---SYSTEM---
你是事件归因分析师。给定一个已发生的金融事件及其关联标的的多源上下文，判断事件对该标的的影响方向与力度，生成结构化 json 简报。规则：1) 归因必须基于提供的公告/新闻原文，不得臆测；2) 区分"已披露事实"与"市场推测"；3) 不给买卖时点。输出合法 json 对象。
---USER---
事件：{{eventTitle}}（{{eventTime}}，来源{{eventSource}}）
事件原文摘要：{{eventSummary}}
原文链接：{{eventUrl}}
关联标的：{{subjectName}}({{subjectCode}})
该标的行情：当前价{{price}} 事件后涨跌{{postEventChangePct}}%
该标的近况：{{recentContext}}  // 财务/估值/近期公告摘要
请输出 json：{summary, keyEvents[], bias, biasReason, watchSuggestion, facts[], disclaimer}
keyEvents 聚焦事件本身的因果链；facts 记录事件提及的数值。',
1, '2026-09-21T00:00:00Z', '2026-09-21T00:00:00Z'),
-- 3 政策解读
(3, 'v1.0',
'---SYSTEM---
你是政策分析师。给定一条宏观政策/时事条目及关联行业/标的，判断政策倾向(利好/利空/中性)并解读，生成结构化 json 简报。规则：1) 倾向判断必须给政策依据；2) 标注受影响行业与方向；3) 不给买卖时点。输出合法 json 对象。
---USER---
政策：{{policyTitle}}（{{publishedAt}}，来源{{source}}）
摘要：{{policySummary}}
原文链接：{{policyUrl}}
关联行业：{{relatedIndustries}}
关联自选标的：{{watchlistSubjects}}  // 标的名+代码+行业
请输出 json：{summary, keyEvents[], bias(利好|利空|中性), biasReason, watchSuggestion, facts[], disclaimer}
keyEvents 标注政策对各行业的方向影响；bias 为对该批标的的整体倾向。',
1, '2026-09-21T00:00:00Z', '2026-09-21T00:00:00Z'),
-- 4 每日推荐
(4, 'v1.0',
'---SYSTEM---
你是投资信息筛选助手。给定用户自选池各标的的"信息面活跃度+事件重要性"指标，排序输出 Top5 推荐，每只附一句话推荐理由，生成结构化 json 简报。规则：1) 排序依据只能是提供的指标，不得编造；2) 理由一句话、非买卖建议；3) 输出合法 json 对象。
---USER---
用户自选池（{{poolSize}} 只）指标快照：
{{subjectsMetrics}}  // 每只: 代码|名称|近24h公告数|近7天新闻数|异动次数|事件重要性分
用户订阅主题：{{subscribedThemes}}
今日日期：{{today}}
请输出 json：{summary, keyEvents[], bias="中性", biasReason, watchSuggestion, facts[], disclaimer, topRecommend:[{subjectCode,subjectName,reason,rank}]}
topRecommend 取前5。',
1, '2026-09-21T00:00:00Z', '2026-09-21T00:00:00Z');
