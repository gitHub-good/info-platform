-- V3.3 · M29 全市场覆盖：市场维度地基（T251，技术方案 §3.1 / ADR-0064 裁决 4）。
-- ①~⑥ 六表重建加 market 维（改名 → 建新表 → INSERT SELECT 回填 'A_SHARE' → 删旧表，SQLite 标准改名重建法；
--    存量合计 ~3.7 万行，单事务瞬间完成；六表均无外键引用，核实于 2026-09-29）；
-- ⑦ market_daily_snapshot 加 market_cap/currency（市值收敛 + 原币口径，拍板六）；
-- ⑧ news_analysis 加 l1_market（跨市场重名行业消歧 + 热度分市场聚合键）；
-- ⑨ industry_enum_map 新表 + 港美股枚举种子（T251 首跑词频定稿：港 31 直采 / 美 156 词归并 40 大类，方案 §3.3 回注）。
-- 注意：market_top_batch 重建保留 V33 的 trigger_events 列（方案 §3.1 ⑥ 微调，已在方案文件回注）。

-- ============ ① industry_heat_snapshot：加 market 维（热度三市场分列的存储基座）============
ALTER TABLE industry_heat_snapshot RENAME TO industry_heat_snapshot_v36;
CREATE TABLE industry_heat_snapshot (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  market       TEXT    NOT NULL DEFAULT 'A_SHARE',  -- A_SHARE / HK / US
  industry     TEXT    NOT NULL,                    -- 各市场枚举（容器不进榜；跨市场重名由 market 消歧）
  window_type  TEXT    NOT NULL,                    -- H24 / D7
  heat_score   REAL    NOT NULL DEFAULT 0,
  prev_score   REAL    NOT NULL DEFAULT 0,
  delta_pct    REAL    NOT NULL DEFAULT 0,
  news_count   INTEGER NOT NULL DEFAULT 0,
  event_count  INTEGER NOT NULL DEFAULT 0,
  basis        TEXT    NOT NULL,
  snapshot_at  TEXT    NOT NULL,
  created_at   TEXT    NOT NULL,
  updated_at   TEXT    NOT NULL,
  UNIQUE(industry, market, window_type)
);
INSERT INTO industry_heat_snapshot (market, industry, window_type, heat_score, prev_score, delta_pct,
                                    news_count, event_count, basis, snapshot_at, created_at, updated_at)
SELECT 'A_SHARE', industry, window_type, heat_score, prev_score, delta_pct,
       news_count, event_count, basis, snapshot_at, created_at, updated_at
FROM industry_heat_snapshot_v36;
DROP TABLE industry_heat_snapshot_v36;

-- ============ ② industry_market_snapshot：加 market + currency 维（港美股聚合行落位）============
ALTER TABLE industry_market_snapshot RENAME TO industry_market_snapshot_v36;
CREATE TABLE industry_market_snapshot (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  market        TEXT    NOT NULL DEFAULT 'A_SHARE',  -- A_SHARE / HK / US
  row_type      TEXT    NOT NULL,                    -- BOARD(A股通道) / INDUSTRY(三市场聚合行)
  dim_name      TEXT    NOT NULL,                    -- 板块名 / 行业枚举名
  industry      TEXT    NOT NULL,                    -- 各市场行业枚举
  snapshot_date TEXT    NOT NULL,                    -- Asia/Shanghai yyyy-MM-dd（幂等锚）
  pct_day       REAL,
  pct_d5        REAL,
  up_count      INTEGER,
  down_count    INTEGER,
  main_net_flow REAL,                                -- A 股通道专有；港美股行 NULL（如实不造假）
  total_mv      REAL,                                -- Σ 个股市值（原币元数值，见 currency）
  currency      TEXT    NOT NULL DEFAULT 'CNY',      -- CNY / HKD / USD（拍板六：原币不折算）
  leader_stock  TEXT,
  source        TEXT    NOT NULL,                    -- eastmoney-push2 / tencent-rank / hkus-aggregate
  agg_method    TEXT    NOT NULL DEFAULT 'NONE',     -- CAP_WEIGHTED / EQUAL / TENCENT_DIRECT / NONE
  quote_time    TEXT,
  created_at    TEXT    NOT NULL,
  updated_at    TEXT    NOT NULL,
  UNIQUE(row_type, dim_name, snapshot_date, market)
);
INSERT INTO industry_market_snapshot (market, row_type, dim_name, industry, snapshot_date, pct_day, pct_d5,
       up_count, down_count, main_net_flow, total_mv, currency, leader_stock, source, agg_method,
       quote_time, created_at, updated_at)
SELECT 'A_SHARE', row_type, dim_name, industry, snapshot_date, pct_day, pct_d5,
       up_count, down_count, main_net_flow, total_mv, 'CNY', leader_stock, source, agg_method,
       quote_time, created_at, updated_at
FROM industry_market_snapshot_v36;
DROP TABLE industry_market_snapshot_v36;
-- 新索引须在 DROP 旧表后创建：旧表携带 V23 同名索引（DROP TABLE 随表清除，先建则撞名——方案 §3.1 ② 回注微调）
CREATE INDEX IF NOT EXISTS idx_ims_date ON industry_market_snapshot(market, snapshot_date, row_type);
CREATE INDEX IF NOT EXISTS idx_ims_board_industry ON industry_market_snapshot(market, row_type, industry, snapshot_date);

-- ============ ③ industry_mainline：加 market 维（分市场榜单，rank_no 市场内 1~5）============
ALTER TABLE industry_mainline RENAME TO industry_mainline_v36;
CREATE TABLE industry_mainline (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  market          TEXT    NOT NULL DEFAULT 'A_SHARE',
  rank_date       TEXT    NOT NULL,
  version         INTEGER NOT NULL,
  rank_no         INTEGER NOT NULL,
  industry        TEXT    NOT NULL,
  main_score      REAL    NOT NULL,
  dim_detail      TEXT    NOT NULL,
  persistent_days INTEGER NOT NULL DEFAULT 0,
  heat_rank       INTEGER,
  divergence      TEXT    NOT NULL DEFAULT 'NONE',
  leaders         TEXT    NOT NULL DEFAULT '[]',     -- 港美股恒 '[]'（W1），detail 端点给 unavailableReason
  basis           TEXT    NOT NULL,                  -- A 股 mainline-v1 / 港美股 mainline-v1:m2
  computed_at     TEXT    NOT NULL,
  created_at      TEXT    NOT NULL,
  updated_at      TEXT    NOT NULL,
  UNIQUE(rank_date, version, market, rank_no),
  UNIQUE(rank_date, version, market, industry)
);
INSERT INTO industry_mainline (market, rank_date, version, rank_no, industry, main_score, dim_detail,
       persistent_days, heat_rank, divergence, leaders, basis, computed_at, created_at, updated_at)
SELECT 'A_SHARE', rank_date, version, rank_no, industry, main_score, dim_detail,
       persistent_days, heat_rank, divergence, leaders, basis, computed_at, created_at, updated_at
FROM industry_mainline_v36;
DROP TABLE industry_mainline_v36;
-- 同 ②：V35 同名索引随旧表 DROP 后重建（回注微调）
CREATE INDEX IF NOT EXISTS idx_iml_date_ver ON industry_mainline(rank_date DESC, version DESC, market);

-- ============ ④ industry_mainline_batch：加 market 维 ============
ALTER TABLE industry_mainline_batch RENAME TO industry_mainline_batch_v36;
CREATE TABLE industry_mainline_batch (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  market         TEXT    NOT NULL DEFAULT 'A_SHARE',
  rank_date      TEXT    NOT NULL,
  version        INTEGER NOT NULL,
  trigger_source TEXT    NOT NULL DEFAULT 'DAILY',
  snapshot_date  TEXT    NOT NULL,
  funnel_stats   TEXT    NOT NULL,
  degraded       TINYINT NOT NULL DEFAULT 0,
  degraded_reason TEXT,
  basis          TEXT    NOT NULL,
  created_at     TEXT    NOT NULL,
  updated_at     TEXT    NOT NULL,
  UNIQUE(rank_date, version, market)
);
INSERT INTO industry_mainline_batch (market, rank_date, version, trigger_source, snapshot_date,
       funnel_stats, degraded, degraded_reason, basis, created_at, updated_at)
SELECT 'A_SHARE', rank_date, version, trigger_source, snapshot_date,
       funnel_stats, degraded, degraded_reason, basis, created_at, updated_at
FROM industry_mainline_batch_v36;
DROP TABLE industry_mainline_batch_v36;

-- ============ ⑤ market_top_rank：加 market 维（分市场独立 Top10）============
ALTER TABLE market_top_rank RENAME TO market_top_rank_v36;
CREATE TABLE market_top_rank (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  market          TEXT    NOT NULL DEFAULT 'A_SHARE',
  rank_date       TEXT    NOT NULL,
  version         INTEGER NOT NULL,
  rank_no         INTEGER NOT NULL,
  subject_id      INTEGER NOT NULL,
  subject_code    TEXT    NOT NULL,
  subject_name    TEXT    NOT NULL,
  total_score     REAL    NOT NULL,
  final_score     REAL    NOT NULL,
  percentile      REAL,
  breakthrough    TINYINT NOT NULL DEFAULT 0,
  generation      TEXT    NOT NULL,
  dive_method     TEXT,
  dive_summary    TEXT    NOT NULL,
  dive_detail     TEXT    NOT NULL DEFAULT '{}',
  evidence_count  INTEGER NOT NULL DEFAULT 0,
  last_event_date TEXT,
  prev_rank       INTEGER,
  change_type     TEXT    NOT NULL,
  basis           TEXT    NOT NULL,                  -- 港美股含 dimensionMissing 注记（方案 §5.5）
  computed_at     TEXT    NOT NULL,
  created_at      TEXT    NOT NULL,
  updated_at      TEXT    NOT NULL,
  UNIQUE(rank_date, version, market, rank_no),
  UNIQUE(rank_date, version, market, subject_id)
);
INSERT INTO market_top_rank (market, rank_date, version, rank_no, subject_id, subject_code, subject_name,
       total_score, final_score, percentile, breakthrough, generation, dive_method, dive_summary,
       dive_detail, evidence_count, last_event_date, prev_rank, change_type, basis, computed_at,
       created_at, updated_at)
SELECT 'A_SHARE', rank_date, version, rank_no, subject_id, subject_code, subject_name,
       total_score, final_score, percentile, breakthrough, generation, dive_method, dive_summary,
       dive_detail, evidence_count, last_event_date, prev_rank, change_type, basis, computed_at,
       created_at, updated_at
FROM market_top_rank_v36;
DROP TABLE market_top_rank_v36;
-- 同 ②：V31 同名索引随旧表 DROP 后重建（回注微调）
CREATE INDEX IF NOT EXISTS idx_mtr_date_ver ON market_top_rank(rank_date DESC, version DESC, market);

-- ============ ⑥ market_top_batch：加 market 维（保留 V33 trigger_events 列——重建不丢 M22 归因面）============
ALTER TABLE market_top_batch RENAME TO market_top_batch_v36;
CREATE TABLE market_top_batch (
  id               INTEGER PRIMARY KEY AUTOINCREMENT,
  market           TEXT    NOT NULL DEFAULT 'A_SHARE',
  rank_date        TEXT    NOT NULL,
  version          INTEGER NOT NULL,
  trigger_source   TEXT    NOT NULL DEFAULT 'DAILY',
  snapshot_date    TEXT    NOT NULL,
  funnel_stats     TEXT    NOT NULL,
  degraded         TINYINT NOT NULL DEFAULT 0,
  degraded_reason  TEXT,
  dropped_subjects TEXT    NOT NULL DEFAULT '[]',
  dive_cost_micros INTEGER NOT NULL DEFAULT 0,
  dive_llm_calls   INTEGER NOT NULL DEFAULT 0,
  prompt_version   TEXT,
  basis            TEXT    NOT NULL,
  trigger_events   TEXT,                             -- JSON [{eventId,summary,importance}]（V33 M22 归因）
  created_at       TEXT    NOT NULL,
  updated_at       TEXT    NOT NULL,
  UNIQUE(rank_date, version, market)
);
INSERT INTO market_top_batch (market, rank_date, version, trigger_source, snapshot_date, funnel_stats,
       degraded, degraded_reason, dropped_subjects, dive_cost_micros, dive_llm_calls, prompt_version,
       basis, trigger_events, created_at, updated_at)
SELECT 'A_SHARE', rank_date, version, trigger_source, snapshot_date, funnel_stats,
       degraded, degraded_reason, dropped_subjects, dive_cost_micros, dive_llm_calls, prompt_version,
       basis, trigger_events, created_at, updated_at
FROM market_top_batch_v36;
DROP TABLE market_top_batch_v36;

-- ============ ⑦ market_daily_snapshot：加市值/币种（聚合权重 + 美股收敛依据，T252 消费）============
ALTER TABLE market_daily_snapshot ADD COLUMN market_cap REAL;   -- 腾讯布局 @44：亿原币（A/HK/US 同位）
ALTER TABLE market_daily_snapshot ADD COLUMN currency TEXT;     -- CNY / HKD / USD（NULL=既有 A 股行未回填）

-- ============ ⑧ news_analysis：加 l1_market（跨市场重名行业消歧 + 热度分市场聚合键，T253 消费）============
ALTER TABLE news_analysis ADD COLUMN l1_market TEXT;            -- A_SHARE / HK / US（NULL=未归类）
UPDATE news_analysis SET l1_market = 'A_SHARE' WHERE main_category IS NOT NULL;  -- 存量全 A 股口径
CREATE INDEX idx_na_market_cat ON news_analysis(l1_market, main_category);

-- ============ ⑨ 行业枚举映射表（F10 原词 → 归并枚举，回填率/归并口径可审计）============
CREATE TABLE industry_enum_map (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  market     TEXT    NOT NULL,                     -- HK / US（A 股申万 31 为代码常量不落表）
  raw_name   TEXT    NOT NULL,                     -- F10 BELONG_INDUSTRY 原词
  enum_name  TEXT    NOT NULL,                     -- 归并后枚举（≤40 集；UNKNOWN 兜底）
  created_at TEXT    NOT NULL,
  updated_at TEXT    NOT NULL,
  UNIQUE(market, raw_name)
);
CREATE INDEX idx_iem_enum ON industry_enum_map(market, enum_name);

-- 港美股枚举种子（T251 首跑词频定稿，2026-09-29 F10 全量：港 6961 行 31 词全量直采 / 美 21561 行 156 词归并 40 大类；
-- 代码侧白名单 IndustryCategory.HK_INDUSTRIES / US_INDUSTRIES 同源——单测断言两侧一致防漂移）。
INSERT INTO industry_enum_map (market, raw_name, enum_name, created_at, updated_at) VALUES
('HK', '一般金属及矿石', '一般金属及矿石', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('HK', '专业零售', '专业零售', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('HK', '保险', '保险', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('HK', '公用事业', '公用事业', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('HK', '其他医疗保健', '其他医疗保健', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('HK', '其他金融', '其他金融', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('HK', '农业产品', '农业产品', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('HK', '半导体', '半导体', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('HK', '原材料', '原材料', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('HK', '地产', '地产', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('HK', '媒体及娱乐', '媒体及娱乐', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('HK', '家庭电器及用品', '家庭电器及用品', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('HK', '工业工程', '工业工程', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('HK', '工用支援', '工用支援', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('HK', '工用运输', '工用运输', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('HK', '建筑', '建筑', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('HK', '支援服务', '支援服务', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('HK', '旅游及消闲设施', '旅游及消闲设施', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('HK', '汽车', '汽车', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('HK', '消费者主要零售商', '消费者主要零售商', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('HK', '煤炭', '煤炭', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('HK', '电讯', '电讯', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('HK', '石油及天然气', '石油及天然气', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('HK', '纺织及服饰', '纺织及服饰', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('HK', '综合企业', '综合企业', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('HK', '药品及生物科技', '药品及生物科技', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('HK', '资讯科技器材', '资讯科技器材', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('HK', '软件服务', '软件服务', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('HK', '银行', '银行', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('HK', '食物饮品', '食物饮品', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('HK', '黄金及贵金属', '黄金及贵金属', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '个人护理用品', '家居与个人用品', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '互动媒体与服务', '互联网与数字媒体', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '互动家庭娱乐', '互联网与数字媒体', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '互助储蓄与抵押信贷金融服务', '银行', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '互联网服务与基础设施', '互联网与数字媒体', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '交易与支付处理服务', '多元金融', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '人力资源与就业服务', '专业服务', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '人寿与健康保险', '保险', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '住宅建筑', '房地产服务与开发', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '保健护理产品经销商', '医疗保健设备与服务', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '保健护理服务', '医疗保健设备与服务', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '保健护理机构', '医疗保健设备与服务', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '保险经纪商', '保险', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '信息科技咨询与其它服务', '软件与信息服务', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '公路与铁路', '客运航空与交通设施', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '其他专卖店', '零售与经销', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '其他专门REIT', '房地产投资信托', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '再保险', '保险', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '写字楼REIT', '房地产投资信托', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '农产品与服务', '食品饮料', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '农用农业机械', '工业机械与集团', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '出版', '媒体与娱乐', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '制药', '制药', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '办公服务与用品', '商业服务与用品', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '包装食品与肉类', '食品饮料', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '化肥与农用药剂', '化学制品', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '区域性银行', '银行', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '医疗保健REIT', '房地产投资信托', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '医疗保健技术', '医疗保健设备与服务', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '医疗保健用品', '医疗保健设备与服务', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '医疗保健设备', '医疗保健设备与服务', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '半导体产品', '半导体', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '半导体材料与设备', '半导体', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '商业与住宅抵押贷款金融', '多元金融', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '商业印刷', '商业服务与用品', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '商品化工', '化学制品', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '啤酒酿造商', '食品饮料', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '地面货运', '货运与物流', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '复合型公用事业', '燃气与水务', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '多元化保险', '保险', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '多元化房地产业务', '房地产服务与开发', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '多品类零售', '零售与经销', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '多户住宅REIT', '房地产投资信托', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '多样化房地产投资信托', '房地产投资信托', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '多种化学制品', '化学制品', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '多种金属与采矿', '贵金属与采矿', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '多领域控股', '综合企业', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '安全和报警服务', '专业服务', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '客运航空公司', '客运航空与交通设施', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '家庭装潢零售', '零售与经销', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '家庭装饰品', '家居与个人用品', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '家庭装饰零售', '零售与经销', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '家用器具与特殊消费品', '家居与个人用品', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '家用电器', '家居与个人用品', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '居家用品', '家居与个人用品', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '工业REIT', '房地产投资信托', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '工业机械、物料与部件', '工业机械与集团', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '工业气体', '化学制品', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '工业集团企业', '工业机械与集团', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '广告', '媒体与娱乐', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '广播', '媒体与娱乐', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '应用软件', '软件与信息服务', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '建筑与工程', '建筑与工程', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '建筑产品', '建筑与工程', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '建筑机械与重型运输设备', '工业机械与集团', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '建筑材料', '包装与建材', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '房地产开发', '房地产服务与开发', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '房地产服务', '房地产服务与开发', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '房地产经营公司', '房地产服务与开发', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '技术产品经销商', '电子设备与元件', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '投资银行业与经纪业', '资本市场与投资服务', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '抵押房地产投资信托', '房地产投资信托', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '摩托车制造商', '汽车', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '教育服务', '消费者服务', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '数据处理与外包服务', '软件与信息服务', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '新能源发电业者', '电力与新能源', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '无线电信业务', '通信与电信', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '日常消费品零售', '零售与经销', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '有线和卫星电视', '媒体与娱乐', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '服装、服饰与奢侈品', '服装与奢侈品', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '服装零售', '零售与经销', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '机场服务', '客运航空与交通设施', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '林业产品', '包装与建材', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '水公用事业', '燃气与水务', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '汽车制造商', '汽车', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '汽车零件与设备', '汽车', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '汽车零售', '零售与经销', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '海上运输', '货运与物流', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '海港与服务', '客运航空与交通设施', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '消费信贷', '多元金融', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '消费电子产品', '电子设备与元件', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '消闲用品', '餐饮住宿与休闲', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '消闲设施', '餐饮住宿与休闲', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '烟草', '烟草', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '煤与消费用燃料', '煤炭与燃料', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '燃气公用事业', '燃气与水务', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '特殊消费者服务', '消费者服务', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '特殊金融服务', '多元金融', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '特种化学制品', '化学制品', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '独立电力生产商与能源贸易商', '电力与新能源', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '环境与设施服务', '商业服务与用品', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '生命科学工具和服务', '医疗保健设备与服务', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '生物科技', '生物科技', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '电力公用事业', '电力与新能源', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '电子元件', '电子设备与元件', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '电子制造服务', '电子设备与元件', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '电子设备和仪器', '电子设备与元件', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '电影与娱乐', '媒体与娱乐', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '电气部件与设备', '工业机械与集团', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '电脑与电子产品零售', '零售与经销', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '电脑硬件、储存设备及电脑周边', '电子设备与元件', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '石油与天然气的储存和运输', '能源设备与服务', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '石油与天然气的勘探与生产', '石油与天然气', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '石油与天然气的炼制和营销', '能源设备与服务', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '石油与天然气钻井', '能源设备与服务', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '石油天然气设备与服务', '能源设备与服务', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '管理型保健护理', '医疗保健设备与服务', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '系统软件', '软件与信息服务', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '纸制品', '包装与建材', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '纸质和塑料包装产品及材料', '包装与建材', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '纺织品', '服装与奢侈品', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '经销商', '零售与经销', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '综合性石油与天然气企业', '石油与天然气', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '综合性资本市场', '资本市场与投资服务', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '综合性银行', '银行', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '综合支持服务', '专业服务', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '综合电信业务', '通信与电信', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '综合金融服务', '多元金融', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '航天航空与国防', '航天航空与国防', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '航空货运与物流', '货运与物流', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '药品零售', '零售与经销', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '调查和咨询服务', '专业服务', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '财产与意外伤害保险', '保险', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '贵重金属与矿石', '贵金属与采矿', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '贸易公司与经销商', '零售与经销', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '资产管理与托管银行', '资本市场与投资服务', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '赌场与赌博', '餐饮住宿与休闲', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '轮胎与橡胶', '汽车', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '软饮料与不含酒精饮料', '食品饮料', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '通信设备', '通信与电信', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '酒店、度假村与豪华游轮', '餐饮住宿与休闲', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '酒店及度假村REIT', '房地产投资信托', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '酿酒商与葡萄酒商', '食品饮料', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '重型电气设备', '工业机械与集团', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '金属、玻璃及塑料器皿', '包装与建材', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '金融交易所和数据', '资本市场与投资服务', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '钢铁', '钢铁与铝', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '铁路', '客运航空与交通设施', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '铝', '钢铁与铝', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '零售REIT', '房地产投资信托', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '非传统电信运营商', '通信与电信', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '鞋类', '家居与个人用品', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '食品分销商', '食品饮料', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '食品零售', '零售与经销', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '餐馆', '餐饮住宿与休闲', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
('US', '黄金', '贵金属与采矿', '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z');
