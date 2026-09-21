-- T14 · 推送记录表（SSE 推送通道 + 幂等防重推）
-- 来源：技术方案 §4.2 push_record DDL（原样落）+ §4.3 流程 3 + §4.4 幂等 + ADR-0006（SSE + Spring 事件驱动）
-- 幂等防重推：idempotency_key = userId + pushType + refId，DB UNIQUE(idempotency_key) 为最后防线——
--               同一异动对同一用户重复消费（@Async 重投/事件重发）→ INSERT 冲突 → 应用层 saveIfAbsent 返回 empty 跳过，绝不重推。
-- status TINYINT：0 待推 / 1 已推 / 2 失败（推送失败重试 1 次仍失败置 2 + ERROR 告警）。
-- retry_count：重试次数（重试 1 次 → retry_count=1）；推送成功即便经过重试也置 status=1。
-- 离线用户：push_record 留 status=0 待推，用户重连 SSE 时按 Last-Event-ID 补拉待推记录（status=0）。
-- version 列：push_record 存在并发 UPDATE（状态翻转），按 §4.2 DDL 带 version 乐观锁（与 anomaly_event 不同）。
-- subject_id 可空：事件/政策/AI 简报类推送未必绑定单个标的；异动推送必填。
-- ref_id TEXT：关联 anomaly_event.id / ai_brief.id / policy_item.id，供回查与幂等键拼接。
-- pushed_at：实际推送成功时刻（status=1 时回填），ISO-8601 文本。
CREATE TABLE push_record (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id         INTEGER NOT NULL,
  subject_id      INTEGER,
  push_type       TINYINT NOT NULL,           -- 1异动 2事件 3政策 4AI简报 5每日推荐
  ref_id          TEXT,                       -- 关联事件/简报id（异动=anomaly_event.id）
  content         TEXT    NOT NULL,
  idempotency_key TEXT    NOT NULL,           -- = userId + pushType + refId
  status          TINYINT NOT NULL DEFAULT 0,  -- 0待推 1已推 2失败
  pushed_at       TEXT,
  retry_count     INTEGER NOT NULL DEFAULT 0,
  created_at      TEXT    NOT NULL,
  updated_at      TEXT    NOT NULL,
  version         INTEGER NOT NULL DEFAULT 0,
  UNIQUE(idempotency_key)
);
CREATE INDEX idx_push_user_status ON push_record(user_id, status);
