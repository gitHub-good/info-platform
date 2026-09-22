-- 回滚 V12：按反向顺序删除索引与表
-- 注意：Flyway 社区版不自动执行 U__ 脚本（Undo 为 Teams 功能），此处作为手动回滚脚本随迁移留存
DROP INDEX IF EXISTS idx_job_log_name_time;
DROP TABLE IF EXISTS job_execution_log;
