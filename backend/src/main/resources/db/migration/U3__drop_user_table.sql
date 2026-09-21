-- 回滚 V3：删除 T17 播种的默认用户与用户表
-- 注意：Flyway 社区版不自动执行 U__ 脚本（Undo 为 Teams 功能），此处作为手动回滚脚本随迁移留存
DROP TABLE IF EXISTS user;
