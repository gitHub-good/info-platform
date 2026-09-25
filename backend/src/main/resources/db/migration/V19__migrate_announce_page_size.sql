-- M12 · REQ-20260925-09 公告分区页码分页：announcePageSize 种子缺省 3→10（技术方案 §4.2 / ADR-0037 决策 5）
-- 条件 UPDATE（幂等）：仅当现值为旧种子缺省 3 时置 10——页面已手改成其他值的行不动（用户意图优先）；
-- 重跑安全（现值 10 ≠ 3 不再命中）；新环境空库由 DataSourceDefaults 新缺省（10）种子，本迁移空跑。
-- 注意：社区版 Flyway 不自动执行 U__ 脚本，回滚见 U19（同款条件 UPDATE 反向）。
UPDATE runtime_config
SET config_value = json_set(config_value, '$.params.announcePageSize', 10),
    updated_at   = strftime('%Y-%m-%dT%H:%M:%SZ', 'now')
WHERE config_key = 'datasource.ANNOUNCE'
  AND json_extract(config_value, '$.params.announcePageSize') = 3;
