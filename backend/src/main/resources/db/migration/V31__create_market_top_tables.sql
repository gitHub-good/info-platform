-- T180/T183 · M21 全市场漏斗与 Top10 榜单（回联提升列扩展 + 榜单两表）。
-- 来源：技术方案-V2.2-M21 §4.1/§4.2 + ADR-0059 裁决 1/6。

-- OBS-M20-3 次级排序键（粗筛四键之一 + 榜单卡「最近事件」）；存量回填取三维护据事件最大日期。
-- 注：方案 §4.2 原文的三段 UNION 直接作标量子查询在 SQLite 中取「去重排序后首行」= 最小日期（迁移单测实证
-- expected 2026-09-21 but was 2026-09-15）——外层再包 MAX(d) 才是「三段最大」语义，偏差记 ADR-0060。
ALTER TABLE subject_factor_snapshot ADD COLUMN last_event_date TEXT;
UPDATE subject_factor_snapshot SET last_event_date = (
  SELECT MAX(d) FROM (
    SELECT MAX(json_extract(e.value, '$.eventDate')) AS d FROM json_each(factor_detail, '$.catalyst.entries') e
    UNION SELECT MAX(json_extract(e.value, '$.eventDate')) FROM json_each(factor_detail, '$.fundamental.entries') e
    UNION SELECT MAX(json_extract(e.value, '$.eventDate')) FROM json_each(factor_detail, '$.risk.entries') e));

-- breakthrough 阈值校准（OBS-M20-2：60→20，用户已改值不覆盖）
UPDATE runtime_config SET config_value = json_set(config_value, '$.btCatalystMin', 20), updated_at = strftime('%Y-%m-%dT%H:%M:%SZ','now')
WHERE config_key = 'score.weight' AND json_extract(config_value, '$.btCatalystMin') = 60;

-- Top10 榜单（版本化：UNIQUE(rank_date, version, rank_no)；M22 事件触发沿用 version 递增）
CREATE TABLE market_top_rank (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  rank_date       TEXT    NOT NULL,               -- 榜单日（Asia/Shanghai yyyy-MM-dd）
  version         INTEGER NOT NULL,               -- 同日重跑/事件触发递增，读最大 version
  rank_no         INTEGER NOT NULL,               -- 1~10
  subject_id      INTEGER NOT NULL,
  subject_code    TEXT    NOT NULL,               -- 冗余（卡片直渲染免 join）
  subject_name    TEXT    NOT NULL,
  total_score     REAL    NOT NULL,               -- 因子总分（快照原值）
  final_score     REAL    NOT NULL,               -- 合成分（§4.5.1 公式）
  percentile      REAL,                           -- 全市场百分位（生成时查询层口径落库快照化）
  breakthrough    TINYINT NOT NULL DEFAULT 0,     -- 「有突破」徽章
  generation      TEXT    NOT NULL,               -- FULL（深析合格）/ FACTOR_ONLY（未深析/兜底/降级）
  dive_method     TEXT,                           -- LLM / TEMPLATE（factor_only 为 NULL）
  dive_summary    TEXT    NOT NULL,               -- 摘要短文（亮点+风险合并，≤160 字）
  dive_detail     TEXT    NOT NULL DEFAULT '{}',  -- JSON {thesis, highlights[], risks[], citations[]}（校验后终态）
  evidence_count  INTEGER NOT NULL DEFAULT 0,     -- 依据事件数（factor_detail 条目计数）
  last_event_date TEXT,
  prev_rank       INTEGER,                        -- 昨日最新版本名次（NULL=昨日不在榜）
  change_type     TEXT    NOT NULL,               -- NEW / UP / DOWN / SAME
  basis           TEXT    NOT NULL,               -- "mt-v1:final=max(total,0.8*total+0.2*dive);..." 口径串
  computed_at     TEXT    NOT NULL,
  created_at      TEXT    NOT NULL,
  updated_at      TEXT    NOT NULL,
  UNIQUE(rank_date, version, rank_no),
  UNIQUE(rank_date, version, subject_id)
);
CREATE INDEX idx_mtr_date_ver ON market_top_rank(rank_date DESC, version DESC);

-- 榜单批次（一版本一行：漏斗计数/降级态/跌出留痕/深析成本——验收与审计的读优化）
CREATE TABLE market_top_batch (
  id               INTEGER PRIMARY KEY AUTOINCREMENT,
  rank_date        TEXT    NOT NULL,
  version          INTEGER NOT NULL,
  trigger_source   TEXT    NOT NULL DEFAULT 'DAILY',  -- DAILY（M21）/ EVENT（M22 预留）
  snapshot_date    TEXT    NOT NULL,               -- 消费的因子快照日（守卫对账键）
  funnel_stats     TEXT    NOT NULL,               -- JSON {snapshotRows,eligible,excluded:{st,noSignal},poolSize,divePlanned,diveDone,diveTemplate,topSize}
  degraded         TINYINT NOT NULL DEFAULT 0,     -- 降级标志（页面横幅依据）
  degraded_reason  TEXT,                           -- COST_CAP / LLM_FAILURE
  dropped_subjects TEXT    NOT NULL DEFAULT '[]',  -- 昨日在榜今日跌出 [{code,name,prevRank}]
  dive_cost_micros INTEGER NOT NULL DEFAULT 0,     -- scene-10 本批成本（cost 报表交叉）
  dive_llm_calls   INTEGER NOT NULL DEFAULT 0,
  prompt_version   TEXT,                           -- 深析模板版本（M5 治理留痕）
  basis            TEXT    NOT NULL,
  created_at       TEXT    NOT NULL,
  updated_at       TEXT    NOT NULL,
  UNIQUE(rank_date, version)
);
