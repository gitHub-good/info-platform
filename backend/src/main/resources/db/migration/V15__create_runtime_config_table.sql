-- T34 · 运行时配置表（配置中心权威存储：页面可改的运行时配置持久化，重启不丢）+ 概览聚合窗口索引
-- 来源：REQ-20260922-02 故事 2 场景 6「重启不丢」+ ADR-0017（自建配置中心快照方案）、故事 5 概览仪表盘取数口径（§4.6）。
-- 模型：单表 key-value；config_key 域前缀命名（llm./datasource./job./aggregation.），config_value 存 JSON 文档。
-- 写入方：RuntimeConfigService（校验通过 → upsert → 刷新内存快照 → 发 RuntimeConfigChangedEvent）；
--         启动 seed-if-absent 从 yml 导入（DB 已有该键则不动，DB 为权威；改过 yml 不再覆盖页面值）。
-- 读取方：ConfigCenter 启动全量载入不可变快照（volatile 引用整体替换），热路径零 DB 读。
-- 不设 version 列：单用户单写者无并发竞争；并发防呆用请求携带 expectedUpdatedAt 比对 updated_at（30065）。
-- 时间戳存整秒 ISO-8601 文本（Instant.toString），字典序即时间序，对齐既有表惯例。
CREATE TABLE runtime_config (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  config_key   TEXT    NOT NULL,                 -- 如 llm.global / llm.provider.deepseek / datasource.QUOTE / job.POLICY_FETCH
  config_value TEXT    NOT NULL,                 -- JSON 文档（结构由各域校验器约束，见方案 §4.1 键空间表）
  description  TEXT,                             -- 人读说明（种子导入时写入）
  created_at   TEXT    NOT NULL,
  updated_at   TEXT    NOT NULL,
  UNIQUE(config_key)
);

-- T42 · 概览仪表盘聚合窗口索引（既有表补索引，取数口径见方案 §4.6；随 V15 一并落地）
-- 今日异动条数：anomaly_event.trigger_time 落今日扫描。
CREATE INDEX idx_anomaly_time   ON anomaly_event(trigger_time);
-- 政策动态：入库时间近 24h 行数（裁定用 created_at 而非 published_at，避免历史回填计数失真）。
CREATE INDEX idx_policy_created ON policy_item(created_at);
-- 任务健康：近 24h 执行总数/FAILED 数扫描。
CREATE INDEX idx_job_log_time   ON job_execution_log(created_at);
