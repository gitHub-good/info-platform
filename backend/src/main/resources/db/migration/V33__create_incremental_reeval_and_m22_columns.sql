-- T190/T191 · M22 事件增量重评留痕表 + 两列扩展（ADR-0061 裁决二/三）。
-- 来源：技术方案-V2.2-M22 §4.1 DDL。惯例对齐 V30/V31：小写下划线；三必备 created_at/updated_at；时间戳整秒 ISO-8601（UTC）文本。

-- 增量重评留痕（一行一事件：消费判重键 + 审计真相源 + 北极星时效数据源）
CREATE TABLE incremental_reeval_log (
  id               INTEGER PRIMARY KEY AUTOINCREMENT,
  event_id         INTEGER NOT NULL,               -- event_item.id（UNIQUE 判重——消费幂等键）
  event_created_at TEXT    NOT NULL,               -- 事件入库时刻（时效口径起点，冗余免 join）
  subjects_json    TEXT    NOT NULL DEFAULT '[]',  -- 受影响标的集 [{id,code,before,after}]（前后分留痕）
  snapshot_at      TEXT,                           -- 增量重算落库时刻（NULL=未到重算阶段）
  judge_passed     TINYINT NOT NULL DEFAULT 0,     -- 挤入挤出阈值判定是否通过
  top_version      INTEGER,                        -- 联动榜单版本号（NULL=未联动）
  top_version_at   TEXT,                           -- 联动版本落库时刻（时效口径终点）
  status           TEXT    NOT NULL,               -- SCANNED/RECOMPUTED/NO_LINK/LINKED/DEFERRED/FAILED
  error_message    TEXT,
  created_at       TEXT    NOT NULL,
  updated_at       TEXT    NOT NULL,
  UNIQUE(event_id)
);
CREATE INDEX idx_ireval_created ON incremental_reeval_log(created_at); -- 时效统计/生命周期清理窗

-- 增量覆盖时刻（NULL=盘后全量行；全量 UPSERT 显式置 NULL 复位——value-score 双层时间戳依据）
ALTER TABLE subject_factor_snapshot ADD COLUMN increment_at TEXT;

-- EVENT 版本归因（V31 trigger_source 已预留 'EVENT'；此列落触发事件 id+摘要，trace-v1 下钻）
ALTER TABLE market_top_batch ADD COLUMN trigger_events TEXT; -- JSON [{eventId,summary,importance}]
