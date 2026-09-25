-- 回滚 V20：policyUrl 还原旧目录页并移除 policyMaxItems（Flyway 社区版不自动执行，手动回滚脚本）
-- 条件 UPDATE（幂等）：仅当现值为本批 JSON 地址时执行——用户手改其他 URL 的行不动。
UPDATE runtime_config
SET config_value = json_remove(json_set(config_value,
        '$.params.policyUrl', 'https://www.gov.cn/zhengce/'), '$.params.policyMaxItems'),
    updated_at   = strftime('%Y-%m-%dT%H:%M:%SZ', 'now')
WHERE config_key = 'datasource.POLICY'
  AND json_extract(config_value, '$.params.policyUrl') = 'https://www.gov.cn/zhengce/zuixin/ZUIXINZHENGCE.json';
