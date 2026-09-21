-- 回滚 V4：按反向顺序删除索引与表（先子表 watchlist_item，后父表 watchlist）
-- 注意：Flyway 社区版不自动执行 U__ 脚本（Undo 为 Teams 功能），此处作为手动回滚脚本随迁移留存
DROP INDEX IF EXISTS idx_wlitem_subject;
DROP TABLE IF EXISTS watchlist_item;
DROP INDEX IF EXISTS idx_watchlist_user;
DROP TABLE IF EXISTS watchlist;
