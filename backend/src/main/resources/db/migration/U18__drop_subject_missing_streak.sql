-- 回滚 V18：移除同步机制列（同步引擎停用后本列无消费方；SQLite ≥3.35 支持 DROP COLUMN）
ALTER TABLE subject_master DROP COLUMN missing_streak;
