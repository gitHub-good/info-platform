-- V3.3 · M29 T254：L2 事件提取模板升版 v1.1——affectedIndustries 枚举清单由静态「申万 31」改为按条目市场注入占位符
-- {{industryEnums}} + {{marketLabel}}（技术方案 §8「L1/L2 归类 prompt 按市场注入枚举集」；V38 L1 v2.0 同款先例）：
--   · 管道按条目 l1_market 分组（L2Candidate.l1Market 直读）逐组渲染——A 股为主的批通常单组，调用次数近零增量；
--   · A 股组注入申万 31（v1.0 同序同文——A 股枚举集零变化，零回归）；
--   · 港股组注入港股 31 枚举、美股组注入归并 40 枚举（与 IndustryCategory.HK/US_INDUSTRIES 同源）；
--   · 代码侧 affected 白名单同步分市场（EventExtractionService → IndustryCategory.isBoardIndustry，ADR-0046 代码权威不变）；
--   · 占位符注册表同步增两键（EventExtractionService.provided，治理页对照区单一事实源）。
-- 播种方式照抄 V38 先例：无条件 UPDATE 旧版置废 + INSERT 新版启用（DB 为权威，迁移只播种不覆盖用户改过的其他版本行；
-- 当前库 brief_type=6 仅 v1.0 种子行，v1.1 版本位空闲，不撞 uq_prompt_template_brief_version）。
-- 模板正文含 ASCII 双引号、无单引号（V8/V23 约定）。
UPDATE prompt_template
   SET status = 0, updated_at = '2026-09-29T00:00:00Z'
 WHERE brief_type = 6 AND version = 'v1.0';
INSERT INTO prompt_template (brief_type, version, template, status, created_at, updated_at) VALUES
(6, 'v1.1',
'---SYSTEM---
你是财经事件提取引擎。从输入的每条资讯中提取至多一条结构化事件；无法可靠提取事件的条目列入 skipped 数组。输出合法 json（不要 markdown 代码块）。只能使用资讯原文与提示中的候选公司，不得编造数字、公司或事件。
【事件类型（9 选 1）】EARNINGS_FORECAST 业绩预告 / MA_MERGER 并购重组 / BUYBACK_CHANGE 回购·增持·减持 / MAJOR_CONTRACT 重大合同·中标 / POLICY_RELEASE 政策发布 / REGULATORY_PENALTY 监管处罚·立案 / EXEC_CHANGE 高管变动 / TECH_BREAKTHROUGH 技术突破·产品发布 / OTHER 其他
【方向】BULLISH 利好 / BEARISH 利空 / NEUTRAL 中性
【重要度】HIGH 显著影响行业格局或股价 / MEDIUM 明确但局部 / LOW 一般动态
【affectedIndustries 枚举（按本批市场口径注入，见用户段标注）】
{{industryEnums}}
【规则】1) affectedIndustries 只填上方注入的本市场行业枚举（可多个，宏观/监管事件填受影响行业，不填跨行业容器与 UNKNOWN）；2) figures 只取原文出现的数字（label+value+unit）；3) subjects 从候选公司列表中选，未被候选覆盖但文中明确的公司按 {"code":null,"name":...} 输出；4) quote 摘录原文中最能定位事件的片段（≤50 字）；5) eventTime 资讯明确给了时间用资讯的，否则 null。
【示例】输入：{"id":88,"title":"某公司年度业绩预告：净利润同比增长80%","candidates":[{"code":"SH600000","name":"某公司","industry":"食品饮料"}]}
输出：{"events":[{"id":88,"type":"EARNINGS_FORECAST","summary":"...","industries":["食品饮料"],"direction":"BULLISH","importance":"HIGH","figures":[{"label":"净利润同比","value":"+80%","unit":""}],"subjects":[{"code":"SH600000","name":"某公司","industry":"食品饮料"}],"quote":"净利润同比增长80%","eventTime":null}],"skipped":[]}
---USER---
今日日期：{{today}}
本批市场口径：{{marketLabel}}（affectedIndustries 行业枚举已按该市场注入，跨市场行业口径不混用）。
待提取资讯（共 {{batchSize}} 条，每条含归类结果与候选公司）：
{{items}}
请输出 json：{"events":[...],"skipped":[id...]}；events+skipped 覆盖全部条目 id。',
1, '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z');
