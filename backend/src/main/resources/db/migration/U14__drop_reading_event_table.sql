-- 回滚 V14：按反向顺序删除索引与表，并回滚每日推荐提示词版本（删 v1.1、恢复 v1.0 启用）
-- 注意：Flyway 社区版不自动执行 U__ 脚本（Undo 为 Teams 功能），此处作为手动回滚脚本随迁移留存
DROP INDEX IF EXISTS idx_reading_event_user_type_ref;
DROP INDEX IF EXISTS idx_reading_event_user_created;
DROP TABLE IF EXISTS reading_event;
DELETE FROM prompt_template WHERE brief_type = 4 AND version = 'v1.1';
UPDATE prompt_template SET status = 1, updated_at = '2026-09-21T00:00:00Z'
 WHERE brief_type = 4 AND version = 'v1.0';
