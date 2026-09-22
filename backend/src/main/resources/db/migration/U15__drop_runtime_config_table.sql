-- 回滚 V15：反向删除索引与配置表（对齐 U1~U14 手动回滚脚本惯例，Flyway 社区版不自动执行 U__ 脚本）
-- 注意：回滚即丢弃页面写入的运行时配置（重启后回落到 yml 种子值），属破坏性操作，仅部署回退时使用。
DROP INDEX IF EXISTS idx_job_log_time;
DROP INDEX IF EXISTS idx_policy_created;
DROP INDEX IF EXISTS idx_anomaly_time;
DROP TABLE IF EXISTS runtime_config;
