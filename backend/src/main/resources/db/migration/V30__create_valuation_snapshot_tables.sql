-- T170 · M20 因子快照两表（行情估值日快照 + 标的因子日快照）。
-- 来源：技术方案-V2.2-M20 §4.1 DDL + ADR-0058 裁决 1/5。
-- 惯例对齐 V23：小写下划线；三必备 created_at/updated_at；NOT NULL+默认值优先；时间戳整秒 ISO-8601（UTC）文本；
-- snapshot_date 为 Asia/Shanghai yyyy-MM-dd（快照口径日，幂等锚）。

-- 行情估值日快照（F5 原料 + v2 时序分位序列资产；腾讯批量一次落库）
CREATE TABLE market_daily_snapshot (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  subject_id    INTEGER NOT NULL,               -- subject_master.id
  snapshot_date TEXT    NOT NULL,               -- Asia/Shanghai yyyy-MM-dd（快照口径日）
  close_price   REAL,                           -- 现价 @3（收后即收盘）
  pct_change    REAL,                           -- 涨跌幅 % @32
  turnover_rate REAL,                           -- 换手 % @38
  amplitude     REAL,                           -- 振幅 % @43
  volume        REAL,                           -- 成交量（手）@6
  pe_ttm        REAL,                           -- PE-TTM @52（空或 ≤0 存 NULL = 缺数）
  pb            REAL,                           -- PB @46（≤0 存 NULL）
  source        TEXT    NOT NULL DEFAULT 'tencent',
  quote_time    TEXT,                           -- 源时间戳 @30（freshness 对账）
  created_at    TEXT    NOT NULL,
  updated_at    TEXT    NOT NULL,
  UNIQUE(subject_id, snapshot_date)
);
CREATE INDEX idx_mds_date ON market_daily_snapshot(snapshot_date); -- v2 时序分位按日窗取数

-- 标的因子日快照（M20 核心：UNIQUE 当日重跑幂等覆盖；M21 粗筛/榜单、M22 统计的读源）
CREATE TABLE subject_factor_snapshot (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  subject_id    INTEGER NOT NULL,
  snapshot_date TEXT    NOT NULL,               -- 与 market_daily_snapshot 同口径日
  f_catalyst    REAL    NOT NULL DEFAULT 0,     -- F1 事件催化强度 [0,100]
  f_conduction  REAL    NOT NULL DEFAULT 0,     -- F2 行业热度传导 [0,100]
  f_fundamental REAL    NOT NULL DEFAULT 50,    -- F3 基本面边际 [0,100]，50=中性
  f_risk        REAL    NOT NULL DEFAULT 100,   -- F4 风险安全分 [0,100]，100=无风险
  f_valuation   REAL    NOT NULL DEFAULT 50,    -- F5 估值水平 [0,100]，50=中性/缺数
  total_score   REAL    NOT NULL DEFAULT 0,     -- Σ w_i×F_i / Σw_i（T171 ScoreComposer 接管）
  breakthrough  TINYINT NOT NULL DEFAULT 0,     -- 「有突破」标签（三阈值判定，T171 接管）
  factor_detail TEXT    NOT NULL,               -- JSON 五维明细（§4.5 契约；依据事件 id 可下钻 trace-v1）
  data_flags    TEXT    NOT NULL DEFAULT '[]',  -- JSON 例外口径标注（NO_MARKET_DATA/NO_ASSOC_INDUSTRY/NO_VALUATION_DATA/ST_RISK）
  weight_basis  TEXT    NOT NULL,               -- 参数指纹串（复算审计锚，§3.4 例）
  computed_at   TEXT    NOT NULL,               -- 本行计算时刻（非输入时刻；幂等以输入为准）
  created_at    TEXT    NOT NULL,
  updated_at    TEXT    NOT NULL,
  UNIQUE(subject_id, snapshot_date)
);
CREATE INDEX idx_sfs_date_score ON subject_factor_snapshot(snapshot_date, total_score DESC); -- 排名/百分位/M21 粗筛
