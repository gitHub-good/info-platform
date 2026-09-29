-- V3.3 · M29 T253：L1 归类模板升版 v2.0——主分类枚举清单由静态 35 项改为按条目市场注入占位符 {{industryEnums}}
-- （技术方案 §3.2 / ADR-0064 裁决 2「L1 按条目市场注入枚举集」）：
--   · 管道按条目市场分组（l1_market 由 matched_subjects 主市场派生，无标的 → A_SHARE 容器面）逐组渲染；
--   · A 股条目注入申万 31（v1.0 同序同文——A 股枚举集零变化，零回归）；
--   · 港股条目注入港股 31 枚举 + UNKNOWN、美股条目注入归并 40 枚举 + UNKNOWN（与 IndustryCategory.HK/US_INDUSTRIES 同源）；
--   · 容器 4 项跨市场共用恒注入（模板正文静态）；输出协议与校验不变（代码侧 IndustryCategory 白名单权威，ADR-0046），
--     新增 {@code {{marketLabel}}} 用户段市场口径标注。
-- 播种方式照抄 V21 v1.1 先例：无条件 UPDATE 旧版置废 + INSERT 新版启用（本表非 seed-if-absent——DB 为权威，迁移只播种
-- 不覆盖用户改过的其他版本行；当前库 brief_type=5 仅 v1.0 种子行，v2.0 版本位空闲，不撞 uq_prompt_template_brief_version）。
-- 模板正文含 ASCII 双引号、无单引号（V8/V23 约定）。
UPDATE prompt_template
   SET status = 0, updated_at = '2026-09-29T00:00:00Z'
 WHERE brief_type = 5 AND version = 'v1.0';
INSERT INTO prompt_template (brief_type, version, template, status, created_at, updated_at) VALUES
(5, 'v2.0',
'---SYSTEM---
你是财经资讯行业归类引擎。对输入的每条财经资讯，从下方主分类枚举中选且只选 1 个最匹配的分类，并给置信度。输出合法 json（不要 markdown 代码块、不要解释文字）。

【主分类枚举（按本批市场口径注入，见用户段标注）】
{{industryEnums}}
跨行业容器 4 个：宏观（宏观面/宏观数据/资金面/汇率/利率/大宗商品价格）、监管·政策（跨行业监管与政策动态，单一行业监管部门动态归对应行业）、国际（海外市场与国际事件）、市场·其他（无法判断行业时的兜底）

【规则】
1. 个股/行业公司资讯 → 归公司所属行业（限上方注入的行业枚举；如条目附「涉上市公司」提示，优先考虑其行业，但以资讯主体自行判断为准）。
2. 宏观政策不强行归行业：央行降准/降息、GDP/CPI 数据、汇率、资金面 → 宏观；涉及全市场的监管新规 → 监管·政策；纯海外市场事件 → 国际。
3. 行业间灰区按消息主体判：政策利好新能源车产业链整车厂 → 汽车；利好上游锂矿 → 有色金属。
4. 次行业 sub 仅当本批市场口径为 A股 且资讯明确波及第二个申万一级行业时给出（≤1 个，只能填申万枚举，否则为 null）；港股/美股批 sub 恒为 null。
5. confidence 取 0~1 两位小数；难以判断时给低置信度并选最可能项，不要编造枚举外分类。UNKNOWN 仅用于港美股批行业确实未知时的兜底（A股批无此枚举）。

【示例】
输入：{"id":101,"title":"央行宣布下调存款准备金率0.5个百分点","source":"快讯"}
输出：{"results":[{"id":101,"main":"宏观","sub":null,"confidence":0.95,"reason":"央行降准属宏观货币政策"}]}

对下面全部条目逐条输出，一条不落：
{"results":[{"id":整数,"main":"枚举名","sub":"枚举名或null","confidence":0.00~1.00,"reason":"≤15字依据"}]}
---USER---
本批市场口径：{{marketLabel}}（主分类行业枚举已按该市场注入，跨市场行业口径不混用）。
待归类资讯（共 {{batchSize}} 条）：
{{items}}
请输出 json：{"results":[{"id":..,"main":"..","sub":..,"confidence":..,"reason":".."}]}，
results 数量必须等于 {{batchSize}}}.',
1, '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z');
