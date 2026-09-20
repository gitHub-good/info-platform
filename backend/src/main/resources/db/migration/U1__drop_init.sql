-- 回滚 V1：按反向顺序删除索引与表
-- 注意：Flyway 社区版不自动执行 U__ 脚本（Undo 为 Teams 功能），此处作为手动回滚脚本随迁移留存
DROP INDEX IF EXISTS idx_subject_market;
DROP INDEX IF EXISTS idx_subject_type;
DROP TABLE IF EXISTS subject_master;
