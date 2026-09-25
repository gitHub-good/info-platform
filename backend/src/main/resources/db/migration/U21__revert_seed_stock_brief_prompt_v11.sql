-- 回滚 V21：删除个股简报 v1.1，恢复 v1.0 启用（Flyway 社区版不自动执行，手动回滚脚本，对齐 U14 先例）
DELETE FROM prompt_template WHERE brief_type = 1 AND version = 'v1.1';
UPDATE prompt_template SET status = 1, updated_at = '2026-09-21T00:00:00Z'
 WHERE brief_type = 1 AND version = 'v1.0';
