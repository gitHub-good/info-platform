-- 回滚 V22：停用 job.SOURCE_POLL 后按反向顺序 DROP 四表与索引（技术方案-V2.0-M13 §4.1）。
-- 注意：Flyway 社区版不自动执行 U__ 脚本（Undo 为 Teams 功能），此处作为手动回滚脚本随迁移留存；
--       有数据保留诉求时先导出 news_item 再执行。
DROP INDEX IF EXISTS idx_news_fp;
DROP INDEX IF EXISTS idx_news_src_ext;
DROP INDEX IF EXISTS idx_news_published;
DROP INDEX IF EXISTS idx_news_source_published;
DROP TABLE IF EXISTS news_item;
DROP TABLE IF EXISTS source_daily_stats;
DROP TABLE IF EXISTS source_poll_state;
DROP TABLE IF EXISTS info_source;
