-- T16 · 数据源缺失事件表（M1 后端可观测性收尾：adapter 降级/缺失时记 data_source_event，旁路不阻断主链路）
-- 来源：技术方案 §4.2 data_source_event DDL（原样落）+ §4.3 流程 1（各 adapter 失败/缺失 → 记 data_source_event，
--        该分区 sourceStatus=missing/failed）+ §4.4（onDegraded 钩子）+ §5 非功能（可观测：数据源缺失记 WARN）。
-- 触发点（AbstractSourceAdapter 模板降级路径旁路记录，现有 T03~T07 各 adapter 零改动自动获得记录能力）：
--   event_type 1 缺失（MISSING）  —— doFetch 返回 Optional.empty()（源当日无数据，成功调用非异常）
--   event_type 2 超时（TIMEOUT）  —— ResilienceRunner Future.get 超时、重试耗尽（FailureKind.TIMEOUT）
--   event_type 3 错误（ERROR）    —— doFetch 抛异常 / 被中断（FailureKind.ERROR / INTERRUPTED）
--   event_type 4 限频（LIMITED）  —— 熔断器 allowRequest=false（circuit-open，快速失败不调 doFetch）
-- subject_id 可空：本批调用点均带 subjectId（fetch(subject)），但 DDL 允许空以备未来无标的上下文的事件源。
-- 本表不设 version 列：data_source_event 为追加型事件流水（仅 INSERT，无并发 UPDATE 竞争），按 §4.2 DDL 原样落
--                      （三必备 id/created_at/updated_at 已齐，无乐观锁需求）。
-- created_at 存整秒 ISO-8601 文本（如 2026-09-21T02:00:00Z）：去掉纳秒小数保证定长、字典序即时间序，
--               idx_dse_source_time 的范围扫描按字符串比较即可命中索引且结果正确（与 anomaly_event 一致）。
CREATE TABLE data_source_event (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  source_code TEXT    NOT NULL,                 -- QUOTE/FINANCE/VALUATION/ANNOUNCE/NEWS/POLICY
  event_type  TINYINT NOT NULL,                 -- 1缺失 2超时 3错误 4限频
  subject_id  INTEGER,                          -- 标的内部主键，可空
  detail      TEXT,                             -- 人读详情（如 reason=no-data / exhausted sourceCode=QUOTE / circuit-open）
  created_at  TEXT    NOT NULL,                 -- ISO-8601 整秒（UTC），事件记录时刻
  updated_at  TEXT    NOT NULL
);
CREATE INDEX idx_dse_source_time ON data_source_event(source_code, created_at);
