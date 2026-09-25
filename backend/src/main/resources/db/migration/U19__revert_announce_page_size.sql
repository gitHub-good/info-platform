-- 回滚 V19：announcePageSize 还原旧种子缺省 3（对齐 U1~U18 手动回滚脚本惯例，Flyway 社区版不自动执行）
-- 条件 UPDATE（幂等）：仅当现值为迁移后缺省 10 时还原 3——用户手改其他值不动；回滚后新缺省仍在代码侧（10），
-- 再次保存页面配置会按页面值为准（DB 为权威）。
UPDATE runtime_config
SET config_value = json_set(config_value, '$.params.announcePageSize', 3),
    updated_at   = strftime('%Y-%m-%dT%H:%M:%SZ', 'now')
WHERE config_key = 'datasource.ANNOUNCE'
  AND json_extract(config_value, '$.params.announcePageSize') = 10;
