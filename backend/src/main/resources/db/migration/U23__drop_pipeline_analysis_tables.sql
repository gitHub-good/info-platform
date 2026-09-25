-- U23 · M15 管道四表回滚（方案 §4.1：停用 4 个管道 Job 键后按反序 DROP；news_item 无伤）
DROP TABLE IF EXISTS industry_daily_report;
DROP TABLE IF EXISTS industry_heat_snapshot;
DROP TABLE IF EXISTS event_item;
DROP TABLE IF EXISTS news_analysis;
DELETE FROM prompt_template WHERE brief_type IN (5, 6, 7);
