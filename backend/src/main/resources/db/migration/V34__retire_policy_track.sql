-- V34 · 写路径退役收口（V2.3-M23 T203，方案 §5 / ADR-0062 裁决二，Gate 3）。
-- 删 job.POLICY_FETCH / job.POLICY_TENDENCY 两键：Job bean 已整链删除，种子亦不再产出——
-- 留键会成为孤儿配置（任务中心/校验面均无对应 Job，防孤儿键误报）。
-- datasource.POLICY 行不动（冻结留档，零消费方，随 policy_item 一并纳入 M10 生命周期治理）；
-- policy_item 表零 DDL（35 行冻结只读，沿「历史不回填」先例）。
DELETE FROM runtime_config WHERE config_key IN ('job.POLICY_FETCH', 'job.POLICY_TENDENCY');
