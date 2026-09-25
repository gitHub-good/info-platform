-- T120~T125 · M15 管道四表（逐条管道状态 / 结构化事件 / 行业热度快照 / 行业日报）+ 提示词播种 brief_type=5
-- 来源：技术方案-V2.0-M15 §4.1 DDL + ADR-0046 裁决 1（news_item 零改动，管道产物独立建表，analysis 域所有）。
-- 惯例对齐 V22：小写下划线；三必备 created_at/updated_at；NOT NULL+默认值优先；时间戳整秒 ISO-8601（UTC）文本。
-- 本批（T120/T121）只建四表 + 播种 brief_type=5 行业归类 v1.0；brief_type=6/7 模板随 T122/T124 追加播种（后续迁移）。

-- 管道逐条状态与归类产物（1:1 news_item；L0~L2 三段状态同表——段间顺序依赖，拆表无查询收益）
-- l0_result：PASS / NOISE（广告噪音，隔离不进 L1/L2/热度/事件）/ NEAR_DUP（近重复，关联主条不进 L1）
-- l1_status：PENDING / DONE / FAILED（当日重试 ≤maxRetries；次日 24h 窗口内再试一轮）
-- l2_status：SKIP（未命中预筛）/ SELECTED（进批）/ EXTRACTED / NO_EVENT（模型判定无可提取事件）
--             / DEFERRED（命中但配额满，如实统计）/ FAILED
CREATE TABLE news_analysis (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  news_id           INTEGER NOT NULL,               -- news_item.id（不做物理 FK，SQLite 惯例）
  l0_result         TEXT    NOT NULL DEFAULT 'PASS',
  near_dup_of       INTEGER,                        -- 近重复主条 news_id（NEAR_DUP 必填；主条为 24h 内同窗 PASS 条目）
  l0_detail         TEXT,                           -- 诊断：NOISE 命中规则名 / NEAR_DUP 海明距离+编辑距离
  importance_score  REAL    NOT NULL DEFAULT 0,     -- L2 重要性预筛分（T122 §4.4 公式，所有 PASS 条目入库时打）
  l1_status         TEXT    NOT NULL DEFAULT 'PENDING',
  main_category     TEXT,                           -- 兜底后主分类（35 枚举；仅 DONE 有值）
  raw_main          TEXT,                           -- 模型原始主分类（兜底改写或非法枚举时留痕，抽检用）
  sub_industry      TEXT,                           -- 次行业（仅申万枚举，可空）
  confidence        REAL,                           -- 0~1
  low_confidence    TINYINT NOT NULL DEFAULT 0,     -- 1=置信度<floor 或模型输出非法枚举，已兜底「市场·其他」
  matched_subjects  TEXT,                           -- JSON [{"code","name","industry"}] 标的池回联（一致性信号 v1 留痕）
  l1_attempts       INTEGER NOT NULL DEFAULT 0,
  l1_prompt_version TEXT,                           -- 产出所用模板版本（抽检/回归定位）
  classified_at     TEXT,                           -- L1 完成时刻（T+30min 口径分子）
  l2_status         TEXT    NOT NULL DEFAULT 'SKIP',
  l2_attempts       INTEGER NOT NULL DEFAULT 0,
  created_at        TEXT    NOT NULL,
  updated_at        TEXT    NOT NULL,
  UNIQUE(news_id)
);
CREATE INDEX idx_na_l1_status ON news_analysis(l1_status, created_at); -- 批窗口取待处理 + 补跑窗口
CREATE INDEX idx_na_main      ON news_analysis(main_category);        -- 热度聚合/下钻/抽检
CREATE INDEX idx_na_l2        ON news_analysis(l2_status, importance_score); -- 配额 Top-N 截断

-- 结构化事件（L2 产物，T122 填逻辑；一条资讯至多一条事件 v1——UNIQUE 锁定，多事件提取留 M17）
-- event_type 白名单（代码枚举）：EARNINGS_FORECAST 业绩预告 / MA_MERGER 并购重组 / BUYBACK_CHANGE 回购·增持·减持
--   / MAJOR_CONTRACT 重大合同·中标 / POLICY_RELEASE 政策发布 / REGULATORY_PENALTY 监管处罚·立案
--   / EXEC_CHANGE 高管变动 / TECH_BREAKTHROUGH 技术突破·产品发布 / OTHER 其他
CREATE TABLE event_item (
  id                  INTEGER PRIMARY KEY AUTOINCREMENT,
  news_id             INTEGER NOT NULL,
  event_type          TEXT    NOT NULL,             -- 上述 9 值白名单
  summary             TEXT    NOT NULL,             -- 事件一句话摘要（模型产出）
  affected_industries TEXT    NOT NULL DEFAULT '[]',-- JSON 申万枚举数组（热度事件加权扩散面）
  direction           TEXT    NOT NULL,             -- BULLISH 利好 / BEARISH 利空 / NEUTRAL 中性
  importance          TEXT    NOT NULL,             -- HIGH / MEDIUM / LOW（热度系数 1.0/0.5/0.25）
  key_figures         TEXT,                         -- JSON [{"label","value","unit"}]（来自原文，禁编造）
  subjects            TEXT,                         -- JSON [{"code","name","industry"}]（标的池回联，code 可空=未回联仅留名）
  quote               TEXT,                         -- 原文引用片段（可回溯，页面展示+抽检）
  event_time          TEXT,                         -- 事件时间 ISO（模型给出，缺省 news.published_at）
  event_date          TEXT    NOT NULL,             -- Asia/Shanghai yyyy-MM-dd（事件流日聚合/日报口径）
  prompt_version      TEXT,
  created_at          TEXT    NOT NULL,
  updated_at          TEXT    NOT NULL,
  UNIQUE(news_id)
);
CREATE INDEX idx_event_type_date ON event_item(event_type, event_date);  -- 事件流类型筛选
CREATE INDEX idx_event_date      ON event_item(event_date);              -- 日报/事件流日窗

-- 行业热度快照（T123 填逻辑；UPSERT 当前值：UNIQUE(industry, window_type) 62 行常驻；历史趋势由日报 heat_top 留存）
CREATE TABLE industry_heat_snapshot (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  industry     TEXT    NOT NULL,                    -- 申万 31 枚举（容器不进榜）
  window_type  TEXT    NOT NULL,                    -- H24 / D7
  heat_score   REAL    NOT NULL DEFAULT 0,
  prev_score   REAL    NOT NULL DEFAULT 0,          -- 上一等长窗口（对齐错位，T123 §4.5）
  delta_pct    REAL    NOT NULL DEFAULT 0,          -- (score-prev)/prev；prev=0 且 score>0 记 100.0，双 0 记 0
  news_count   INTEGER NOT NULL DEFAULT 0,          -- 去重后资讯条数（PASS 且 main=本行业）
  event_count  INTEGER NOT NULL DEFAULT 0,          -- 影响本行业的事件数（含扩散）
  basis        TEXT    NOT NULL,                    -- 口径版本串（榜单脚注直读）
  snapshot_at  TEXT    NOT NULL,
  created_at   TEXT    NOT NULL,
  updated_at   TEXT    NOT NULL,
  UNIQUE(industry, window_type)
);

-- 行业日报（T124 填逻辑；v1 失败可重试；内容 JSON 契约方案 §4.5）
CREATE TABLE industry_daily_report (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  report_date    TEXT    NOT NULL,                  -- Asia/Shanghai yyyy-MM-dd（覆盖前一日 00:00~24:00）
  status         TEXT    NOT NULL,                  -- SUCCESS / FAILED
  content        TEXT,                              -- JSON：summary/topIndustries[5]/containerCounts/totalNews/totalEvents/disclaimer
  heat_top       TEXT,                              -- 生成时点快照留存 JSON（对账与趋势，不随快照滚动丢失）
  error_message  TEXT,
  prompt_version TEXT,
  basis          TEXT,                              -- heat + cost 双 basis 串
  created_at     TEXT    NOT NULL,
  updated_at     TEXT    NOT NULL,
  UNIQUE(report_date)
);

-- 提示词播种：BriefType 5 行业归类 v1.0（ADR-0046 裁决 3；system 段与附录 A 实测文本同源，用户段占位符化；
-- status=1 启用，UNIQUE(brief_type, version) 幂等）。模板正文含 ASCII 双引号、无单引号（V8/V21 约定）。
INSERT INTO prompt_template (brief_type, version, template, status, created_at, updated_at) VALUES
(5, 'v1.0',
'---SYSTEM---
你是财经资讯行业归类引擎。对输入的每条财经资讯，从下列 35 个主分类中选且只选 1 个最匹配的分类，并给置信度。输出合法 json（不要 markdown 代码块、不要解释文字）。

【主分类枚举（35 选 1）】
申万一级行业 31 个：农林牧渔、基础化工、钢铁、有色金属、电子、家用电器、食品饮料、纺织服饰、轻工制造、医药生物、公用事业、交通运输、房地产、商贸零售、社会服务、银行、非银金融、综合、建筑材料、建筑装饰、电力设备、机械设备、国防军工、计算机、传媒、通信、煤炭、石油石化、环保、美容护理、汽车
跨行业容器 4 个：宏观（宏观面/宏观数据/资金面/汇率/利率/大宗商品价格）、监管·政策（跨行业监管与政策动态，单一行业监管部门动态归对应行业）、国际（海外市场与国际事件）、市场·其他（无法判断行业时的兜底）

【规则】
1. 个股/行业公司资讯 → 归公司所属申万一级行业（如贵州茅台→食品饮料、宁德时代→电力设备、中芯国际→电子）。
2. 宏观政策不强行归行业：央行降准/降息、GDP/CPI 数据、汇率、资金面 → 宏观；涉及全市场的监管新规 → 监管·政策；纯海外市场事件 → 国际。
3. 行业间灰区按消息主体判：政策利好新能源车产业链整车厂 → 汽车；利好上游锂矿 → 有色金属。
4. 次行业 sub 仅在资讯明确波及第二个申万行业时给出（≤1 个，只能填申万枚举，否则为 null）。
5. confidence 取 0~1 两位小数；难以判断时给低置信度并选最可能项，不要编造枚举外分类。
6. 若条目附「涉上市公司」提示，优先考虑其行业，但以资讯主体自行判断为准。

【示例】
输入：{"id":101,"title":"央行宣布下调存款准备金率0.5个百分点","source":"快讯"}
输出：{"results":[{"id":101,"main":"宏观","sub":null,"confidence":0.95,"reason":"央行降准属宏观货币政策"}]}

对下面全部条目逐条输出，一条不落：
{"results":[{"id":整数,"main":"枚举名","sub":"枚举名或null","confidence":0.00~1.00,"reason":"≤15字依据"}]}
---USER---
待归类资讯（共 {{batchSize}} 条）：
{{items}}
请输出 json：{"results":[{"id":..,"main":"..","sub":..,"confidence":..,"reason":".."}]}，
results 数量必须等于 {{batchSize}}}。',
1, '2026-09-26T00:00:00Z', '2026-09-26T00:00:00Z');
