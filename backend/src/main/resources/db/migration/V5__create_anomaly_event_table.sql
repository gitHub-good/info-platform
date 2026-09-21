-- T13 · 异动事件表（@Scheduled 轮询行情 + 阈值触发 + 业务键应用层去重 + 发 Spring 事件驱动 SSE 推送）
-- 来源：技术方案 §4.2 anomaly_event DDL（原样落）+ §4.3 流程 3 + ADR-0006（SSE + Spring 事件驱动）
-- 去重：无 UNIQUE 约束（§4.2 DDL 未定义），按 §4.4 业务语义键 subjectId + anomalyType + triggerDate
--        应用层 existsByBusinessKey 查重（同标的同类型同日只记一条），避免对同一标的一天重复推 N 次。
-- pushed TINYINT：0 未推 / 1 已推（T14 PushService @EventListener 消费本事件后置 1）。
-- 本表不设 version 列：anomaly_event 为追加型事件流水，写操作仅 INSERT + 单一 pushed 标志翻转，
--                       无并发 UPDATE 竞争，按 §4.2 DDL 原样落（三必备 id/created_at/updated_at 已齐）。
-- trigger_time 存整秒 ISO-8601 文本（如 2026-09-21T02:00:00Z）：去掉纳秒小数保证定长、
--               字典序即时间序，idx_anomaly_subject_time 的范围扫描按字符串比较即可命中索引且结果正确。
CREATE TABLE anomaly_event (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  subject_id    INTEGER NOT NULL,
  anomaly_type  TINYINT NOT NULL,             -- 1涨跌幅 2量异动 3事件
  change_pct    DECIMAL(8,4),
  current_price DECIMAL(12,4),
  trigger_time  TEXT    NOT NULL,             -- ISO-8601 触发时刻（整秒，UTC）
  detail        TEXT,
  pushed        TINYINT NOT NULL DEFAULT 0,   -- 0未推 1已推
  created_at    TEXT    NOT NULL,
  updated_at    TEXT    NOT NULL
);
CREATE INDEX idx_anomaly_subject_time ON anomaly_event(subject_id, trigger_time);
