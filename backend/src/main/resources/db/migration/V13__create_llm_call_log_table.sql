-- T30 · LLM 调用留痕表（每次 LlmGateway.chat 落一行：缓存命中 / 成功 / 失败 / 预算拒绝四态）
-- 来源：任务表 T30「成本治理报表」+ 技术方案 §4.4 成本上限 + Spike-2 §8.3（成本报表）。
--       Spike-2 §8.3 原建议复用 ai_brief.cost_tokens，但该表只覆盖简报成功路径——
--       不含政策倾向（T28）、失败调用、缓存命中、provider 维度与预算拦截，故 T30 落独立留痕表（ADR-0015）。
-- 写入方：LlmGatewayImpl 经 LlmCallLogger（begin → markCacheHit/markSuccess/markFailed/markRejected → save），
--         留痕失败仅记 ERROR 不阻断调用主链路。
-- 查询方：LlmCostReportService（GET /api/v1/llm-cost-report?window=today|7d|30d 窗口聚合）。
-- 不设 version 列：追加型流水，写操作仅 INSERT（终态一次写入，无 UPDATE 竞争），对齐 job_execution_log 模式。
-- 时间戳存整秒 ISO-8601 文本（Instant.toString），字典序即时间序；duration_millis 记端到端耗时毫秒。
-- cost_micros 为成本估算（百万分之一元，整数存储防浮点累加漂移；未配置单价的 provider 记 0）。
CREATE TABLE llm_call_log (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id           INTEGER NOT NULL DEFAULT 0,   -- 发起用户（0=系统调用：无认证上下文，如定时任务）
  provider          TEXT,                         -- 实际服务厂商配置键（deepseek/glm）；缓存命中记原响应厂商；失败/拒绝为 NULL
  model             TEXT,                         -- 实际模型名（含 request 覆盖）
  scene_key         TEXT NOT NULL,                -- 场景键（= LlmRequest.briefTypeKey："1"个股 "2"事件 "3"政策 "4"每日推荐）
  status            TEXT    NOT NULL,             -- SUCCESS / FAILED / REJECTED（枚举名文本）
  cache_hit         INTEGER NOT NULL DEFAULT 0,   -- 1=本地 Caffeine 缓存命中（0 外呼、0 token、0 成本）
  prompt_tokens     INTEGER NOT NULL DEFAULT 0,   -- 输入 token（成功外呼才有值）
  completion_tokens INTEGER NOT NULL DEFAULT 0,   -- 输出 token（成功外呼才有值）
  cost_micros       INTEGER NOT NULL DEFAULT 0,   -- 成本估算（微元；token×元/百万token=微元，ADR-0015）
  duration_millis   INTEGER NOT NULL DEFAULT 0,   -- chat 端到端耗时（缓存命中≈0）
  error_message     TEXT,                         -- FAILED：attempted provider 链；REJECTED：预算拦截原因
  created_at        TEXT    NOT NULL,
  updated_at        TEXT    NOT NULL
);
-- 成本报表按时间窗扫描（created_at >= 窗口起点），字典序比较即时间序。
CREATE INDEX idx_llm_call_log_created ON llm_call_log(created_at);
