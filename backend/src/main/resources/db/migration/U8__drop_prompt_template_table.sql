-- 回滚 V8：按反向顺序删除唯一索引与表
-- 注意：Flyway 社区版不自动执行 U__ 脚本（Undo 为 Teams 功能），此处作为手动回滚脚本随迁移留存
DROP INDEX IF EXISTS uq_prompt_template_brief_version;
DROP TABLE IF EXISTS prompt_template;
