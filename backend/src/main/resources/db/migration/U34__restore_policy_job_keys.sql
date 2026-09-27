-- U34 · 回滚 V34：重插两 job 键（enabled=0 留档态，方案 §5 U34 条款）。
-- 仅恢复配置行供配置找回——恢复运行需同批 git revert（Job bean 已随 T203 删除，键行不再驱动任何调度）。
-- 形态对齐 JobRuntimeConfigSeeder fixedDelay 种子（V20 时代 POLICY_FETCH 1h / POLICY_TENDENCY 30min 缺省）。
INSERT INTO runtime_config (config_key, config_value, description, created_at, updated_at)
VALUES
  ('job.POLICY_FETCH',
   '{"enabled":false,"scheduleType":"FIXED_DELAY","intervalMillis":3600000,"userIds":""}',
   '政策抓取任务调度（已退役留档：Job bean 随 V2.3-M23 T203 删除，恢复运行需代码回滚）',
   strftime('%Y-%m-%dT%H:%M:%SZ', 'now'),
   strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
  ('job.POLICY_TENDENCY',
   '{"enabled":false,"scheduleType":"FIXED_DELAY","intervalMillis":1800000,"userIds":""}',
   '政策倾向判断任务调度（已退役留档：Job bean 随 V2.3-M23 T203 删除，恢复运行需代码回滚）',
   strftime('%Y-%m-%dT%H:%M:%SZ', 'now'),
   strftime('%Y-%m-%dT%H:%M:%SZ', 'now'));
