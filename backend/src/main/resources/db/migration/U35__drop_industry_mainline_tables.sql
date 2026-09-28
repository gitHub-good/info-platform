-- U35 · 回滚 V35（三表独立无他表引用，直接删；沿 U31 惯例）。
DROP INDEX IF EXISTS idx_iml_date_ver;
DROP TABLE IF EXISTS industry_mainline_batch;
DROP TABLE IF EXISTS industry_mainline;
DROP INDEX IF EXISTS idx_ims_board_industry;
DROP INDEX IF EXISTS idx_ims_date;
DROP TABLE IF EXISTS industry_market_snapshot;

-- runtime_config industry.mainline / industry.leader 两键与 job.* 两键为 seed-if-absent 代码/yml 播种，
-- 回滚随代码回退自然消失（无迁移数据依赖，沿 V34 先例判断）。
DELETE FROM runtime_config WHERE config_key IN ('industry.mainline', 'industry.leader');
