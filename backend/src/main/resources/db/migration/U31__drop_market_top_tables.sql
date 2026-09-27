-- U31 · 回滚 V31（M21 榜单两表独立，无他表引用，直接删）。
DROP TABLE IF EXISTS market_top_batch;
DROP TABLE IF EXISTS market_top_rank;

-- breakthrough 阈值回滚（V31 守卫反向：仍为校准值 20 时恢复 60；用户自定义其他值不动）
UPDATE runtime_config SET config_value = json_set(config_value, '$.btCatalystMin', 60), updated_at = strftime('%Y-%m-%dT%H:%M:%SZ','now')
WHERE config_key = 'score.weight' AND json_extract(config_value, '$.btCatalystMin') = 20;

-- subject_factor_snapshot.last_event_date：SQLite 旧版本不支持 DROP COLUMN——回滚口径 = 代码停写该列（保留列无害），
-- 粗筛四键的次级排序键随 V31 代码回滚一并消失，无脏读面。
