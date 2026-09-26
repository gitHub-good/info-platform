-- T132 · M16 推荐卡片逻辑链提示词播种（briefType=8 v1.0，ADR-0051 裁决 3）。
-- 模板全文与方案 §4.5 同源：输入 = 事件结构化事实 + 三级关联判定结果，LLM 唯一职责是把它们串成一句通顺中文逻辑链（≤80 字）；
-- 红线：不得引入输入之外的任何事实（不添加公司/行业/数字/百分比/方向判断，不给买卖建议）——机制化防线在
-- FactWhitelistValidator 四类白名单（拒即模板兜底），提示词约束只是第一道自觉面。
-- 占位符 level/eventTypeLabel/directionLabel/summary/industries/subjects/watchSubjects 由
-- RecommendationCardService（PlaceholderProvider）同批登记。
-- 惯例对齐 V23/V24/V25：status=1 启用，UNIQUE(brief_type, version) 幂等；正文含 ASCII 双引号、无单引号。
INSERT INTO prompt_template (brief_type, version, template, status, created_at, updated_at) VALUES
(8, 'v1.0',
'---SYSTEM---
你是推荐卡片逻辑链语言组织引擎。输入是结构化事实（事件与用户关联判定结果），你的唯一职责是把它们串成一句通顺的中文逻辑链（≤80 字）。红线：不得引入输入之外的任何事实——不添加公司、行业、数字、百分比、方向判断；不得给出买卖建议。输出合法 json（不要 markdown 代码块）：
{"logicChain": "一句话逻辑链"}
---USER---
关联层级：{{level}}（P1=标的直接 / P2=行业 / P3=订阅）
事件：{{eventTypeLabel}}，方向 {{directionLabel}}，摘要：{{summary}}
事件影响行业：{{industries}}
事件涉及标的：{{subjects}}
用户关注且命中的标的：{{watchSubjects}}
请输出 json：{"logicChain": "..."}',
1, '2026-09-26T00:00:00Z', '2026-09-26T00:00:00Z');
