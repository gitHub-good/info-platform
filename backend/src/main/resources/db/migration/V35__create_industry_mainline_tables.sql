-- T242/T243 · M27 行业热力图与主线龙头三表（行业行情快照 + 主线榜单 + 主线批次）。
-- 来源：技术方案-M27 §4.1 DDL + ADR-0063 裁决 1/2/4/6。惯例对齐 V23/V30/V31：小写下划线；三必备
-- created_at/updated_at；NOT NULL+默认值优先；时间戳整秒 ISO-8601（UTC）文本；snapshot_date/rank_date
-- 为 Asia/Shanghai yyyy-MM-dd（幂等锚）。

-- ① 行业行情快照（板块行 + 申万行业聚合行两级同表；row_type 区分）
-- dim_name：BOARD 行 = 东财板块名（f14）；INDUSTRY 行 = 申万行业名。
-- UNIQUE(row_type, dim_name, snapshot_date)：盘中轮 UPSERT 当日行（幂等键 = 方案库 03 业务语义键），
--   跨日自然新增——每日 ~117 行（86 板块 + 31 行业）。
-- pct_d5：通道 A 库内自算（近 5 交易日 pct_day 复利累计）/ 通道 B 源 zdf_d5 直给；冷启动 NULL。
-- source：eastmoney-push2 / tencent-rank（降级链留痕）；agg_method 见行注释。
CREATE TABLE industry_market_snapshot (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  row_type      TEXT    NOT NULL,              -- BOARD / INDUSTRY
  dim_name      TEXT    NOT NULL,              -- 板块名（BOARD）/ 申万行业名（INDUSTRY）
  industry      TEXT    NOT NULL,              -- 申万 31 枚举（BOARD 行 = swPrimaryOf(dim_name)；未收录板块不落行）
  snapshot_date TEXT    NOT NULL,              -- Asia/Shanghai yyyy-MM-dd（幂等锚）
  pct_day       REAL,                          -- 当日涨跌幅 %
  pct_d5        REAL,                          -- 5 日累计涨跌幅 %
  up_count      INTEGER,                       -- 上涨家数（f104 / Σ / zgb 前数）
  down_count    INTEGER,                       -- 下跌家数（f105 / Σ / zgb 后数）
  main_net_flow REAL,                          -- 主力净流入（元：f62 Σ / zljlr×10⁴）
  total_mv      REAL,                          -- 总市值（元：f20 / Σ / zsz×10⁴）
  leader_stock  TEXT,                          -- 领涨股 JSON {"code","name","pct"}（通道 B lzg；通道 A NULL）
  source        TEXT    NOT NULL,              -- eastmoney-push2 / tencent-rank
  agg_method    TEXT    NOT NULL DEFAULT 'NONE', -- CAP_WEIGHTED / EQUAL / TENCENT_DIRECT / NONE(板块行)
  quote_time    TEXT,                          -- 源时间戳（stale 判定 + 三方对账锚）
  created_at    TEXT    NOT NULL,
  updated_at    TEXT    NOT NULL,
  UNIQUE(row_type, dim_name, snapshot_date)
);
CREATE INDEX idx_ims_date ON industry_market_snapshot(snapshot_date, row_type);       -- 热力图当日 31 行 / 5 日窗
CREATE INDEX idx_ims_board_industry ON industry_market_snapshot(row_type, industry, snapshot_date); -- 下钻按行业取板块行

-- ② 主线榜单（版本化沿 market_top_rank 先例：同日重算/手动 version+1，读最大 version）
-- dim_detail：三维分解 JSON（各维 rank/score/raw 原始值——对账与展示共用）
-- leaders：龙一/二/三 JSON 数组（§4.4.3 契约，含主力徽章与依据回溯）
-- divergence：NONE / PRICE_HOT_HEAT_COLD（价格≤3 名且热度>13 名的中性背离标注）
CREATE TABLE industry_mainline (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  rank_date       TEXT    NOT NULL,
  version         INTEGER NOT NULL,
  rank_no         INTEGER NOT NULL,            -- 1~5
  industry        TEXT    NOT NULL,
  main_score      REAL    NOT NULL,
  dim_detail      TEXT    NOT NULL,            -- JSON {"price":{...},"heat":{...},"event":{...}}
  persistent_days INTEGER NOT NULL DEFAULT 0,  -- 近 5 交易日上榜天数（门槛 ≥2 可配）
  heat_rank       INTEGER,                     -- 对照徽章：当前 H24 排名
  divergence      TEXT    NOT NULL DEFAULT 'NONE',
  leaders         TEXT    NOT NULL DEFAULT '[]',
  basis           TEXT    NOT NULL,            -- mainline-v1:... 口径串（§3.4）
  computed_at     TEXT    NOT NULL,
  created_at      TEXT    NOT NULL,
  updated_at      TEXT    NOT NULL,
  UNIQUE(rank_date, version, rank_no),
  UNIQUE(rank_date, version, industry)
);
CREATE INDEX idx_iml_date_ver ON industry_mainline(rank_date DESC, version DESC);

-- ③ 主线批次（一版本一行：漏斗计数/缺维/排除/徽章失败留痕——验收与审计读优化，沿 market_top_batch）
CREATE TABLE industry_mainline_batch (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  rank_date      TEXT    NOT NULL,
  version        INTEGER NOT NULL,
  trigger_source TEXT    NOT NULL DEFAULT 'DAILY', -- DAILY / MANUAL
  snapshot_date  TEXT    NOT NULL,             -- 消费的行情快照日（守卫对账键）
  funnel_stats   TEXT    NOT NULL,             -- JSON {"industries","persistPass","topN","dimensionMissing":{...},"excluded":{...},"attentionFailures"}
  degraded       TINYINT NOT NULL DEFAULT 0,   -- 快照缺当日行等降级态
  degraded_reason TEXT,
  basis          TEXT    NOT NULL,
  created_at     TEXT    NOT NULL,
  updated_at     TEXT    NOT NULL,
  UNIQUE(rank_date, version)
);
