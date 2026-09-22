-- T33 · Job 执行留痕表（@Scheduled Job 经 AOP 切面记录每次执行：STARTED→SUCCESS/FAILED）
-- 来源：需求「Job 执行留痕 + 前端日志页」+ 后端基线 §四（每表三字段 created_at/updated_at，时间戳存 ISO-8601 文本）。
-- 写入方：JobExecutionRecorder（start 插 STARTED → success/failed 更新），由 JobExecutionAspect
--        @Around 拦截各 @Scheduled 方法驱动；查询方：JobLogController GET /api/v1/job-logs 游标分页。
-- 不设 version 列：job_execution_log 为追加型流水，写操作仅 INSERT（STARTED）+ 单次状态翻转（SUCCESS/FAILED），
--                  无并发 UPDATE 竞争（每个 @Scheduled 方法同进程单线程串行），按 §4.2 原样落（三必备已齐）。
-- 时间戳存整秒 ISO-8601 文本（Instant.toString），字典序即时间序；duration_millis 记执行耗时毫秒。
-- processed_count/error_count 首期由 AOP 记 0（拿不到 Job 内部统计）；各 Job 可选显式调 Recorder 增强。
CREATE TABLE job_execution_log (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  job_name        TEXT    NOT NULL,                 -- Job 名（派生自类名，如 PolicyFetchJob）
  start_time      TEXT    NOT NULL,                 -- ISO-8601 执行开始时刻（整秒，UTC）
  end_time        TEXT,                             -- ISO-8601 执行结束时刻（STARTED 时为 NULL）
  status          TEXT    NOT NULL,                 -- STARTED / SUCCESS / FAILED
  duration_millis INTEGER,                          -- 执行耗时毫秒（end - start）
  processed_count INTEGER NOT NULL DEFAULT 0,        -- 处理条数（首期 AOP 记 0）
  error_count     INTEGER NOT NULL DEFAULT 0,        -- 错误条数（首期 AOP 记 0）
  error_message   TEXT,                             -- FAILED 时的异常摘要
  created_at      TEXT    NOT NULL,
  updated_at      TEXT    NOT NULL
);
-- 按 job_name 过滤 + created_at 倒序查询的复合索引（job_name 等值用前缀，created_at 辅助排序）。
-- id 为自增主键，其顺序即 created_at 顺序，游标分页按 id DESC 取 newest-first（对齐 PushRepository 模式）。
CREATE INDEX idx_job_log_name_time ON job_execution_log(job_name, created_at);
