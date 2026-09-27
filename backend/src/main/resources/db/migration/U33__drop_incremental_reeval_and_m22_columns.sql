-- U33 · 回滚 V33（留痕表独立无他表引用，直接删；两 ALTER 列沿 U31 惯例：代码停写该列，保留列无害——
-- increment_at/trigger_events 数据自然废弃，无下游依赖，方案 §4.1 U33 实操条款）。
DROP INDEX IF EXISTS idx_ireval_created;
DROP TABLE IF EXISTS incremental_reeval_log;

-- subject_factor_snapshot.increment_at / market_top_batch.trigger_events：
-- SQLite 旧版本不支持 DROP COLUMN（3.35 前），回滚口径 = 代码停写（保留列无害，U31 last_event_date 同款）。
