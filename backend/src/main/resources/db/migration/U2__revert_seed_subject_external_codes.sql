-- 回滚 V2：删除示例标的种子数据
-- 注意：Flyway 社区版不自动执行 U__ 脚本（Undo 为 Teams 功能），此处作为手动回滚脚本随迁移留存
DELETE FROM subject_master
WHERE subject_code IN ('SH600519', 'SZ000001', 'HK00700');
