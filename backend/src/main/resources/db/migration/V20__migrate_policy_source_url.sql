-- M12 · REQ-20260925-09 T93 政策源扩容：policyUrl 种子指向 ZUIXINZHENGCE.json + policyMaxItems（技术方案 §4.2 / ADR-0037 决策 5）
-- 背景：zuixin HTML 列表为 AJAX 空壳（2026-09-22 实测，方案 §1.2 实测 3），静态 ZUIXINZHENG.json 可单次取任意前 N 条（1100 条）。
-- 条件 UPDATE（幂等）：仅当现值为旧目录页时改指 JSON 并增 policyMaxItems=30——页面已手改其他 URL 的行不动（用户意图优先）；
-- 重跑安全；回滚见 U20（还原旧 URL 并 json_remove policyMaxItems）。GovPolicyClient 双分支兼容（页面手改回 HTML URL 即热回退）。
UPDATE runtime_config
SET config_value = json_set(config_value,
        '$.params.policyUrl',       'https://www.gov.cn/zhengce/zuixin/ZUIXINZHENGCE.json',
        '$.params.policyMaxItems',  30),
    updated_at   = strftime('%Y-%m-%dT%H:%M:%SZ', 'now')
WHERE config_key = 'datasource.POLICY'
  AND json_extract(config_value, '$.params.policyUrl') = 'https://www.gov.cn/zhengce/';
