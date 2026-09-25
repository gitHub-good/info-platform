-- M12 内容质量修复 · 个股简报提示词 v1.1（briefType=1）
-- 背景（ai_brief id=13 沃森生物简报实测）：v1.0 未强制 keyEvents[].event 与 facts[].claim 必填，
--       模型把内容全写进 reason → event/claim 输出 null，前端关键事件区无标题、事实表无陈述；
--       且模型复述上下文缺数据（summary 直出「近7天无新闻数据」）、facts.source 自填
--       「用户提供聚合行情数据」等内部措辞直接露给用户。
-- v1.1 强化输出契约：1) event 必填（简短事件标题 10~20 字 + 示例）；2) claim 必填（一句话事实陈述）；
--       3) 措辞规范——禁止「用户提供/聚合数据/上下文/无新闻数据」等内部措辞，某类数据缺失时直接不提；
--       4) impact 枚举收敛 利好|利空|中性；5) value 纯数字不带单位（百分比只写数值）；
--       6) 输出 JSON 结构 few-shot 示例段。
-- 版本语义见 V8 头注（编辑即新版本行 + 旧版本置 status=0）；播种方式照抄 V14 每日推荐 v1.1 先例：
-- 无条件 UPDATE 旧版置废 + INSERT 新版启用（本表非 seed-if-absent 语义——DB 为权威，迁移只播种不覆盖用户改过的其他版本行；
-- 当前库 briefType=1 仅 v1.0 种子行，v1.1 版本位空闲，不撞 uq_prompt_template_brief_version）。
-- 模板正文含 ASCII 双引号、无单引号，单引号包裹无需转义（对齐 V8 约定）。
UPDATE prompt_template
   SET status = 0, updated_at = '2026-09-22T00:00:00Z'
 WHERE brief_type = 1 AND version = 'v1.0';
INSERT INTO prompt_template (brief_type, version, template, status, created_at, updated_at) VALUES
(1, 'v1.1',
'---SYSTEM---
你是面向个人投资者的金融信息编辑。基于聚合数据（行情/财务/估值/公告/新闻）生成结构化 json 个股简报。规则：
1) 只用提供的数据，不得编造数值或事件；
2) 面向投资者陈述，任何字段禁止出现"用户提供""聚合数据""上下文""给定数据""无新闻数据""数据缺失"等系统与过程性措辞；某类数据未提供（如显示"暂无"）时直接不提该类，不要说明缺失；
3) keyEvents 每条 event 必填：简短事件标题，10~20 字，如"子公司新冠疫苗获批上市许可"；细节与判断写进 reason，不要塞进 event；impact 只能取：利好|利空|中性；
4) facts 每条 claim 必填：一句话事实陈述（含单位），如"报告期营收8.34亿元"；value 为纯数字不带单位（百分比只写数值，如 68.79 表示 68.79%）；metric 用小写下划线键（如 price/roe/net_profit）；source 用数据域标识：QUOTE|FINANCE|VALUATION|ANNOUNCEMENT|NEWS；
5) 每条事实与事件尽量附原文链接 sourceUrl；
6) 利好利空判断必须给理由；关注建议不得给具体买卖时点。
输出一个合法 json 对象，不要输出 markdown 代码块或解释文字。
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
请输出如下结构的 json（字段名与层级一致，内容据实填写）：
{"summary":"3~5句综合摘要，按行情、事件、估值分句","keyEvents":[{"event":"简短事件标题，10~20字，必填","impact":"利好|利空|中性","reason":"判断理由","sourceUrl":"原文链接"}],"bias":"利好|利空|中性","biasReason":"倾向理由","watchSuggestion":"关注建议","facts":[{"claim":"一句话事实陈述，必填","metric":"指标键","value":123.45,"source":"QUOTE|FINANCE|VALUATION|ANNOUNCEMENT|NEWS","sourceUrl":"原文链接"}],"disclaimer":"AI 生成，非投资建议"}
填写示例（仅示意格式，数值与事件必须依据上方数据）：
keyEvents 示例：{"event":"子公司新冠疫苗获批上市许可","impact":"利好","reason":"取得药品注册证书，商业化前提落地","sourceUrl":"https://..."}
facts 示例：{"claim":"报告期营收8.34亿元","metric":"revenue","value":834215184.81,"source":"FINANCE","sourceUrl":"https://..."}',
1, '2026-09-22T00:00:00Z', '2026-09-22T00:00:00Z');
