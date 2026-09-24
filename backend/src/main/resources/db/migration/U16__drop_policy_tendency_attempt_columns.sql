-- 回滚 V16：反向删除尝试留痕列（对齐 U1~U15 手动回滚脚本惯例，Flyway 社区版不自动执行 U__ 脚本）
-- 注意：DROP COLUMN 需 SQLite >= 3.35（xerial sqlite-jdbc 3.45+ 满足）；回滚后失败条目恢复无限重扫，
--        仅部署回退时使用。
ALTER TABLE policy_item DROP COLUMN tendency_last_attempt_at;
ALTER TABLE policy_item DROP COLUMN tendency_attempts;
